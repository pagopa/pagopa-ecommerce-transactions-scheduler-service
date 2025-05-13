package it.pagopa.ecommerce.transactions.scheduler.publishers.v2

import com.azure.core.http.rest.Response
import com.azure.core.http.rest.ResponseBase
import com.azure.storage.queue.models.QueueStorageException
import com.azure.storage.queue.models.SendMessageResult
import com.mongodb.MongoException
import it.pagopa.ecommerce.commons.client.QueueAsyncClient
import it.pagopa.ecommerce.commons.documents.v2.Transaction
import it.pagopa.ecommerce.commons.documents.v2.TransactionExpiredData
import it.pagopa.ecommerce.commons.documents.v2.TransactionExpiredEvent
import it.pagopa.ecommerce.commons.domain.v2.*
import it.pagopa.ecommerce.commons.domain.v2.TransactionId
import it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OperationResultDto
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
import it.pagopa.ecommerce.commons.queues.QueueEvent
import it.pagopa.ecommerce.commons.queues.TracingUtilsTests
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils
import it.pagopa.ecommerce.transactions.scheduler.repositories.TransactionsEventStoreRepository
import it.pagopa.ecommerce.transactions.scheduler.repositories.TransactionsViewRepository
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.reactor.mono
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.willReturn
import org.mockito.kotlin.willReturnConsecutively
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

@ExtendWith(MockitoExtension::class)
class TransactionExpiredEventPublisherTests {

    @Mock private lateinit var queueAsyncClient: QueueAsyncClient

    @Mock private lateinit var viewRepository: TransactionsViewRepository

    @Mock
    private lateinit var eventStoreRepository:
        TransactionsEventStoreRepository<TransactionExpiredData>

    @Captor private lateinit var queueArgumentCaptor: ArgumentCaptor<QueueEvent<*>>

    @Captor private lateinit var viewArgumentCaptor: ArgumentCaptor<Transaction>

    @Captor private lateinit var eventStoreCaptor: ArgumentCaptor<TransactionExpiredEvent>

    @Captor private lateinit var eventsVisibilityTimeoutCaptor: ArgumentCaptor<Duration>

    private val transientQueueTTLSeconds = 30

    private val tracingUtils = TracingUtilsTests.getMock()

    @Test
    fun `Should publish all events`() {
        // preconditions
        val baseDocuments =
            generateTransactionBaseDocuments(
                howMany = 5,
                transactionType = TransactionType.ACTIVATED_ONLY
            )

        val expectedGeneratedEvents =
            baseDocuments
                .map { baseTransactionToExpiryEvent(it) }
                .toList()
                .sortedBy { it.transactionId }
        val sendMessageResult = SendMessageResult()
        sendMessageResult.messageId = "msgId"
        sendMessageResult.timeNextVisible = OffsetDateTime.now()
        val queueAsyncClientResponse: Mono<Response<SendMessageResult>> =
            Mono.just(ResponseBase(null, 200, null, sendMessageResult, null))
        given(
                queueAsyncClient.sendMessageWithResponse(
                    queueArgumentCaptor.capture(),
                    eventsVisibilityTimeoutCaptor.capture(),
                    any()
                )
            )
            .willReturn(queueAsyncClientResponse)
        given(eventStoreRepository.save(eventStoreCaptor.capture())).willAnswer {
            Mono.just(it.arguments[0])
        }
        given(viewRepository.findByTransactionId(org.mockito.kotlin.any())).willAnswer {
            val transaction =
                TransactionTestUtils.transactionDocument(
                    TransactionStatusDto.ACTIVATED,
                    ZonedDateTime.now()
                )
            transaction.transactionId = it.arguments[0].toString()
            mono { transaction }
        }
        given(viewRepository.save(viewArgumentCaptor.capture())).willAnswer {
            Mono.just(it.arguments[0])
        }

        val batchExecutionTimeWindow = TimeUnit.HOURS.toMillis(1)
        // test
        StepVerifier.create(
                TransactionExpiredEventPublisher(
                        expiredEventQueueAsyncClient = queueAsyncClient,
                        viewRepository = viewRepository,
                        eventStoreRepository = eventStoreRepository,
                        parallelEventToProcess = 1,
                        transientQueueTTLSeconds = transientQueueTTLSeconds,
                        tracingUtils = tracingUtils
                    )
                    .publishExpiryEvents(baseDocuments, batchExecutionTimeWindow, 5, 0)
            )
            .expectNext(true)
            .verifyComplete()
        // assertions

        val viewCapturedArguments = viewArgumentCaptor.allValues
        viewCapturedArguments.sortBy { it.transactionId }
        val eventCapturedArguments = eventStoreCaptor.allValues
        eventCapturedArguments.sortBy { it.transactionId }
        val queueCapturedArguments = queueArgumentCaptor.allValues
        queueCapturedArguments.sortBy { it.event.transactionId }
        for ((idx, expectedGeneratedEvent) in expectedGeneratedEvents.withIndex()) {
            /*
             * verify that event stored into event store collection and sent on the queue are the expected ones.
             * since some event fields such id and creation timestamp are created automatically in constructor
             * equality is verified field by field here
             */
            equalityAssertionsOnEventStore(expectedGeneratedEvent, eventCapturedArguments[idx], idx)
            equalityAssertionsOnView(
                expectedGeneratedEvent,
                viewCapturedArguments[idx],
                idx,
                TransactionStatusDto.EXPIRED_NOT_AUTHORIZED
            )
            equalityAssertionsOnSentEvent(expectedGeneratedEvent, queueCapturedArguments[idx], idx)
        }

        val expectedEventsIntertime = batchExecutionTimeWindow / expectedGeneratedEvents.size
        val eventsVisibilityTimeouts = eventsVisibilityTimeoutCaptor.allValues
        var currentIdx = eventsVisibilityTimeoutCaptor.allValues.size - 1
        eventsVisibilityTimeouts.sort()
        var visibilityTimeoutIntertime: Long
        while (currentIdx > 0) {
            visibilityTimeoutIntertime =
                eventsVisibilityTimeouts[currentIdx]
                    .minus(eventsVisibilityTimeouts[currentIdx - 1])
                    .toMillis()
            assertEquals(
                expectedEventsIntertime,
                visibilityTimeoutIntertime,
                "Testing event visibility timeout interleave. event[$currentIdx] - event[$currentIdx-1]"
            )
            currentIdx--
        }
        verify(queueAsyncClient, times(5))
            .sendMessageWithResponse(
                any<QueueEvent<*>>(),
                any(),
                eq(Duration.ofSeconds(transientQueueTTLSeconds.toLong()))
            )
        verify(eventStoreRepository, times(5)).save(any())
        verify(viewRepository, times(5)).save(any())
    }

