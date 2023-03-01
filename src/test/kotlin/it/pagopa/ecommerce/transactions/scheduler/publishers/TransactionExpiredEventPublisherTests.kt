package it.pagopa.ecommerce.transactions.scheduler.publishers

import com.azure.core.http.rest.Response
import com.azure.core.http.rest.ResponseBase
import com.azure.core.util.BinaryData
import com.azure.storage.queue.QueueAsyncClient
import com.azure.storage.queue.models.QueueStorageException
import com.azure.storage.queue.models.SendMessageResult
import com.mongodb.MongoException
import it.pagopa.ecommerce.commons.documents.v1.Transaction
import it.pagopa.ecommerce.commons.documents.v1.TransactionExpiredData
import it.pagopa.ecommerce.commons.documents.v1.TransactionExpiredEvent
import it.pagopa.ecommerce.commons.domain.v1.*
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils
import it.pagopa.ecommerce.transactions.scheduler.repositories.TransactionsEventStoreRepository
import it.pagopa.ecommerce.transactions.scheduler.repositories.TransactionsViewRepository
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
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

    @Captor private lateinit var queueArgumentCaptor: ArgumentCaptor<BinaryData>

    @Captor private lateinit var viewArgumentCaptor: ArgumentCaptor<Transaction>

    @Captor private lateinit var eventStoreCaptor: ArgumentCaptor<TransactionExpiredEvent>

    @Captor private lateinit var eventsVisibilityTimeoutCaptor: ArgumentCaptor<Duration>

    @Test
    fun `Should publish all events`() {
        // preconditions
        val baseDocuments = generateTransactionActivatedBaseDocuments(5)

        val expectedGeneratedEvents =
            baseDocuments.map { baseTransactionToExpiryEvent(it) }.toList()
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
        given(viewRepository.save(viewArgumentCaptor.capture())).willAnswer {
            Mono.just(it.arguments[0])
        }

        val batchExecutionTimeWindow = TimeUnit.HOURS.toMillis(1)
        // test
        StepVerifier.create(
                TransactionExpiredEventPublisher(
                        logger = Logger.getGlobal(),
                        expiredEventQueueAsyncClient = queueAsyncClient,
                        viewRepository = viewRepository,
                        eventStoreRepository = eventStoreRepository,
                        1
                    )
                    .publishExpiryEvents(baseDocuments, batchExecutionTimeWindow)
            )
            .expectNext(true)
            .verifyComplete()
        // assertions
        for ((idx, expectedGeneratedEvent) in expectedGeneratedEvents.withIndex()) {
            /*
             * verify that event stored into event store collection and sent on the queue are the expected ones.
             * since some event fields such id and creation timestamp are created automatically in constructor
             * equality is verified field by field here
             */
            equalityAssertionsOnEventStore(
                expectedGeneratedEvent,
                eventStoreCaptor.allValues[idx],
                idx
            )
            equalityAssertionsOnView(expectedGeneratedEvent, viewArgumentCaptor.allValues[idx], idx)
            equalityAssertionsOnSentEvent(
                expectedGeneratedEvent,
                queueArgumentCaptor.allValues[idx],
                idx
            )
        }

        val expectedEventsIntertime = batchExecutionTimeWindow / expectedGeneratedEvents.size
        val eventsVisibilityTimeouts = eventsVisibilityTimeoutCaptor.allValues
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
                "Testing event visibility timeout interleave. event[$currentIdx] - event[$currentIdx-1]"
            )
            currentIdx--
        }
        verify(queueAsyncClient, times(5)).sendMessageWithResponse(any<BinaryData>(), any(), any())
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
        given(viewRepository.save(viewArgumentCaptor.capture())).willReturn {
            Mono.error(MongoException("Error updating view"))
        }

        val batchExecutionTimeWindow = TimeUnit.HOURS.toMillis(1)
        // test

        StepVerifier.create(
                TransactionExpiredEventPublisher(
                        logger = Logger.getGlobal(),
                        expiredEventQueueAsyncClient = queueAsyncClient,
                        viewRepository = viewRepository,
                        eventStoreRepository = eventStoreRepository,
                        1
                    )
                    .publishExpiryEvents(
                        generateTransactionActivatedBaseDocuments(5),
                        batchExecutionTimeWindow
                    )
            )
            .expectNext(false)
            .verifyComplete()
        // assertions
        verify(queueAsyncClient, times(0)).sendMessageWithResponse(any<BinaryData>(), any(), any())
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
                        logger = Logger.getGlobal(),
                        expiredEventQueueAsyncClient = queueAsyncClient,
                        viewRepository = viewRepository,
                        eventStoreRepository = eventStoreRepository,
                        1
                    )
                    .publishExpiryEvents(
                        generateTransactionActivatedBaseDocuments(5),
                        batchExecutionTimeWindow
                    )
            )
            .expectNext(false)
            .verifyComplete()
        // assertions
        verify(queueAsyncClient, times(0)).sendMessageWithResponse(any<BinaryData>(), any(), any())
        verify(viewRepository, times(0)).save(any())
        verify(eventStoreRepository, times(5)).save(any())
    }

    @Test
    fun `Should fails all for error sending event to queue`() {
        // preconditions

        val sendMessageResult = SendMessageResult()
        sendMessageResult.messageId = "msgId"
        sendMessageResult.timeNextVisible = OffsetDateTime.now()
        given(queueAsyncClient.sendMessageWithResponse(any<BinaryData>(), any(), any()))
            .willReturn(
                Mono.error(QueueStorageException("Error sending message to queue", null, null))
            )
        given(eventStoreRepository.save(eventStoreCaptor.capture())).willAnswer {
            Mono.just(it.arguments[0])
        }
        given(viewRepository.save(viewArgumentCaptor.capture())).willAnswer {
            Mono.just(it.arguments[0])
        }
        val batchExecutionTimeWindow = TimeUnit.HOURS.toMillis(1)
        // test
        StepVerifier.create(
                TransactionExpiredEventPublisher(
                        logger = Logger.getGlobal(),
                        expiredEventQueueAsyncClient = queueAsyncClient,
                        viewRepository = viewRepository,
                        eventStoreRepository = eventStoreRepository,
                        1
                    )
                    .publishExpiryEvents(
                        generateTransactionActivatedBaseDocuments(5),
                        batchExecutionTimeWindow
                    )
            )
            .expectNext(false)
            .verifyComplete()
        // assertions
        verify(queueAsyncClient, times(5)).sendMessageWithResponse(any<BinaryData>(), any(), any())
        verify(viewRepository, times(5)).save(any())
        verify(eventStoreRepository, times(5)).save(any())
    }

    @Test
    fun `Should continue processing transactions for error saving event to event store`() {
        // preconditions
        val errorTransactionId = UUID.randomUUID().toString()
        val okTransactions = generateTransactionActivatedBaseDocuments(5)
        val koTransactions =
            generateTransactionActivatedBaseDocuments(1, UUID.fromString(errorTransactionId))
        val allTransactions = koTransactions.plus(okTransactions)

        val okEvents = okTransactions.map { baseTransactionToExpiryEvent(it) }
        val koEvents = koTransactions.map { baseTransactionToExpiryEvent(it) }
        val expectedCapturedSavedEvents = koEvents.plus(okEvents)
        val expectedCapturedSavedViews: List<TransactionExpiredEvent> = okEvents
        val expectedCapturedSentEvents: List<TransactionExpiredEvent> = okEvents
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
        given(eventStoreRepository.save(eventStoreCaptor.capture()))
            .willReturnConsecutively(
                allEvents.map { event ->
                    if (event.transactionId != errorTransactionId) {
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
                        logger = Logger.getGlobal(),
                        expiredEventQueueAsyncClient = queueAsyncClient,
                        viewRepository = viewRepository,
                        eventStoreRepository = eventStoreRepository,
                        1
                    )
                    .publishExpiryEvents(allTransactions, batchExecutionTimeWindow)
            )
            .expectNext(false)
            .verifyComplete()
        // assertions
        for ((idx, expectedEvent) in expectedCapturedSavedEvents.withIndex()) {
            equalityAssertionsOnEventStore(expectedEvent, eventStoreCaptor.allValues[idx], idx)
        }

        for ((idx, expectedEvent) in expectedCapturedSavedViews.withIndex()) {
            equalityAssertionsOnView(expectedEvent, viewArgumentCaptor.allValues[idx], idx)
        }

        for ((idx, expectedEvent) in expectedCapturedSentEvents.withIndex()) {
            equalityAssertionsOnSentEvent(expectedEvent, queueArgumentCaptor.allValues[idx], idx)
        }

        val expectedEventsIntertime = batchExecutionTimeWindow / allEvents.size
        val eventsVisibilityTimeouts = eventsVisibilityTimeoutCaptor.allValues
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
        verify(viewRepository, times(5)).save(any())
        verify(queueAsyncClient, times(5)).sendMessageWithResponse(any<BinaryData>(), any(), any())
    }

    @Test
    fun `Should continue processing transactions for error saving view`() {
        // preconditions
        val errorTransactionId = UUID.randomUUID().toString()
        val okTransactions = generateTransactionActivatedBaseDocuments(5)
        val koTransactions =
            generateTransactionActivatedBaseDocuments(1, UUID.fromString(errorTransactionId))
        val allTransactions = koTransactions.plus(okTransactions)

        val okEvents = okTransactions.map { baseTransactionToExpiryEvent(it) }
        val koEvents = koTransactions.map { baseTransactionToExpiryEvent(it) }
        val expectedCapturedSavedEvents = koEvents.plus(okEvents)
        val expectedCapturedSavedViews = koEvents.plus(okEvents)
        val expectedCapturedSentEvents: List<TransactionExpiredEvent> = okEvents
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
        given(viewRepository.save(viewArgumentCaptor.capture())).willAnswer {
            if ((it.arguments[0] as Transaction).transactionId == errorTransactionId) {
                Mono.error(MongoException("Error saving view"))
            } else {
                Mono.just(it.arguments[0])
            }
        }

        val batchExecutionTimeWindow = TimeUnit.HOURS.toMillis(1)
        // test
        StepVerifier.create(
                TransactionExpiredEventPublisher(
                        logger = Logger.getGlobal(),
                        expiredEventQueueAsyncClient = queueAsyncClient,
                        viewRepository = viewRepository,
                        eventStoreRepository = eventStoreRepository,
                        1
                    )
                    .publishExpiryEvents(allTransactions, batchExecutionTimeWindow)
            )
            .expectNext(false)
            .verifyComplete()
        // assertions

        for ((idx, expectedEvent) in expectedCapturedSavedEvents.withIndex()) {
            equalityAssertionsOnEventStore(expectedEvent, eventStoreCaptor.allValues[idx], idx)
        }

        for ((idx, expectedEvent) in expectedCapturedSavedViews.withIndex()) {
            equalityAssertionsOnView(expectedEvent, viewArgumentCaptor.allValues[idx], idx)
        }

        for ((idx, expectedEvent) in expectedCapturedSentEvents.withIndex()) {
            equalityAssertionsOnSentEvent(expectedEvent, queueArgumentCaptor.allValues[idx], idx)
        }
        val expectedEventsIntertime = batchExecutionTimeWindow / allEvents.size
        val eventsVisibilityTimeouts = eventsVisibilityTimeoutCaptor.allValues
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
        verify(queueAsyncClient, times(5)).sendMessageWithResponse(any<BinaryData>(), any(), any())
    }

    @Test
    fun `Should continue processing transactions for error sending event to queue`() {
        // preconditions
        val errorTransactionId = UUID.randomUUID().toString()
        val okTransactions = generateTransactionActivatedBaseDocuments(5)
        val koTransactions =
            generateTransactionActivatedBaseDocuments(1, UUID.fromString(errorTransactionId))
        val allTransactions = koTransactions.plus(okTransactions)

        val okEvents = okTransactions.map { baseTransactionToExpiryEvent(it) }
        val koEvents = koTransactions.map { baseTransactionToExpiryEvent(it) }

        val expectedCapturedSavedEvents = koEvents.plus(okEvents)
        val expectedCapturedSavedViews = koEvents.plus(okEvents)
        val expectedCapturedSentEvents = koEvents.plus(okEvents)
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
        given(
                queueAsyncClient.sendMessageWithResponse(
                    queueArgumentCaptor.capture(),
                    eventsVisibilityTimeoutCaptor.capture(),
                    any()
                )
            )
            .willAnswer {
                if (
                    BinaryData.fromBytes((it.arguments[0] as BinaryData).toBytes())
                        .toObject(TransactionExpiredEvent::class.java)
                        .transactionId != errorTransactionId
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
                        logger = Logger.getGlobal(),
                        expiredEventQueueAsyncClient = queueAsyncClient,
                        viewRepository = viewRepository,
                        eventStoreRepository = eventStoreRepository,
                        1
                    )
                    .publishExpiryEvents(allTransactions, batchExecutionTimeWindow)
            )
            .expectNext(false)
            .verifyComplete()
        // assertions
        for ((idx, expectedEvent) in expectedCapturedSavedEvents.withIndex()) {
            equalityAssertionsOnEventStore(expectedEvent, eventStoreCaptor.allValues[idx], idx)
        }

        for ((idx, expectedEvent) in expectedCapturedSavedViews.withIndex()) {
            equalityAssertionsOnView(expectedEvent, viewArgumentCaptor.allValues[idx], idx)
        }

        for ((idx, expectedEvent) in expectedCapturedSentEvents.withIndex()) {
            equalityAssertionsOnSentEvent(expectedEvent, queueArgumentCaptor.allValues[idx], idx)
        }
        val expectedEventsIntertime = batchExecutionTimeWindow / allEvents.size
        val eventsVisibilityTimeouts = eventsVisibilityTimeoutCaptor.allValues
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
        verify(queueAsyncClient, times(6)).sendMessageWithResponse(any<BinaryData>(), any(), any())
    }

    private fun generateTransactionActivatedBaseDocuments(
        howMany: Int,
        transactionId: UUID = UUID.randomUUID()
    ): List<BaseTransaction> {
        val baseDocuments = ArrayList<BaseTransaction>()
        repeat(howMany) {
            baseDocuments.add(
                TransactionActivated(
                    TransactionId(transactionId),
                    listOf(
                        PaymentNotice(
                            PaymentToken("paymentToken"),
                            RptId("77777777777111111111111111111"),
                            TransactionAmount(100),
                            TransactionDescription("description"),
                            PaymentContextCode("paymentContextCode")
                        )
                    ),
                    TransactionTestUtils.EMAIL,
                    "",
                    "",
                    ZonedDateTime.now(),
                    Transaction.ClientId.CHECKOUT
                )
            )
        }
        return baseDocuments
    }

    private fun baseTransactionToExpiryEvent(transaction: BaseTransaction) =
        TransactionExpiredEvent(
            transaction.transactionId.value.toString(),
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
        idx: Int
    ) {
        val equalityMessage = "Event[$idx]"
        assertEquals(
            expectedGeneratedEvent.transactionId,
            capturedValue.transactionId,
            equalityMessage
        )
        assertEquals(TransactionStatusDto.EXPIRED, capturedValue.status, equalityMessage)
    }

    private fun equalityAssertionsOnSentEvent(
        expectedGeneratedEvent: TransactionExpiredEvent,
        binaryData: BinaryData,
        idx: Int
    ) {
        val equalityMessage = "Event[$idx]"
        // assertions on events sent on queue
        val eventSent =
            BinaryData.fromBytes(binaryData.toBytes()).toObject(TransactionExpiredEvent::class.java)
        assertEquals(expectedGeneratedEvent.transactionId, eventSent.transactionId, equalityMessage)
        assertEquals(expectedGeneratedEvent.eventCode, eventSent.eventCode, equalityMessage)
        assertEquals(
            expectedGeneratedEvent.data.statusBeforeExpiration,
            eventSent.data.statusBeforeExpiration,
            equalityMessage
        )
    }
}
