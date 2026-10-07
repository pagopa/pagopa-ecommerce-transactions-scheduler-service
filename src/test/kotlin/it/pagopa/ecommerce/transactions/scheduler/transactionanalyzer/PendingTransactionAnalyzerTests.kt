package it.pagopa.ecommerce.transactions.scheduler.transactionanalyzer

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent
import it.pagopa.ecommerce.commons.documents.BaseTransactionView
import it.pagopa.ecommerce.commons.documents.v1.Transaction as TransactionV1
import it.pagopa.ecommerce.commons.documents.v2.Transaction as TransactionV2
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
import it.pagopa.ecommerce.commons.mdcutilities.LogTracingUtils
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils as TransactionTestUtilsV1
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils as TransactionTestUtilsV2
import it.pagopa.ecommerce.transactions.scheduler.configurations.MicrometerMdcConfiguration
import it.pagopa.ecommerce.transactions.scheduler.publishers.v1.TransactionExpiredEventPublisher as TransactionExpiredEventPublisherV1
import it.pagopa.ecommerce.transactions.scheduler.publishers.v2.TransactionExpiredEventPublisher as TransactionExpiredEventPublisherV2
import it.pagopa.ecommerce.transactions.scheduler.repositories.ecommerce.TransactionsEventStoreRepository
import it.pagopa.ecommerce.transactions.scheduler.repositories.ecommerce.TransactionsViewRepository
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.*
import org.mockito.BDDMockito.given
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.slf4j.MDC
import org.springframework.data.domain.Pageable
import reactor.core.publisher.Flux
import reactor.core.publisher.Hooks
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

@ExtendWith(MockitoExtension::class)
class PendingTransactionAnalyzerTests {

    @Mock
    private lateinit var transactionExpiredEventPublisherV1: TransactionExpiredEventPublisherV1

    @Mock
    private lateinit var transactionExpiredEventPublisherV2: TransactionExpiredEventPublisherV2

    @Mock private lateinit var viewRepository: TransactionsViewRepository

    @Mock private lateinit var eventStoreRepository: TransactionsEventStoreRepository<Any>

    @Mock
    private lateinit var transactionStatusesForSendExpiryEventMocked: Set<TransactionStatusDto>

    @Captor
    private lateinit var transactionStatusArgumentCaptor: ArgumentCaptor<TransactionStatusDto>

    private lateinit var pendingTransactionAnalyzer: PendingTransactionAnalyzer

    private lateinit var transactionStatusesForSendExpiryEventOriginal: Set<TransactionStatusDto>

    private val sendPaymentResultTimeout = 120

    @BeforeEach
    fun `init`() {
        pendingTransactionAnalyzer =
            PendingTransactionAnalyzer(
                expiredTransactionEventPublisherV1 = transactionExpiredEventPublisherV1,
                expiredTransactionEventPublisherV2 = transactionExpiredEventPublisherV2,
                viewRepository = viewRepository,
                eventStoreRepository = eventStoreRepository,
                transactionStatusesForSendExpiryEvent = transactionStatusesForSendExpiryEventMocked,
                sendPaymentResultTimeoutSeconds = sendPaymentResultTimeout
            )
        /*
         * This trick allow to capture the tested status using the real statuses set
         * at test runtime for perform check and be sure that all statuses have been covered by tests
         */
        transactionStatusesForSendExpiryEventOriginal =
            PendingTransactionAnalyzer(
                    expiredTransactionEventPublisherV1 = transactionExpiredEventPublisherV1,
                    expiredTransactionEventPublisherV2 = transactionExpiredEventPublisherV2,
                    viewRepository = viewRepository,
                    eventStoreRepository = eventStoreRepository,
                    sendPaymentResultTimeoutSeconds = sendPaymentResultTimeout
                )
                .transactionStatusesForSendExpiryEvent
    }