    @Test
    fun `Should fails all for exception updating transaction view`() {
        // preconditions

        val sendMessageResult = SendMessageResult()
        sendMessageResult.messageId = "msgId"
        sendMessageResult.timeNextVisible = OffsetDateTime.now()
        given(eventStoreRepository.save(eventStoreCaptor.capture())).willAnswer {
            Mono.just(it.arguments[0])
        }
        given(viewRepository.findByTransactionId(org.mockito.kotlin.any())).willAnswer {
            val transaction =
                TransactionTestUtils.transactionDocument(
                    TransactionStatusDto.ACTIVATED,
                    ZonedDateTime.now()
                )
            transaction.transactionId = it.arguments[0].toString()
            mono { transaction }
        }
        given(viewRepository.save(viewArgumentCaptor.capture())).willReturn {
            Mono.error(MongoException("Error updating view"))
        }

        val batchExecutionTimeWindow = TimeUnit.HOURS.toMillis(1)
        // test

        StepVerifier.create(
                TransactionExpiredEventPublisher(
                        expiredEventQueueAsyncClient = queueAsyncClient,
                        viewRepository = viewRepository,
                        eventStoreRepository = eventStoreRepository,
                        parallelEventToProcess = 1,
                        transientQueueTTLSeconds = transientQueueTTLSeconds,
                        tracingUtils = tracingUtils
                    )
                    .publishExpiryEvents(
                        generateTransactionBaseDocuments(
                            howMany = 5,
                            transactionType = TransactionType.ACTIVATED_ONLY
                        ),
                        batchExecutionTimeWindow,
                        5,
                        0
                    )
            )
            .expectNext(false)
            .verifyComplete()
        // assertions
        verify(queueAsyncClient, times(0))
            .sendMessageWithResponse(
                any<QueueEvent<*>>(),
                any(),
                eq(Duration.ofSeconds(transientQueueTTLSeconds.toLong()))
            )
        verify(viewRepository, times(5)).save(any())
        verify(eventStoreRepository, times(5)).save(any())
    }

    @Test
    fun `Should fails all for error saving event to event store`() {
        // preconditions

        given(eventStoreRepository.save(eventStoreCaptor.capture())).willReturn {
            Mono.error(MongoException("Error saving event"))
        }
        val batchExecutionTimeWindow = TimeUnit.HOURS.toMillis(1)
        // test
        StepVerifier.create(
                TransactionExpiredEventPublisher(
                        expiredEventQueueAsyncClient = queueAsyncClient,
                        viewRepository = viewRepository,
                        eventStoreRepository = eventStoreRepository,
                        parallelEventToProcess = 1,
                        transientQueueTTLSeconds = transientQueueTTLSeconds,
                        tracingUtils = tracingUtils
                    )
                    .publishExpiryEvents(
                        generateTransactionBaseDocuments(
                            howMany = 5,
                            transactionType = TransactionType.ACTIVATED_ONLY
                        ),
                        batchExecutionTimeWindow,
                        5,
                        0
                    )
            )
            .expectNext(false)
            .verifyComplete()
        // assertions
        verify(queueAsyncClient, times(0))
            .sendMessageWithResponse(
                any<QueueEvent<*>>(),
                any(),
                eq(Duration.ofSeconds(transientQueueTTLSeconds.toLong()))
            )
        verify(viewRepository, times(0)).save(any())
        verify(eventStoreRepository, times(5)).save(any())
    }

    @Test
    fun `Should fails all for error sending event to queue`() {
        // preconditions
        val transactions =
            generateTransactionBaseDocuments(
                howMany = 5,
                transactionType = TransactionType.ACTIVATED_ONLY
            )
        val sendMessageResult = SendMessageResult()
        sendMessageResult.messageId = "msgId"
        sendMessageResult.timeNextVisible = OffsetDateTime.now()
        given(queueAsyncClient.sendMessageWithResponse(any<QueueEvent<*>>(), any(), any()))
            .willReturn(
                Mono.error(QueueStorageException("Error sending message to queue", null, null))
            )
        given(eventStoreRepository.save(eventStoreCaptor.capture())).willAnswer {
            Mono.just(it.arguments[0])
        }
        given(viewRepository.findByTransactionId(org.mockito.kotlin.any())).willAnswer {
            val transaction =
                TransactionTestUtils.transactionDocument(
                    TransactionStatusDto.ACTIVATED,
                    ZonedDateTime.now()
                )
            transaction.transactionId = it.arguments[0].toString()
            mono { transaction }
        }
        given(viewRepository.save(viewArgumentCaptor.capture())).willAnswer {
            Mono.just(it.arguments[0])
        }
        val batchExecutionTimeWindow = TimeUnit.HOURS.toMillis(1)
        // test
        StepVerifier.create(
                TransactionExpiredEventPublisher(
                        expiredEventQueueAsyncClient = queueAsyncClient,
                        viewRepository = viewRepository,
                        eventStoreRepository = eventStoreRepository,
                        parallelEventToProcess = 1,
                        transientQueueTTLSeconds = transientQueueTTLSeconds,
                        tracingUtils = tracingUtils
                    )
                    .publishExpiryEvents(transactions, batchExecutionTimeWindow, 5, 0)
            )
            .expectNext(false)
            .verifyComplete()
        // assertions
        verify(queueAsyncClient, times(5))
            .sendMessageWithResponse(
                any<QueueEvent<*>>(),
                any(),
                eq(Duration.ofSeconds(transientQueueTTLSeconds.toLong()))
            )
        verify(viewRepository, times(5)).save(any())
        verify(eventStoreRepository, times(5)).save(any())
    }

    @Test
    fun `Should continue processing transactions for error saving event to event store`() {
        // preconditions
        val errorTransactionId = TransactionId(UUID.randomUUID())

        val okTransactions =
            generateTransactionBaseDocuments(
                howMany = 5,
                transactionType = TransactionType.ACTIVATED_ONLY
            )
        val koTransactions =
            generateTransactionBaseDocuments(
                howMany = 1,
                transactionId = errorTransactionId.uuid,
                transactionType = TransactionType.ACTIVATED_ONLY
            )
        val allTransactions = koTransactions.plus(okTransactions)

        val okEvents = okTransactions.map { baseTransactionToExpiryEvent(it) }
        val koEvents = koTransactions.map { baseTransactionToExpiryEvent(it) }
        val expectedCapturedSavedEvents = koEvents.plus(okEvents).sortedBy { it.transactionId }
        val expectedCapturedSavedViews: List<TransactionExpiredEvent> =
            okEvents.sortedBy { it.transactionId }
        val expectedCapturedSentEvents: List<TransactionExpiredEvent> =
            okEvents.sortedBy { it.transactionId }
        val allEvents = koTransactions.plus(okTransactions).map { baseTransactionToExpiryEvent(it) }
        val sendMessageResult = SendMessageResult()
        sendMessageResult.messageId = "msgId"
        sendMessageResult.timeNextVisible = OffsetDateTime.now()
        val queueAsyncClientResponse: Mono<Response<SendMessageResult>> =
            Mono.just(ResponseBase(null, 200, null, sendMessageResult, null))
        given(
                queueAsyncClient.sendMessageWithResponse(
                    queueArgumentCaptor.capture(),
                    eventsVisibilityTimeoutCaptor.capture(),
                    any()
                )
            )
            .willReturn(queueAsyncClientResponse)
        given(viewRepository.findByTransactionId(org.mockito.kotlin.any())).willAnswer {
            val transaction =
                TransactionTestUtils.transactionDocument(
                    TransactionStatusDto.ACTIVATED,
                    ZonedDateTime.now()
                )
            transaction.transactionId = it.arguments[0].toString()
            mono { transaction }
        }

        given(eventStoreRepository.save(eventStoreCaptor.capture()))
            .willReturnConsecutively(
                allEvents.map { event ->
                    if (event.transactionId != errorTransactionId.value()) {
                        Mono.just(event)
                    } else {
                        Mono.error(MongoException("Error writing event to event store"))
                    }
                }
            )
        given(viewRepository.save(viewArgumentCaptor.capture())).willAnswer {
            Mono.just(it.arguments[0])
        }

        val batchExecutionTimeWindow = TimeUnit.HOURS.toMillis(1)
        // test
        StepVerifier.create(
                TransactionExpiredEventPublisher(
                        expiredEventQueueAsyncClient = queueAsyncClient,
                        viewRepository = viewRepository,
                        eventStoreRepository = eventStoreRepository,
                        parallelEventToProcess = 1,
                        transientQueueTTLSeconds = transientQueueTTLSeconds,
                        tracingUtils = tracingUtils
                    )
                    .publishExpiryEvents(
                        allTransactions,
                        batchExecutionTimeWindow,
                        allEvents.size.toLong(),
                        0
                    )
            )
            .expectNext(false)
            .verifyComplete()
        // assertions
        verify(eventStoreRepository, times(6)).save(any())
        verify(viewRepository, times(5)).save(any())
        verify(queueAsyncClient, times(5))
            .sendMessageWithResponse(
                any<QueueEvent<*>>(),
                any(),
                eq(Duration.ofSeconds(transientQueueTTLSeconds.toLong()))
            )

        val viewCapturedArguments = viewArgumentCaptor.allValues
        viewCapturedArguments.sortBy { it.transactionId }
        val eventCapturedArguments = eventStoreCaptor.allValues
        eventCapturedArguments.sortBy { it.transactionId }
        val queueCapturedArguments = queueArgumentCaptor.allValues
        queueCapturedArguments.sortBy { it.event.transactionId }
        for ((idx, expectedEvent) in expectedCapturedSavedEvents.withIndex()) {
            equalityAssertionsOnEventStore(expectedEvent, eventCapturedArguments[idx], idx)
        }

        for ((idx, expectedEvent) in expectedCapturedSavedViews.withIndex()) {
            equalityAssertionsOnView(
                expectedEvent,
                viewCapturedArguments[idx],
                idx,
                TransactionStatusDto.EXPIRED_NOT_AUTHORIZED
            )
        }

        for ((idx, expectedEvent) in expectedCapturedSentEvents.withIndex()) {
            equalityAssertionsOnSentEvent(expectedEvent, queueCapturedArguments[idx], idx)
        }

        val expectedEventsIntertime = batchExecutionTimeWindow / allEvents.size
        val eventsVisibilityTimeouts = eventsVisibilityTimeoutCaptor.allValues
        eventsVisibilityTimeouts.sort()
        var currentIdx = eventsVisibilityTimeoutCaptor.allValues.size - 1
        var visibilityTimeoutIntertime: Long
        while (currentIdx > 0) {
            visibilityTimeoutIntertime =
                eventsVisibilityTimeouts[currentIdx]
                    .minus(eventsVisibilityTimeouts[currentIdx - 1])
                    .toMillis()
            assertEquals(
                expectedEventsIntertime,
                visibilityTimeoutIntertime,
                "Testing event visibility timeout interleave. event[$currentIdx] - event[${currentIdx - 1}]"
            )
            currentIdx--
        }
    }

    @Test
    fun `Should continue processing transactions for error saving view`() {
        // preconditions
        val errorTransactionId = TransactionId(UUID.randomUUID())
        val okTransactions =
            generateTransactionBaseDocuments(
                howMany = 5,
                transactionType = TransactionType.ACTIVATED_ONLY
            )
        val koTransactions =
            generateTransactionBaseDocuments(
                howMany = 1,
                transactionId = errorTransactionId.uuid,
                transactionType = TransactionType.ACTIVATED_ONLY
            )
        val allTransactions = koTransactions.plus(okTransactions)

        val okEvents = okTransactions.map { baseTransactionToExpiryEvent(it) }
        val koEvents = koTransactions.map { baseTransactionToExpiryEvent(it) }
        val expectedCapturedSavedEvents = koEvents.plus(okEvents).sortedBy { it.transactionId }
        val expectedCapturedSavedViews = koEvents.plus(okEvents).sortedBy { it.transactionId }
        val expectedCapturedSentEvents: List<TransactionExpiredEvent> =
            okEvents.sortedBy { it.transactionId }
        val allEvents = koTransactions.plus(okTransactions).map { baseTransactionToExpiryEvent(it) }
        val sendMessageResult = SendMessageResult()
        sendMessageResult.messageId = "msgId"
        sendMessageResult.timeNextVisible = OffsetDateTime.now()
        val queueAsyncClientResponse: Mono<Response<SendMessageResult>> =
            Mono.just(ResponseBase(null, 200, null, sendMessageResult, null))
        given(
                queueAsyncClient.sendMessageWithResponse(
                    queueArgumentCaptor.capture(),
                    eventsVisibilityTimeoutCaptor.capture(),
                    any()
                )
            )
            .willReturn(queueAsyncClientResponse)
        given(eventStoreRepository.save(eventStoreCaptor.capture())).willAnswer {
            Mono.just(it.arguments[0])
        }
        given(viewRepository.findByTransactionId(org.mockito.kotlin.any())).willAnswer {
            val transaction =
                TransactionTestUtils.transactionDocument(
                    TransactionStatusDto.ACTIVATED,
                    ZonedDateTime.now()
                )
            transaction.transactionId = it.arguments[0].toString()
            mono { transaction }
        }
        given(viewRepository.save(viewArgumentCaptor.capture())).willAnswer {
            if ((it.arguments[0] as Transaction).transactionId == errorTransactionId.value()) {
                Mono.error(MongoException("Error saving view"))
            } else {
                Mono.just(it.arguments[0])
            }
        }

        val batchExecutionTimeWindow = TimeUnit.HOURS.toMillis(1)
        // test
        StepVerifier.create(
                TransactionExpiredEventPublisher(
                        expiredEventQueueAsyncClient = queueAsyncClient,
                        viewRepository = viewRepository,
                        eventStoreRepository = eventStoreRepository,
                        parallelEventToProcess = 1,
                        transientQueueTTLSeconds = transientQueueTTLSeconds,
                        tracingUtils = tracingUtils
                    )
                    .publishExpiryEvents(
                        allTransactions,
                        batchExecutionTimeWindow,
                        allEvents.size.toLong(),
                        0
                    )
            )
            .expectNext(false)
            .verifyComplete()
        // assertions

        val viewCapturedArguments = viewArgumentCaptor.allValues
        viewCapturedArguments.sortBy { it.transactionId }
        val eventCapturedArguments = eventStoreCaptor.allValues
        eventCapturedArguments.sortBy { it.transactionId }
        val queueCapturedArguments = queueArgumentCaptor.allValues
        queueCapturedArguments.sortBy { it.event.transactionId }
        for ((idx, expectedEvent) in expectedCapturedSavedEvents.withIndex()) {
            equalityAssertionsOnEventStore(expectedEvent, eventCapturedArguments[idx], idx)
        }

        for ((idx, expectedEvent) in expectedCapturedSavedViews.withIndex()) {
            equalityAssertionsOnView(
                expectedEvent,
                viewCapturedArguments[idx],
                idx,
                TransactionStatusDto.EXPIRED_NOT_AUTHORIZED
            )
        }

        for ((idx, expectedEvent) in expectedCapturedSentEvents.withIndex()) {
            equalityAssertionsOnSentEvent(expectedEvent, queueCapturedArguments[idx], idx)
        }
        val expectedEventsIntertime = batchExecutionTimeWindow / allEvents.size
        val eventsVisibilityTimeouts = eventsVisibilityTimeoutCaptor.allValues
        eventsVisibilityTimeouts.sort()
        var currentIdx = eventsVisibilityTimeoutCaptor.allValues.size - 1
        var visibilityTimeoutIntertime: Long
        while (currentIdx > 0) {
            visibilityTimeoutIntertime =
                eventsVisibilityTimeouts[currentIdx]
                    .minus(eventsVisibilityTimeouts[currentIdx - 1])
                    .toMillis()
            assertEquals(
                expectedEventsIntertime,
                visibilityTimeoutIntertime,
                "Testing event visibility timeout interleave. event[$currentIdx] - event[${currentIdx - 1}]"
            )
            currentIdx--
        }
        verify(eventStoreRepository, times(6)).save(any())
        verify(viewRepository, times(6)).save(any())
        verify(queueAsyncClient, times(5))
            .sendMessageWithResponse(
                any<QueueEvent<*>>(),
                any(),
                eq(Duration.ofSeconds(transientQueueTTLSeconds.toLong()))
            )
    }