    @Test
    fun `Should send expiration event calculating already processed transactions correctly for V1 and V2 found transactions in time window`() {
        // assertions
        val v1Transactions = 8
        val v2Transactions = 6
        val events = ArrayList<BaseTransactionEvent<Any>>()
        repeat(v1Transactions) { _ ->
            events.add(
                it.pagopa.ecommerce.commons.v1.TransactionTestUtils.transactionActivateEvent()
                    as BaseTransactionEvent<Any>
            )
        }
        repeat(v2Transactions) { _ ->
            events.add(
                it.pagopa.ecommerce.commons.v2.TransactionTestUtils.transactionActivateEvent()
                    as BaseTransactionEvent<Any>
            )
        }

        val transactions = ArrayList<BaseTransactionView>()

        repeat(v1Transactions) { _ ->
            transactions.add(
                it.pagopa.ecommerce.commons.v1.TransactionTestUtils.transactionDocument(
                    TransactionStatusDto.EXPIRED,
                    ZonedDateTime.now()
                )
            )
        }
        repeat(v2Transactions) { _ ->
            transactions.add(
                it.pagopa.ecommerce.commons.v2.TransactionTestUtils.transactionDocument(
                    TransactionStatusDto.EXPIRED,
                    ZonedDateTime.now()
                )
            )
        }

        given(
                transactionStatusesForSendExpiryEventMocked.contains(
                    transactionStatusArgumentCaptor.capture()
                )
            )
            .willAnswer { transactionStatusesForSendExpiryEventOriginal.contains(it.arguments[0]) }
        given(
                viewRepository.findTransactionInTimeRangeWithExcludedStatusesPaginated(
                    any(),
                    any(),
                    any(),
                    any()
                )
            )
            .willReturn(Flux.just(*transactions.toTypedArray()))
        given(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(any()))
            .willReturn(Flux.just(*events.toTypedArray()))
        given(transactionExpiredEventPublisherV1.publishExpiryEvents(any(), any(), any(), any()))
            .willReturn(Mono.just(true))
        given(transactionExpiredEventPublisherV2.publishExpiryEvents(any(), any(), any(), any()))
            .willReturn(Mono.just(true))
        // test

        StepVerifier.create(
                pendingTransactionAnalyzer.searchPendingTransactions(
                    LocalDateTime.now(),
                    LocalDateTime.now(),
                    1000,
                    transactions.size.toLong(),
                    Pageable.ofSize(1000)
                )
            )
            .expectNext(true)
            .verifyComplete()
        // V1 events should be sent with offset = 0
        verify(transactionExpiredEventPublisherV1, times(1))
            .publishExpiryEvents(any(), any(), eq(transactions.size.toLong()), eq(0))
        // V2 events should be sent with offset valued based on already processed events + V1 ones
        verify(transactionExpiredEventPublisherV2, times(1))
            .publishExpiryEvents(
                any(),
                any(),
                eq(transactions.size.toLong()),
                eq(v1Transactions.toLong())
            )
        transactionStatusArgumentCaptor.allValues.forEach {
            assertEquals(TransactionStatusDto.ACTIVATED, it)
        }
    }

    @Test
    fun `Should bind each transaction id only to its own analysis chain`() {
        val transactionIdKey = LogTracingUtils.AttributeKeys.CTX_TRANSACTION_ID.key
        val v1Ids = List(3) { "v1-transaction-$it" }
        val v2Ids = List(3) { "v2-transaction-$it" }
        val allIds = v1Ids + v2Ids
        val views: List<BaseTransactionView> =
            v1Ids.map { id -> mock<TransactionV1> { on { transactionId } doReturn id } } +
                v2Ids.map { id -> mock<TransactionV2> { on { transactionId } doReturn id } }
        val contextSeenByQuery = ConcurrentHashMap<String, String>()
        val mdcSeenByQuery = ConcurrentHashMap<String, String>()
        val seenAfterCollect = CopyOnWriteArrayList<String>()

        given(transactionStatusesForSendExpiryEventMocked.contains(any())).willReturn(true)
        given(
                viewRepository.findTransactionInTimeRangeWithExcludedStatusesPaginated(
                    any(),
                    any(),
                    any(),
                    any()
                )
            )
            .willReturn(Flux.fromIterable(views))
        given(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(any())).willAnswer {
            invocation ->
            val transactionId = invocation.getArgument<String>(0)
            val event =
                if (transactionId in v1Ids) {
                    TransactionTestUtilsV1.transactionActivateEvent()
                } else {
                    TransactionTestUtilsV2.transactionActivateEvent()
                }
            Flux.deferContextual { context ->
                contextSeenByQuery[transactionId] =
                    context.getOrDefault(transactionIdKey, "absent")!!
                // reversed delays keep every chain in flight together and complete them out of
                // order, on other threads
                Flux.just(event)
                    .delayElements(
                        Duration.ofMillis(50L * (allIds.size - allIds.indexOf(transactionId)))
                    )
                    .doOnNext {
                        mdcSeenByQuery[transactionId] = MDC.get(transactionIdKey) ?: "absent"
                    }
            }
        }
        val publishAnswer = {
            Mono.deferContextual { context ->
                seenAfterCollect.add(context.getOrDefault(transactionIdKey, "absent")!!)
                seenAfterCollect.add(MDC.get(transactionIdKey) ?: "absent")
                Mono.just(true)
            }
        }
        given(transactionExpiredEventPublisherV1.publishExpiryEvents(any(), any(), any(), any()))
            .willAnswer { publishAnswer() }
        given(transactionExpiredEventPublisherV2.publishExpiryEvents(any(), any(), any(), any()))
            .willAnswer { publishAnswer() }

        MicrometerMdcConfiguration().initMdcMicrometerRegistry()
        try {
            StepVerifier.create(
                    pendingTransactionAnalyzer.searchPendingTransactions(
                        LocalDateTime.now(),
                        LocalDateTime.now(),
                        1000,
                        views.size.toLong(),
                        Pageable.ofSize(1000)
                    )
                )
                .expectNext(true)
                .verifyComplete()
        } finally {
            // the hook is JVM-wide
            Hooks.disableAutomaticContextPropagation()
        }

        val eachIdToItself = allIds.associateWith { it }
        assertEquals(eachIdToItself, contextSeenByQuery)
        assertEquals(eachIdToItself, mdcSeenByQuery)
        assertEquals(List(4) { "absent" }, seenAfterCollect)
    }
}