    @Test
    fun `Should continue processing transactions for error sending event to queue`() {
        // preconditions
        val errorTransactionId = TransactionId(UUID.randomUUID())
        val okTransactions =
            generateTransactionBaseDocuments(
                howMany = 5,
                transactionType = TransactionType.ACTIVATED_ONLY
            )
        val koTransactions =
            generateTransactionBaseDocuments(
                howMany = 1,
                transactionId = errorTransactionId.uuid,
                transactionType = TransactionType.ACTIVATED_ONLY
            )
        val allTransactions = koTransactions.plus(okTransactions)

        val okEvents = okTransactions.map { baseTransactionToExpiryEvent(it) }
        val koEvents = koTransactions.map { baseTransactionToExpiryEvent(it) }

        val expectedCapturedSavedEvents = koEvents.plus(okEvents).sortedBy { it.transactionId }
        val expectedCapturedSavedViews = koEvents.plus(okEvents).sortedBy { it.transactionId }
        val expectedCapturedSentEvents = koEvents.plus(okEvents).sortedBy { it.transactionId }
        val allEvents = koTransactions.plus(okTransactions).map { baseTransactionToExpiryEvent(it) }
        val sendMessageResult = SendMessageResult()
        sendMessageResult.messageId = "msgId"
        sendMessageResult.timeNextVisible = OffsetDateTime.now()
        val queueAsyncClientResponse: Mono<Response<SendMessageResult>> =
            Mono.just(ResponseBase(null, 200, null, sendMessageResult, null))

        given(eventStoreRepository.save(eventStoreCaptor.capture())).willAnswer {
            Mono.just(it.arguments[0])
        }
        given(viewRepository.save(viewArgumentCaptor.capture())).willAnswer {
            Mono.just(it.arguments[0])
        }
        given(viewRepository.findByTransactionId(org.mockito.kotlin.any())).willAnswer {
            val transaction =
                TransactionTestUtils.transactionDocument(
                    TransactionStatusDto.ACTIVATED,
                    ZonedDateTime.now()
                )
            transaction.transactionId = it.arguments[0].toString()
            mono { transaction }
        }
        given(
                queueAsyncClient.sendMessageWithResponse(
                    queueArgumentCaptor.capture(),
                    eventsVisibilityTimeoutCaptor.capture(),
                    any()
                )
            )
            .willAnswer {
                if (
                    (it.arguments[0] as QueueEvent<*>).event.transactionId !=
                        errorTransactionId.value()
                ) {
                    queueAsyncClientResponse
                } else {
                    Mono.error(QueueStorageException("Error sending message to queue", null, null))
                }
            }

        val batchExecutionTimeWindow = TimeUnit.HOURS.toMillis(1)
        // test
        StepVerifier.create(
                TransactionExpiredEventPublisher(
                        expiredEventQueueAsyncClient = queueAsyncClient,
                        viewRepository = viewRepository,
                        eventStoreRepository = eventStoreRepository,
                        parallelEventToProcess = 1,
                        transientQueueTTLSeconds = transientQueueTTLSeconds,
                        tracingUtils = tracingUtils
                    )
                    .publishExpiryEvents(
                        allTransactions,
                        batchExecutionTimeWindow,
                        allEvents.size.toLong(),
                        0
                    )
            )
            .expectNext(false)
            .verifyComplete()
        // assertions

        val viewCapturedArguments = viewArgumentCaptor.allValues
        viewCapturedArguments.sortBy { it.transactionId }
        val eventCapturedArguments = eventStoreCaptor.allValues
        eventCapturedArguments.sortBy { it.transactionId }
        val queueCapturedArguments = queueArgumentCaptor.allValues
        queueCapturedArguments.sortBy { it.event.transactionId }
        for ((idx, expectedEvent) in expectedCapturedSavedEvents.withIndex()) {
            equalityAssertionsOnEventStore(expectedEvent, eventCapturedArguments[idx], idx)
        }

        for ((idx, expectedEvent) in expectedCapturedSavedViews.withIndex()) {
            equalityAssertionsOnView(
                expectedEvent,
                viewCapturedArguments[idx],
                idx,
                TransactionStatusDto.EXPIRED_NOT_AUTHORIZED
            )
        }

        for ((idx, expectedEvent) in expectedCapturedSentEvents.withIndex()) {
            equalityAssertionsOnSentEvent(expectedEvent, queueCapturedArguments[idx], idx)
        }
        val expectedEventsIntertime = batchExecutionTimeWindow / allEvents.size
        val eventsVisibilityTimeouts = eventsVisibilityTimeoutCaptor.allValues
        eventsVisibilityTimeouts.sort()
        var currentIdx = eventsVisibilityTimeoutCaptor.allValues.size - 1
        var visibilityTimeoutIntertime: Long
        while (currentIdx > 0) {
            visibilityTimeoutIntertime =
                eventsVisibilityTimeouts[currentIdx]
                    .minus(eventsVisibilityTimeouts[currentIdx - 1])
                    .toMillis()
            assertEquals(
                expectedEventsIntertime,
                visibilityTimeoutIntertime,
                "Testing event visibility timeout interleave. event[$currentIdx] - event[${currentIdx - 1}]"
            )
            currentIdx--
        }
        verify(eventStoreRepository, times(6)).save(any())
        verify(viewRepository, times(6)).save(any())
        verify(queueAsyncClient, times(6))
            .sendMessageWithResponse(
                any<QueueEvent<*>>(),
                any(),
                eq(Duration.ofSeconds(transientQueueTTLSeconds.toLong()))
            )
    }

    @Test
    fun `Should publish event for transactions with authorization requested`() {
        // preconditions
        val baseDocuments =
            generateTransactionBaseDocuments(
                howMany = 5,
                transactionType = TransactionType.AUTH_REQUESTED
            )

        val expectedGeneratedEvents =
            baseDocuments
                .map { baseTransactionToExpiryEvent(it) }
                .toList()
                .sortedBy { it.transactionId }
        val sendMessageResult = SendMessageResult()
        sendMessageResult.messageId = "msgId"
        sendMessageResult.timeNextVisible = OffsetDateTime.now()
        val queueAsyncClientResponse: Mono<Response<SendMessageResult>> =
            Mono.just(ResponseBase(null, 200, null, sendMessageResult, null))
        given(
                queueAsyncClient.sendMessageWithResponse(
                    queueArgumentCaptor.capture(),
                    eventsVisibilityTimeoutCaptor.capture(),
                    any()
                )
            )
            .willReturn(queueAsyncClientResponse)
        given(eventStoreRepository.save(eventStoreCaptor.capture())).willAnswer {
            Mono.just(it.arguments[0])
        }
        given(viewRepository.findByTransactionId(org.mockito.kotlin.any())).willAnswer {
            val transaction =
                TransactionTestUtils.transactionDocument(
                    TransactionStatusDto.ACTIVATED,
                    ZonedDateTime.now()
                )
            transaction.transactionId = it.arguments[0].toString()
            mono { transaction }
        }
        given(viewRepository.save(viewArgumentCaptor.capture())).willAnswer {
            Mono.just(it.arguments[0])
        }

        val batchExecutionTimeWindow = TimeUnit.HOURS.toMillis(1)
        // test
        StepVerifier.create(
                TransactionExpiredEventPublisher(
                        expiredEventQueueAsyncClient = queueAsyncClient,
                        viewRepository = viewRepository,
                        eventStoreRepository = eventStoreRepository,
                        parallelEventToProcess = 1,
                        transientQueueTTLSeconds = transientQueueTTLSeconds,
                        tracingUtils = tracingUtils
                    )
                    .publishExpiryEvents(
                        baseDocuments,
                        batchExecutionTimeWindow,
                        baseDocuments.size.toLong(),
                        0
                    )
            )
            .expectNext(true)
            .verifyComplete()
        // assertions

        val viewCapturedArguments = viewArgumentCaptor.allValues
        viewCapturedArguments.sortBy { it.transactionId }
        val eventCapturedArguments = eventStoreCaptor.allValues
        eventCapturedArguments.sortBy { it.transactionId }
        val queueCapturedArguments = queueArgumentCaptor.allValues
        queueCapturedArguments.sortBy { it.event.transactionId }
        for ((idx, expectedGeneratedEvent) in expectedGeneratedEvents.withIndex()) {
            /*
             * verify that event stored into event store collection and sent on the queue are the expected ones.
             * since some event fields such id and creation timestamp are created automatically in constructor
             * equality is verified field by field here
             */
            equalityAssertionsOnEventStore(expectedGeneratedEvent, eventCapturedArguments[idx], idx)
            equalityAssertionsOnView(
                expectedGeneratedEvent,
                viewCapturedArguments[idx],
                idx,
                TransactionStatusDto.EXPIRED
            )
            equalityAssertionsOnSentEvent(expectedGeneratedEvent, queueCapturedArguments[idx], idx)
        }

        val expectedEventsIntertime = batchExecutionTimeWindow / expectedGeneratedEvents.size
        val eventsVisibilityTimeouts = eventsVisibilityTimeoutCaptor.allValues
        var currentIdx = eventsVisibilityTimeoutCaptor.allValues.size - 1
        eventsVisibilityTimeouts.sort()
        var visibilityTimeoutIntertime: Long
        while (currentIdx > 0) {
            visibilityTimeoutIntertime =
                eventsVisibilityTimeouts[currentIdx]
                    .minus(eventsVisibilityTimeouts[currentIdx - 1])
                    .toMillis()
            assertEquals(
                expectedEventsIntertime,
                visibilityTimeoutIntertime,
                "Testing event visibility timeout interleave. event[$currentIdx] - event[$currentIdx-1]"
            )
            currentIdx--
        }
        verify(queueAsyncClient, times(5))
            .sendMessageWithResponse(
                any<QueueEvent<*>>(),
                any(),
                eq(Duration.ofSeconds(transientQueueTTLSeconds.toLong()))
            )
        verify(eventStoreRepository, times(5)).save(any())
        verify(viewRepository, times(5)).save(any())
    }

    @Test
    fun `Should publish event for all type of transactions`() {
        // preconditions
        val transactionOnlyActivatedDocuments =
            generateTransactionBaseDocuments(
                howMany = 5,
                transactionType = TransactionType.ACTIVATED_ONLY
            )
        val transactionWithRequestedAuthorizationDocuments =
            generateTransactionBaseDocuments(
                howMany = 5,
                transactionType = TransactionType.AUTH_REQUESTED
            )
        val transactionCanceledByUser =
            generateTransactionBaseDocuments(
                howMany = 5,
                transactionType = TransactionType.CANCELED_BY_USER
            )
        val transactionCanceledByUserWithClosureError =
            generateTransactionBaseDocuments(
                howMany = 5,
                transactionType = TransactionType.CANCELED_BY_USER_CLOSURE_ERROR
            )
        val transactionAuthorizedWithClosureError =
            generateTransactionBaseDocuments(
                howMany = 5,
                transactionType = TransactionType.CLOSURE_ERROR_WITH_AUTH_COMPLETED
            )
        val transactionAuthorizedWithClosureRequested =
            generateTransactionBaseDocuments(
                howMany = 5,
                transactionType = TransactionType.CLOSURE_REQUESTED
            )
        val baseDocuments =
            transactionOnlyActivatedDocuments
                .plus(transactionWithRequestedAuthorizationDocuments)
                .plus(transactionCanceledByUser)
                .plus(transactionCanceledByUserWithClosureError)
                .plus(transactionAuthorizedWithClosureError)
                .plus(transactionAuthorizedWithClosureRequested)
        val expectedTransactionStatusMap =
            transactionOnlyActivatedDocuments
                .groupBy(
                    { it.transactionId.value() },
                    { TransactionStatusDto.EXPIRED_NOT_AUTHORIZED }
                )
                .plus(
                    transactionWithRequestedAuthorizationDocuments.groupBy(
                        { it.transactionId.value() },
                        { TransactionStatusDto.EXPIRED }
                    )
                )
                .plus(
                    transactionCanceledByUser.groupBy(
                        { it.transactionId.value() },
                        { TransactionStatusDto.CANCELLATION_EXPIRED }
                    )
                )
                .plus(
                    transactionCanceledByUserWithClosureError.groupBy(
                        { it.transactionId.value() },
                        { TransactionStatusDto.CANCELLATION_EXPIRED }
                    )
                )
                .plus(
                    transactionAuthorizedWithClosureError.groupBy(
                        { it.transactionId.value() },
                        { TransactionStatusDto.EXPIRED }
                    )
                )
                .plus(
                    transactionAuthorizedWithClosureRequested.groupBy(
                        { it.transactionId.value() },
                        { TransactionStatusDto.EXPIRED }
                    )
                )

        val expectedGeneratedEvents =
            baseDocuments
                .map { baseTransactionToExpiryEvent(it) }
                .toList()
                .sortedBy { it.transactionId }
        val sendMessageResult = SendMessageResult()
        sendMessageResult.messageId = "msgId"
        sendMessageResult.timeNextVisible = OffsetDateTime.now()
        val queueAsyncClientResponse: Mono<Response<SendMessageResult>> =
            Mono.just(ResponseBase(null, 200, null, sendMessageResult, null))
        given(
                queueAsyncClient.sendMessageWithResponse(
                    queueArgumentCaptor.capture(),
                    eventsVisibilityTimeoutCaptor.capture(),
                    any()
                )
            )
            .willReturn(queueAsyncClientResponse)
        given(eventStoreRepository.save(eventStoreCaptor.capture())).willAnswer {
            Mono.just(it.arguments[0])
        }
        given(viewRepository.findByTransactionId(org.mockito.kotlin.any())).willAnswer {
            val transaction =
                TransactionTestUtils.transactionDocument(
                    TransactionStatusDto.ACTIVATED,
                    ZonedDateTime.now()
                )
            transaction.transactionId = it.arguments[0].toString()
            mono { transaction }
        }
        given(viewRepository.save(viewArgumentCaptor.capture())).willAnswer {
            Mono.just(it.arguments[0])
        }

        val batchExecutionTimeWindow = TimeUnit.HOURS.toMillis(1)
        // test
        StepVerifier.create(
                TransactionExpiredEventPublisher(
                        expiredEventQueueAsyncClient = queueAsyncClient,
                        viewRepository = viewRepository,
                        eventStoreRepository = eventStoreRepository,
                        parallelEventToProcess = 1,
                        transientQueueTTLSeconds = transientQueueTTLSeconds,
                        tracingUtils = tracingUtils
                    )
                    .publishExpiryEvents(
                        baseDocuments,
                        batchExecutionTimeWindow,
                        baseDocuments.size.toLong(),
                        0
                    )
            )
            .expectNext(true)
            .verifyComplete()
        // assertions

        val viewCapturedArguments = viewArgumentCaptor.allValues
        viewCapturedArguments.sortBy { it.transactionId }
        val eventCapturedArguments = eventStoreCaptor.allValues
        eventCapturedArguments.sortBy { it.transactionId }
        val queueCapturedArguments = queueArgumentCaptor.allValues
        queueCapturedArguments.sortBy { it.event.transactionId }
        for ((idx, expectedGeneratedEvent) in expectedGeneratedEvents.withIndex()) {
            /*
             * verify that event stored into event store collection and sent on the queue are the expected ones.
             * since some event fields such id and creation timestamp are created automatically in constructor
             * equality is verified field by field here
             */
            equalityAssertionsOnEventStore(expectedGeneratedEvent, eventCapturedArguments[idx], idx)
            equalityAssertionsOnView(
                expectedGeneratedEvent,
                viewCapturedArguments[idx],
                idx,
                expectedTransactionStatusMap[expectedGeneratedEvent.transactionId]!![0]
            )
            equalityAssertionsOnSentEvent(expectedGeneratedEvent, queueCapturedArguments[idx], idx)
        }

        val expectedEventsIntertime = batchExecutionTimeWindow / expectedGeneratedEvents.size
        val eventsVisibilityTimeouts = eventsVisibilityTimeoutCaptor.allValues
        var currentIdx = eventsVisibilityTimeoutCaptor.allValues.size - 1
        eventsVisibilityTimeouts.sort()
        var visibilityTimeoutIntertime: Long
        while (currentIdx > 0) {
            visibilityTimeoutIntertime =
                eventsVisibilityTimeouts[currentIdx]
                    .minus(eventsVisibilityTimeouts[currentIdx - 1])
                    .toMillis()
            assertEquals(
                expectedEventsIntertime,
                visibilityTimeoutIntertime,
                "Testing event visibility timeout interleave. event[$currentIdx] - event[$currentIdx-1]"
            )
            currentIdx--
        }
        verify(queueAsyncClient, times(30))
            .sendMessageWithResponse(
                any<QueueEvent<*>>(),
                any(),
                eq(Duration.ofSeconds(transientQueueTTLSeconds.toLong()))
            )
        verify(eventStoreRepository, times(30)).save(any())
        verify(viewRepository, times(30)).save(any())
    }

    @Test
    fun `Should publish event for transactions with cancellation requested updating transaction to CANCELLATION_EXPIRED`() {
        // preconditions
        val baseDocuments =
            generateTransactionBaseDocuments(
                howMany = 5,
                transactionType = TransactionType.CANCELED_BY_USER
            )

        val expectedGeneratedEvents =
            baseDocuments
                .map { baseTransactionToExpiryEvent(it) }
                .toList()
                .sortedBy { it.transactionId }
        val sendMessageResult = SendMessageResult()
        sendMessageResult.messageId = "msgId"
        sendMessageResult.timeNextVisible = OffsetDateTime.now()
        val queueAsyncClientResponse: Mono<Response<SendMessageResult>> =
            Mono.just(ResponseBase(null, 200, null, sendMessageResult, null))
        given(
                queueAsyncClient.sendMessageWithResponse(
                    queueArgumentCaptor.capture(),
                    eventsVisibilityTimeoutCaptor.capture(),
                    any()
                )
            )
            .willReturn(queueAsyncClientResponse)
        given(eventStoreRepository.save(eventStoreCaptor.capture())).willAnswer {
            Mono.just(it.arguments[0])
        }
        given(viewRepository.findByTransactionId(org.mockito.kotlin.any())).willAnswer {
            val transaction =
                TransactionTestUtils.transactionDocument(
                    TransactionStatusDto.ACTIVATED,
                    ZonedDateTime.now()
                )
            transaction.transactionId = it.arguments[0].toString()
            mono { transaction }
        }
        given(viewRepository.save(viewArgumentCaptor.capture())).willAnswer {
            Mono.just(it.arguments[0])
        }

        val batchExecutionTimeWindow = TimeUnit.HOURS.toMillis(1)
        // test
        StepVerifier.create(
                TransactionExpiredEventPublisher(
                        expiredEventQueueAsyncClient = queueAsyncClient,
                        viewRepository = viewRepository,
                        eventStoreRepository = eventStoreRepository,
                        parallelEventToProcess = 1,
                        transientQueueTTLSeconds = transientQueueTTLSeconds,
                        tracingUtils = tracingUtils
                    )
                    .publishExpiryEvents(
                        baseDocuments,
                        batchExecutionTimeWindow,
                        baseDocuments.size.toLong(),
                        0
                    )
            )
            .expectNext(true)
            .verifyComplete()
        // assertions

        val viewCapturedArguments = viewArgumentCaptor.allValues
        viewCapturedArguments.sortBy { it.transactionId }
        val eventCapturedArguments = eventStoreCaptor.allValues
        eventCapturedArguments.sortBy { it.transactionId }
        val queueCapturedArguments = queueArgumentCaptor.allValues
        queueCapturedArguments.sortBy { it.event.transactionId }
        for ((idx, expectedGeneratedEvent) in expectedGeneratedEvents.withIndex()) {
            /*
             * verify that event stored into event store collection and sent on the queue are the expected ones.
             * since some event fields such id and creation timestamp are created automatically in constructor
             * equality is verified field by field here
             */
            equalityAssertionsOnEventStore(expectedGeneratedEvent, eventCapturedArguments[idx], idx)
            equalityAssertionsOnView(
                expectedGeneratedEvent,
                viewCapturedArguments[idx],
                idx,
                TransactionStatusDto.CANCELLATION_EXPIRED
            )
            equalityAssertionsOnSentEvent(expectedGeneratedEvent, queueCapturedArguments[idx], idx)
        }

        val expectedEventsIntertime = batchExecutionTimeWindow / expectedGeneratedEvents.size
        val eventsVisibilityTimeouts = eventsVisibilityTimeoutCaptor.allValues
        var currentIdx = eventsVisibilityTimeoutCaptor.allValues.size - 1
        eventsVisibilityTimeouts.sort()
        var visibilityTimeoutIntertime: Long
        while (currentIdx > 0) {
            visibilityTimeoutIntertime =
                eventsVisibilityTimeouts[currentIdx]
                    .minus(eventsVisibilityTimeouts[currentIdx - 1])
                    .toMillis()
            assertEquals(
                expectedEventsIntertime,
                visibilityTimeoutIntertime,
                "Testing event visibility timeout interleave. event[$currentIdx] - event[$currentIdx-1]"
            )
            currentIdx--
        }
        verify(queueAsyncClient, times(5))
            .sendMessageWithResponse(
                any<QueueEvent<*>>(),
                any(),
                eq(Duration.ofSeconds(transientQueueTTLSeconds.toLong()))
            )
        verify(eventStoreRepository, times(5)).save(any())
        verify(viewRepository, times(5)).save(any())
    }

    @Test
    fun `Should publish event for transactions with cancellation requested in CLOSURE_ERROR state updating status to CANCELLATION_EXPIRED`() {
        // preconditions
        val baseDocuments =
            generateTransactionBaseDocuments(
                howMany = 5,
                transactionType = TransactionType.CANCELED_BY_USER_CLOSURE_ERROR
            )

        val expectedGeneratedEvents =
            baseDocuments
                .map { baseTransactionToExpiryEvent(it) }
                .toList()
                .sortedBy { it.transactionId }
        val sendMessageResult = SendMessageResult()
        sendMessageResult.messageId = "msgId"
        sendMessageResult.timeNextVisible = OffsetDateTime.now()
        val queueAsyncClientResponse: Mono<Response<SendMessageResult>> =
            Mono.just(ResponseBase(null, 200, null, sendMessageResult, null))
        given(
                queueAsyncClient.sendMessageWithResponse(
                    queueArgumentCaptor.capture(),
                    eventsVisibilityTimeoutCaptor.capture(),
                    any()
                )
            )
            .willReturn(queueAsyncClientResponse)
        given(eventStoreRepository.save(eventStoreCaptor.capture())).willAnswer {
            Mono.just(it.arguments[0])
        }
        given(viewRepository.findByTransactionId(org.mockito.kotlin.any())).willAnswer {
            val transaction =
                TransactionTestUtils.transactionDocument(
                    TransactionStatusDto.ACTIVATED,
                    ZonedDateTime.now()
                )
            transaction.transactionId = it.arguments[0].toString()
            mono { transaction }
        }
        given(viewRepository.save(viewArgumentCaptor.capture())).willAnswer {
            Mono.just(it.arguments[0])
        }

        val batchExecutionTimeWindow = TimeUnit.HOURS.toMillis(1)
        // test
        StepVerifier.create(
                TransactionExpiredEventPublisher(
                        expiredEventQueueAsyncClient = queueAsyncClient,
                        viewRepository = viewRepository,
                        eventStoreRepository = eventStoreRepository,
                        parallelEventToProcess = 1,
                        transientQueueTTLSeconds = transientQueueTTLSeconds,
                        tracingUtils = tracingUtils
                    )
                    .publishExpiryEvents(
                        baseDocuments,
                        batchExecutionTimeWindow,
                        baseDocuments.size.toLong(),
                        0
                    )
            )
            .expectNext(true)
            .verifyComplete()
        // assertions

        val viewCapturedArguments = viewArgumentCaptor.allValues
        viewCapturedArguments.sortBy { it.transactionId }
        val eventCapturedArguments = eventStoreCaptor.allValues
        eventCapturedArguments.sortBy { it.transactionId }
        val queueCapturedArguments = queueArgumentCaptor.allValues
        queueCapturedArguments.sortBy { it.event.transactionId }
        for ((idx, expectedGeneratedEvent) in expectedGeneratedEvents.withIndex()) {
            /*
             * verify that event stored into event store collection and sent on the queue are the expected ones.
             * since some event fields such id and creation timestamp are created automatically in constructor
             * equality is verified field by field here
             */
            equalityAssertionsOnEventStore(expectedGeneratedEvent, eventCapturedArguments[idx], idx)
            equalityAssertionsOnView(
                expectedGeneratedEvent,
                viewCapturedArguments[idx],
                idx,
                TransactionStatusDto.CANCELLATION_EXPIRED
            )
            equalityAssertionsOnSentEvent(expectedGeneratedEvent, queueCapturedArguments[idx], idx)
        }

        val expectedEventsIntertime = batchExecutionTimeWindow / expectedGeneratedEvents.size
        val eventsVisibilityTimeouts = eventsVisibilityTimeoutCaptor.allValues
        var currentIdx = eventsVisibilityTimeoutCaptor.allValues.size - 1
        eventsVisibilityTimeouts.sort()
        var visibilityTimeoutIntertime: Long
        while (currentIdx > 0) {
            visibilityTimeoutIntertime =
                eventsVisibilityTimeouts[currentIdx]
                    .minus(eventsVisibilityTimeouts[currentIdx - 1])
                    .toMillis()
            assertEquals(
                expectedEventsIntertime,
                visibilityTimeoutIntertime,
                "Testing event visibility timeout interleave. event[$currentIdx] - event[$currentIdx-1]"
            )
            currentIdx--
        }
        verify(queueAsyncClient, times(5))
            .sendMessageWithResponse(
                any<QueueEvent<*>>(),
                any(),
                eq(Duration.ofSeconds(transientQueueTTLSeconds.toLong()))
            )
        verify(eventStoreRepository, times(5)).save(any())
        verify(viewRepository, times(5)).save(any())
    }

    @Test
    fun `Should publish event for transactions with completed authorization in CLOSURE_ERROR state updating status to EXPIRED`() {
        // preconditions
        val baseDocuments =
            generateTransactionBaseDocuments(
                howMany = 5,
                transactionType = TransactionType.CLOSURE_ERROR_WITH_AUTH_COMPLETED
            )

        val expectedGeneratedEvents =
            baseDocuments
                .map { baseTransactionToExpiryEvent(it) }
                .toList()
                .sortedBy { it.transactionId }
        val sendMessageResult = SendMessageResult()
        sendMessageResult.messageId = "msgId"
        sendMessageResult.timeNextVisible = OffsetDateTime.now()
        val queueAsyncClientResponse: Mono<Response<SendMessageResult>> =
            Mono.just(ResponseBase(null, 200, null, sendMessageResult, null))
        given(
                queueAsyncClient.sendMessageWithResponse(
                    queueArgumentCaptor.capture(),
                    eventsVisibilityTimeoutCaptor.capture(),
                    any()
                )
            )
            .willReturn(queueAsyncClientResponse)
        given(eventStoreRepository.save(eventStoreCaptor.capture())).willAnswer {
            Mono.just(it.arguments[0])
        }
        given(viewRepository.findByTransactionId(org.mockito.kotlin.any())).willAnswer {
            val transaction =
                TransactionTestUtils.transactionDocument(
                    TransactionStatusDto.ACTIVATED,
                    ZonedDateTime.now()
                )
            transaction.transactionId = it.arguments[0].toString()
            mono { transaction }
        }
        given(viewRepository.save(viewArgumentCaptor.capture())).willAnswer {
            Mono.just(it.arguments[0])
        }

        val batchExecutionTimeWindow = TimeUnit.HOURS.toMillis(1)
        // test
        StepVerifier.create(
                TransactionExpiredEventPublisher(
                        expiredEventQueueAsyncClient = queueAsyncClient,
                        viewRepository = viewRepository,
                        eventStoreRepository = eventStoreRepository,
                        parallelEventToProcess = 1,
                        transientQueueTTLSeconds = transientQueueTTLSeconds,
                        tracingUtils = tracingUtils
                    )
                    .publishExpiryEvents(
                        baseDocuments,
                        batchExecutionTimeWindow,
                        baseDocuments.size.toLong(),
                        0
                    )
            )
            .expectNext(true)
            .verifyComplete()
        // assertions

        val viewCapturedArguments = viewArgumentCaptor.allValues
        viewCapturedArguments.sortBy { it.transactionId }
        val eventCapturedArguments = eventStoreCaptor.allValues
        eventCapturedArguments.sortBy { it.transactionId }
        val queueCapturedArguments = queueArgumentCaptor.allValues
        queueCapturedArguments.sortBy { it.event.transactionId }
        for ((idx, expectedGeneratedEvent) in expectedGeneratedEvents.withIndex()) {
            /*
             * verify that event stored into event store collection and sent on the queue are the expected ones.
             * since some event fields such id and creation timestamp are created automatically in constructor
             * equality is verified field by field here
             */
            equalityAssertionsOnEventStore(expectedGeneratedEvent, eventCapturedArguments[idx], idx)
            equalityAssertionsOnView(
                expectedGeneratedEvent,
                viewCapturedArguments[idx],
                idx,
                TransactionStatusDto.EXPIRED
            )
            equalityAssertionsOnSentEvent(expectedGeneratedEvent, queueCapturedArguments[idx], idx)
        }

        val expectedEventsIntertime = batchExecutionTimeWindow / expectedGeneratedEvents.size
        val eventsVisibilityTimeouts = eventsVisibilityTimeoutCaptor.allValues
        var currentIdx = eventsVisibilityTimeoutCaptor.allValues.size - 1
        eventsVisibilityTimeouts.sort()
        var visibilityTimeoutIntertime: Long
        while (currentIdx > 0) {
            visibilityTimeoutIntertime =
                eventsVisibilityTimeouts[currentIdx]
                    .minus(eventsVisibilityTimeouts[currentIdx - 1])
                    .toMillis()
            assertEquals(
                expectedEventsIntertime,
                visibilityTimeoutIntertime,
                "Testing event visibility timeout interleave. event[$currentIdx] - event[$currentIdx-1]"
            )
            currentIdx--
        }
        verify(queueAsyncClient, times(5))
            .sendMessageWithResponse(
                any<QueueEvent<*>>(),
                any(),
                eq(Duration.ofSeconds(transientQueueTTLSeconds.toLong()))
            )
        verify(eventStoreRepository, times(5)).save(any())
        verify(viewRepository, times(5)).save(any())
    }

    private fun baseTransactionToExpiryEvent(transaction: BaseTransaction) =
        TransactionExpiredEvent(
            transaction.transactionId.value(),
            TransactionExpiredData(transaction.status)
        )

    private fun equalityAssertionsOnEventStore(
        expectedGeneratedEvent: TransactionExpiredEvent,
        capturedValue: TransactionExpiredEvent,
        idx: Int
    ) {
        val equalityMessage = "Event[$idx]"
        assertEquals(
            expectedGeneratedEvent.transactionId,
            capturedValue.transactionId,
            equalityMessage
        )
        assertEquals(expectedGeneratedEvent.eventCode, capturedValue.eventCode, equalityMessage)
        assertEquals(
            expectedGeneratedEvent.data.statusBeforeExpiration,
            capturedValue.data.statusBeforeExpiration,
            equalityMessage
        )
    }

    private fun equalityAssertionsOnView(
        expectedGeneratedEvent: TransactionExpiredEvent,
        capturedValue: Transaction,
        idx: Int,
        expectedViewStatus: TransactionStatusDto
    ) {
        val equalityMessage = "Event[$idx]"
        assertEquals(
            expectedGeneratedEvent.transactionId,
            capturedValue.transactionId,
            equalityMessage
        )
        assertEquals(expectedViewStatus, capturedValue.status, equalityMessage)
    }

    private fun equalityAssertionsOnSentEvent(
        expectedGeneratedEvent: TransactionExpiredEvent,
        actualEvent: QueueEvent<*>,
        idx: Int
    ) {
        val equalityMessage = "Event[$idx]"
        val eventSent = actualEvent.event as TransactionExpiredEvent

        // assertions on events sent on queue
        assertEquals(expectedGeneratedEvent.transactionId, eventSent.transactionId, equalityMessage)
        assertEquals(expectedGeneratedEvent.eventCode, eventSent.eventCode, equalityMessage)
        assertEquals(
            expectedGeneratedEvent.data.statusBeforeExpiration,
            eventSent.data.statusBeforeExpiration,
            equalityMessage
        )
    }

    enum class TransactionType {
        ACTIVATED_ONLY,
        AUTH_REQUESTED,
        CANCELED_BY_USER,
        CANCELED_BY_USER_CLOSURE_ERROR,
        CLOSURE_ERROR_WITH_AUTH_COMPLETED,
        CLOSURE_REQUESTED
    }

    private fun generateTransactionBaseDocuments(
        howMany: Int,
        transactionId: UUID? = null,
        transactionType: TransactionType
    ): List<BaseTransaction> {
        val baseDocuments = ArrayList<BaseTransaction>()
        val transactionActivated =
            TransactionTestUtils.transactionActivated(ZonedDateTime.now().toString())
        val transactionGatewayActivationData =
            repeat(howMany) {
                val transactionActivatedWithCustomUUID =
                    TransactionActivated(
                        TransactionId(transactionId ?: UUID.randomUUID()),
                        transactionActivated.paymentNotices,
                        transactionActivated.email,
                        transactionActivated.transactionActivatedData.faultCode,
                        transactionActivated.transactionActivatedData.faultCodeString,
                        transactionActivated.creationDate,
                        transactionActivated.clientId,
                        transactionActivated.transactionActivatedData.idCart,
                        transactionActivated.transactionActivatedData.paymentTokenValiditySeconds,
                        transactionActivated.transactionActivatedData
                            .transactionGatewayActivationData,
                        null
                    )
                baseDocuments.add(
                    when (transactionType) {
                        TransactionType.ACTIVATED_ONLY -> transactionActivatedWithCustomUUID
                        TransactionType.CANCELED_BY_USER ->
                            TransactionTestUtils.transactionWithCancellationRequested(
                                transactionActivatedWithCustomUUID,
                                TransactionTestUtils.transactionUserCanceledEvent()
                            )
                        TransactionType.CANCELED_BY_USER_CLOSURE_ERROR ->
                            TransactionTestUtils.transactionWithClosureError(
                                TransactionTestUtils.transactionClosureErrorEvent(),
                                TransactionTestUtils.transactionWithCancellationRequested(
                                    transactionActivatedWithCustomUUID,
                                    TransactionTestUtils.transactionUserCanceledEvent()
                                )
                            )
                        TransactionType.AUTH_REQUESTED ->
                            TransactionTestUtils.transactionWithRequestedAuthorization(
                                TransactionTestUtils.transactionAuthorizationRequestedEvent(),
                                transactionActivatedWithCustomUUID
                            )
                        TransactionType.CLOSURE_ERROR_WITH_AUTH_COMPLETED ->
                            TransactionTestUtils.transactionWithClosureError(
                                TransactionTestUtils.transactionClosureErrorEvent(),
                                TransactionTestUtils.transactionWithClosureRequested(
                                    TransactionTestUtils.transactionAuthorizationCompleted(
                                        TransactionTestUtils.transactionAuthorizationCompletedEvent(
                                            TransactionTestUtils
                                                .npgTransactionGatewayAuthorizationData(
                                                    OperationResultDto.AUTHORIZED
                                                )
                                        ),
                                        TransactionTestUtils.transactionWithRequestedAuthorization(
                                            TransactionTestUtils
                                                .transactionAuthorizationRequestedEvent(),
                                            transactionActivatedWithCustomUUID
                                        )
                                    )
                                )
                            )
                        TransactionType.CLOSURE_REQUESTED ->
                            TransactionTestUtils.transactionWithClosureRequested(
                                TransactionTestUtils.transactionAuthorizationCompleted(
                                    TransactionTestUtils.transactionAuthorizationCompletedEvent(
                                        TransactionTestUtils.npgTransactionGatewayAuthorizationData(
                                            OperationResultDto.AUTHORIZED
                                        )
                                    ),
                                    TransactionTestUtils.transactionWithRequestedAuthorization(
                                        TransactionTestUtils
                                            .transactionAuthorizationRequestedEvent(),
                                        transactionActivatedWithCustomUUID
                                    )
                                )
                            )
                    }
                )
            }
        return baseDocuments
    }
}
