package it.pagopa.ecommerce.transactions.scheduler.transactionanalyzer

import it.pagopa.ecommerce.commons.documents.v1.TransactionClosureData
import it.pagopa.ecommerce.commons.documents.v1.TransactionEvent
import it.pagopa.ecommerce.commons.documents.v1.TransactionUserReceiptData
import it.pagopa.ecommerce.commons.domain.v1.EmptyTransaction
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction
import it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils
import it.pagopa.ecommerce.transactions.scheduler.publishers.TransactionExpiredEventPublisher
import it.pagopa.ecommerce.transactions.scheduler.repositories.TransactionsEventStoreRepository
import it.pagopa.ecommerce.transactions.scheduler.repositories.TransactionsViewRepository
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.*
import java.util.logging.Logger
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.*
import org.mockito.BDDMockito.given
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

@ExtendWith(MockitoExtension::class)
class PendingTransactionAnalyzerTests {

    @Mock private lateinit var transactionExpiredEventPublisher: TransactionExpiredEventPublisher

    @Mock private lateinit var viewRepository: TransactionsViewRepository

    @Mock private lateinit var eventStoreRepository: TransactionsEventStoreRepository<Any>

    @Mock
    private lateinit var transactionStatusesForSendExpiryEventMocked: Set<TransactionStatusDto>

    @Captor
    private lateinit var transactionStatusArgumentCaptor: ArgumentCaptor<TransactionStatusDto>

    private lateinit var pendingTransactionAnalyzer: PendingTransactionAnalyzer

    private lateinit var transactionStatusesForSendExpiryEventOriginal: Set<TransactionStatusDto>

    companion object {
        val testedStatuses: MutableSet<TransactionStatusDto> = HashSet()

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            testedStatuses.clear()
        }

        @JvmStatic
        @AfterAll
        fun afterAll() {
            TransactionStatusDto.values().forEach {
                assertTrue(
                    testedStatuses.contains(it),
                    "Error: Transaction in status [$it] NOT covered by tests!"
                )
            }
            testedStatuses.clear()
        }
    }

    @BeforeEach
    fun `init`() {
        pendingTransactionAnalyzer =
            PendingTransactionAnalyzer(
                logger = Logger.getGlobal(),
                expiredTransactionEventPublisher = transactionExpiredEventPublisher,
                viewRepository = viewRepository,
                eventStoreRepository = eventStoreRepository,
                transactionStatusesForSendExpiryEvent = transactionStatusesForSendExpiryEventMocked
            )
        /*
         * This trick allow to capture the tested status using the real statuses set
         * at test runtime for perform check and be sure that all statuses have been covered by tests
         */
        transactionStatusesForSendExpiryEventOriginal =
            PendingTransactionAnalyzer(
                    logger = Logger.getGlobal(),
                    expiredTransactionEventPublisher = transactionExpiredEventPublisher,
                    viewRepository = viewRepository,
                    eventStoreRepository = eventStoreRepository
                )
                .transactionStatusesForSendExpiryEvent
    }

    @Test
    fun `Should send event for pending transaction in ACTIVATED state`() {
        // assertions
        val events =
            listOf(TransactionTestUtils.transactionActivateEvent()) as List<TransactionEvent<Any>>

        checkThatExpiryEventIsSent(events, TransactionStatusDto.ACTIVATED)
    }

    @Test
    fun `Should send event for pending transaction in AUTHORIZATION_REQUESTED state`() {
        // assertions
        val events =
            listOf(
                TransactionTestUtils.transactionActivateEvent(),
                TransactionTestUtils.transactionAuthorizationRequestedEvent()
            )
                as List<TransactionEvent<Any>>

        checkThatExpiryEventIsSent(events, TransactionStatusDto.AUTHORIZATION_REQUESTED)
    }

    @Test
    fun `Should send event for pending transaction in AUTHORIZATION_COMPLETED state`() {
        // assertions
        val events =
            listOf(
                TransactionTestUtils.transactionActivateEvent(),
                TransactionTestUtils.transactionAuthorizationRequestedEvent(),
                TransactionTestUtils.transactionAuthorizationCompletedEvent()
            )
                as List<TransactionEvent<Any>>

        checkThatExpiryEventIsSent(events, TransactionStatusDto.AUTHORIZATION_COMPLETED)
    }

    @Test
    fun `Should send event for pending transaction in CLOSURE_ERROR state`() {
        // assertions
        val events =
            listOf(
                TransactionTestUtils.transactionActivateEvent(),
                TransactionTestUtils.transactionAuthorizationRequestedEvent(),
                TransactionTestUtils.transactionAuthorizationCompletedEvent(),
                TransactionTestUtils.transactionClosureErrorEvent()
            )
                as List<TransactionEvent<Any>>

        checkThatExpiryEventIsSent(events, TransactionStatusDto.CLOSURE_ERROR)
    }

    @Test
    fun `Should send event for pending transaction in CANCELLATION_REQUESTED state`() {
        // assertions
        val events =
            listOf(
                TransactionTestUtils.transactionActivateEvent(),
                TransactionTestUtils.transactionUserCanceledEvent()
            )
                as List<TransactionEvent<Any>>

        checkThatExpiryEventIsSent(events, TransactionStatusDto.CANCELLATION_REQUESTED)
    }

    @Test
    fun `Should send event for pending transaction in CLOSED state`() {
        // assertions
        val events =
            listOf(
                TransactionTestUtils.transactionActivateEvent(),
                TransactionTestUtils.transactionAuthorizationRequestedEvent(),
                TransactionTestUtils.transactionAuthorizationCompletedEvent(),
                TransactionTestUtils.transactionClosedEvent(TransactionClosureData.Outcome.OK)
            )
                as List<TransactionEvent<Any>>

        checkThatExpiryEventIsSent(events, TransactionStatusDto.CLOSED)
    }

    @Test
    fun `Should send event for pending transaction in NOTIFICATION_ERROR user receipt OK state`() {
        // assertions
        val events =
            listOf(
                TransactionTestUtils.transactionActivateEvent(),
                TransactionTestUtils.transactionAuthorizationRequestedEvent(),
                TransactionTestUtils.transactionAuthorizationCompletedEvent(),
                TransactionTestUtils.transactionClosedEvent(TransactionClosureData.Outcome.OK),
                TransactionTestUtils.transactionUserReceiptRequestedEvent(
                    TransactionTestUtils.transactionUserReceiptData(
                        TransactionUserReceiptData.Outcome.OK
                    )
                ),
                TransactionTestUtils.transactionUserReceiptAddErrorEvent(
                    TransactionTestUtils.transactionUserReceiptData(
                        TransactionUserReceiptData.Outcome.OK
                    )
                )
            )
                as List<TransactionEvent<Any>>

        checkThatExpiryEventIsSent(events, TransactionStatusDto.NOTIFICATION_ERROR)
    }

    @Test
    fun `Should send event for pending transaction in NOTIFICATION_ERROR user receipt KO state`() {
        // assertions
        val events =
            listOf(
                TransactionTestUtils.transactionActivateEvent(),
                TransactionTestUtils.transactionAuthorizationRequestedEvent(),
                TransactionTestUtils.transactionAuthorizationCompletedEvent(),
                TransactionTestUtils.transactionClosedEvent(TransactionClosureData.Outcome.OK),
                TransactionTestUtils.transactionUserReceiptRequestedEvent(
                    TransactionTestUtils.transactionUserReceiptData(
                        TransactionUserReceiptData.Outcome.KO
                    )
                ),
                TransactionTestUtils.transactionUserReceiptAddErrorEvent(
                    TransactionTestUtils.transactionUserReceiptData(
                        TransactionUserReceiptData.Outcome.KO
                    )
                )
            )
                as List<TransactionEvent<Any>>

        checkThatExpiryEventIsSent(events, TransactionStatusDto.NOTIFICATION_ERROR)
    }

    @Test
    fun `Should send event for pending transaction in NOTIFICATION_REQUESTED user receipt OK state`() {
        // assertions
        val events =
            listOf(
                TransactionTestUtils.transactionActivateEvent(),
                TransactionTestUtils.transactionAuthorizationRequestedEvent(),
                TransactionTestUtils.transactionAuthorizationCompletedEvent(),
                TransactionTestUtils.transactionClosedEvent(TransactionClosureData.Outcome.OK),
                TransactionTestUtils.transactionUserReceiptRequestedEvent(
                    TransactionTestUtils.transactionUserReceiptData(
                        TransactionUserReceiptData.Outcome.OK
                    )
                )
            )
                as List<TransactionEvent<Any>>

        checkThatExpiryEventIsSent(events, TransactionStatusDto.NOTIFICATION_REQUESTED)
    }

    @Test
    fun `Should send event for pending transaction in NOTIFICATION_REQUESTED user receipt KO state`() {
        // assertions
        val events =
            listOf(
                TransactionTestUtils.transactionActivateEvent(),
                TransactionTestUtils.transactionAuthorizationRequestedEvent(),
                TransactionTestUtils.transactionAuthorizationCompletedEvent(),
                TransactionTestUtils.transactionClosedEvent(TransactionClosureData.Outcome.OK),
                TransactionTestUtils.transactionUserReceiptRequestedEvent(
                    TransactionTestUtils.transactionUserReceiptData(
                        TransactionUserReceiptData.Outcome.KO
                    )
                )
            )
                as List<TransactionEvent<Any>>

        checkThatExpiryEventIsSent(events, TransactionStatusDto.NOTIFICATION_REQUESTED)
    }

    @Test
    fun `Should not send event for pending transaction in EXPIRED_NOT_AUTHORIZED state`() {
        // assertions
        val events =
            listOf(
                TransactionTestUtils.transactionActivateEvent(),
                TransactionTestUtils.transactionExpiredEvent(
                    TransactionTestUtils.transactionActivated(ZonedDateTime.now().toString())
                )
            )
                as List<TransactionEvent<Any>>

        checkThatExpiryEventIsNotSent(events, TransactionStatusDto.EXPIRED_NOT_AUTHORIZED)
    }

    @Test
    fun `Should not send event for pending transaction in CANCELED state`() {
        // assertions
        val events =
            listOf(
                TransactionTestUtils.transactionActivateEvent(),
                TransactionTestUtils.transactionUserCanceledEvent(),
                TransactionTestUtils.transactionClosedEvent(TransactionClosureData.Outcome.OK)
            )
                as List<TransactionEvent<Any>>

        checkThatExpiryEventIsNotSent(events, TransactionStatusDto.CANCELED)
    }

    @Test
    fun `Should not send event for pending transaction in UNAUTHORIZED state`() {
        // assertions
        val events =
            listOf(
                TransactionTestUtils.transactionActivateEvent(),
                TransactionTestUtils.transactionAuthorizationRequestedEvent(),
                TransactionTestUtils.transactionAuthorizationCompletedEvent(
                    AuthorizationResultDto.KO
                ),
                TransactionTestUtils.transactionClosureFailedEvent(
                    TransactionClosureData.Outcome.OK
                )
            )
                as List<TransactionEvent<Any>>

        checkThatExpiryEventIsNotSent(events, TransactionStatusDto.UNAUTHORIZED)
    }

    @Test
    fun `Should not send event for pending transaction in NOTIFIED_OK state`() {
        // assertions
        val events =
            listOf(
                TransactionTestUtils.transactionActivateEvent(),
                TransactionTestUtils.transactionAuthorizationRequestedEvent(),
                TransactionTestUtils.transactionAuthorizationCompletedEvent(
                    AuthorizationResultDto.OK
                ),
                TransactionTestUtils.transactionClosedEvent(TransactionClosureData.Outcome.OK),
                TransactionTestUtils.transactionUserReceiptRequestedEvent(
                    TransactionTestUtils.transactionUserReceiptData(
                        TransactionUserReceiptData.Outcome.OK
                    )
                ),
                TransactionTestUtils.transactionUserReceiptAddedEvent(
                    TransactionTestUtils.transactionUserReceiptData(
                        TransactionUserReceiptData.Outcome.OK
                    )
                )
            )
                as List<TransactionEvent<Any>>

        checkThatExpiryEventIsNotSent(events, TransactionStatusDto.NOTIFIED_OK)
    }

    @Test
    fun `Should send event for pending transaction in NOTIFIED_KO state`() {
        // assertions
        val events =
            listOf(
                TransactionTestUtils.transactionActivateEvent(),
                TransactionTestUtils.transactionAuthorizationRequestedEvent(),
                TransactionTestUtils.transactionAuthorizationCompletedEvent(
                    AuthorizationResultDto.OK
                ),
                TransactionTestUtils.transactionClosedEvent(TransactionClosureData.Outcome.OK),
                TransactionTestUtils.transactionUserReceiptRequestedEvent(
                    TransactionTestUtils.transactionUserReceiptData(
                        TransactionUserReceiptData.Outcome.KO
                    )
                ),
                TransactionTestUtils.transactionUserReceiptAddedEvent(
                    TransactionTestUtils.transactionUserReceiptData(
                        TransactionUserReceiptData.Outcome.KO
                    )
                )
            )
                as List<TransactionEvent<Any>>

        checkThatExpiryEventIsSent(events, TransactionStatusDto.NOTIFIED_KO)
    }

    @Test
    fun `Should not send event for pending transaction in EXPIRED state`() {
        // assertions

        var events =
            listOf(
                TransactionTestUtils.transactionActivateEvent(),
                TransactionTestUtils.transactionAuthorizationRequestedEvent(),
                TransactionTestUtils.transactionAuthorizationCompletedEvent(
                    AuthorizationResultDto.OK
                ),
                TransactionTestUtils.transactionClosedEvent(TransactionClosureData.Outcome.OK),
            )
                as List<TransactionEvent<Any>>
        events =
            events.plus(
                TransactionTestUtils.transactionExpiredEvent(reduceEvents(events))
                    as TransactionEvent<Any>
            )

        checkThatExpiryEventIsNotSent(events, TransactionStatusDto.EXPIRED)
    }

    @Test
    fun `Should not send event for pending transaction in REFUND_REQUESTED state`() {
        // assertions
        var events =
            listOf(
                TransactionTestUtils.transactionActivateEvent(),
                TransactionTestUtils.transactionAuthorizationRequestedEvent(),
                TransactionTestUtils.transactionAuthorizationCompletedEvent(
                    AuthorizationResultDto.OK
                ),
                TransactionTestUtils.transactionClosedEvent(TransactionClosureData.Outcome.OK),
            )
                as List<TransactionEvent<Any>>
        events =
            events.plus(
                TransactionTestUtils.transactionExpiredEvent(reduceEvents(events))
                    as TransactionEvent<Any>
            )
        events =
            events.plus(
                TransactionTestUtils.transactionRefundRequestedEvent(reduceEvents(events))
                    as TransactionEvent<Any>
            )

        checkThatExpiryEventIsNotSent(events, TransactionStatusDto.REFUND_REQUESTED)
    }

    @Test
    fun `Should not send event for pending transaction in REFUND_ERROR state`() {
        // assertions
        var events =
            listOf(
                TransactionTestUtils.transactionActivateEvent(),
                TransactionTestUtils.transactionAuthorizationRequestedEvent(),
                TransactionTestUtils.transactionAuthorizationCompletedEvent(
                    AuthorizationResultDto.OK
                ),
                TransactionTestUtils.transactionClosedEvent(TransactionClosureData.Outcome.OK),
            )
                as List<TransactionEvent<Any>>
        events =
            events.plus(
                TransactionTestUtils.transactionExpiredEvent(reduceEvents(events))
                    as TransactionEvent<Any>
            )
        events =
            events.plus(
                TransactionTestUtils.transactionRefundRequestedEvent(reduceEvents(events))
                    as TransactionEvent<Any>
            )

        events =
            events.plus(
                TransactionTestUtils.transactionRefundErrorEvent(reduceEvents(events))
                    as TransactionEvent<Any>
            )

        checkThatExpiryEventIsNotSent(events, TransactionStatusDto.REFUND_ERROR)
    }

    @Test
    fun `Should not send event for pending transaction in REFUNDED state`() {
        // assertions
        var events =
            listOf(
                TransactionTestUtils.transactionActivateEvent(),
                TransactionTestUtils.transactionAuthorizationRequestedEvent(),
                TransactionTestUtils.transactionAuthorizationCompletedEvent(
                    AuthorizationResultDto.OK
                ),
                TransactionTestUtils.transactionClosedEvent(TransactionClosureData.Outcome.OK),
            )
                as List<TransactionEvent<Any>>
        events =
            events.plus(
                TransactionTestUtils.transactionExpiredEvent(reduceEvents(events))
                    as TransactionEvent<Any>
            )
        events =
            events.plus(
                TransactionTestUtils.transactionRefundRequestedEvent(reduceEvents(events))
                    as TransactionEvent<Any>
            )
        events =
            events.plus(
                TransactionTestUtils.transactionRefundedEvent(reduceEvents(events))
                    as TransactionEvent<Any>
            )

        checkThatExpiryEventIsNotSent(events, TransactionStatusDto.REFUNDED)
    }

    @Test
    fun `Should not send event for pending transaction in CANCELLATION_EXPIRED state`() {
        // assertions
        var events =
            listOf(
                TransactionTestUtils.transactionActivateEvent(),
                TransactionTestUtils.transactionUserCanceledEvent(),
            )
                as List<TransactionEvent<Any>>

        events =
            events.plus(
                TransactionTestUtils.transactionExpiredEvent(reduceEvents(events))
                    as TransactionEvent<Any>
            )

        checkThatExpiryEventIsNotSent(events, TransactionStatusDto.CANCELLATION_EXPIRED)
    }

    private fun checkThatExpiryEventIsSent(
        events: List<TransactionEvent<Any>>,
        expectedTransactionStatus: TransactionStatusDto
    ) {

        val transactions =
            listOf(
                TransactionTestUtils.transactionDocument(
                    expectedTransactionStatus,
                    ZonedDateTime.now()
                )
            )
        given(
                transactionStatusesForSendExpiryEventMocked.contains(
                    transactionStatusArgumentCaptor.capture()
                )
            )
            .willAnswer { transactionStatusesForSendExpiryEventOriginal.contains(it.arguments[0]) }
        given(viewRepository.findTransactionInTimeRangeWithExcludedStatuses(any(), any(), any()))
            .willReturn(Flux.just(*transactions.toTypedArray()))
        given(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(any()))
            .willReturn(Flux.just(*events.toTypedArray()))
        given(transactionExpiredEventPublisher.publishExpiryEvents(any(), any()))
            .willReturn(Mono.just(true))
        // test
        StepVerifier.create(
                pendingTransactionAnalyzer.searchPendingTransactions(
                    LocalDateTime.now(),
                    LocalDateTime.now(),
                    1000
                )
            )
            .expectNext(true)
            .verifyComplete()
        verify(transactionExpiredEventPublisher, times(1)).publishExpiryEvents(any(), any())
        // This check has the purpose of check that the test list of events effectively cover the
        // wanted scenario.
        // The wanted scenario, in fact, is taken as input parameter by this method so developer
        // must explicitly declare
        // what transaction status scenario wanted to be tested, avoiding false positive tests where
        // the input event list
        // does not cover the wanted scenario. Ex: test written for cover CLOSURE ERROR case but the
        // input event list does
        // not generate a transaction in CLOSURE_ERROR state just because the closure error event
        // was missing from the event list
        assertEquals(expectedTransactionStatus, transactionStatusArgumentCaptor.value)
        testedStatuses.add(transactionStatusArgumentCaptor.value)
    }

    private fun checkThatExpiryEventIsNotSent(
        events: List<TransactionEvent<Any>>,
        expectedTransactionStatus: TransactionStatusDto
    ) {
        val transactions =
            listOf(
                TransactionTestUtils.transactionDocument(
                    expectedTransactionStatus,
                    ZonedDateTime.now()
                )
            )
        given(
                transactionStatusesForSendExpiryEventMocked.contains(
                    transactionStatusArgumentCaptor.capture()
                )
            )
            .willAnswer { transactionStatusesForSendExpiryEventOriginal.contains(it.arguments[0]) }
        given(viewRepository.findTransactionInTimeRangeWithExcludedStatuses(any(), any(), any()))
            .willReturn(Flux.just(*transactions.toTypedArray()))
        given(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(any()))
            .willReturn(Flux.just(*events.toTypedArray()))
        // test
        StepVerifier.create(
                pendingTransactionAnalyzer.searchPendingTransactions(
                    LocalDateTime.now(),
                    LocalDateTime.now(),
                    1000
                )
            )
            .expectNext(true)
            .verifyComplete()
        verify(transactionExpiredEventPublisher, times(0)).publishExpiryEvents(any(), any())
        // This check has the purpose of check that the test list of events effectively cover the
        // wanted scenario.
        // The wanted scenario, in fact, is taken as input parameter by this method so developer
        // must explicitly declare
        // what transaction status scenario wanted to be tested, avoiding false positive tests where
        // the input event list
        // does not cover the wanted scenario. Ex: test written for cover CLOSURE ERROR case but the
        // input event list does
        // not generate a transaction in CLOSURE_ERROR state just because the closure error event
        // was missing from the event list
        assertEquals(expectedTransactionStatus, transactionStatusArgumentCaptor.value)
        testedStatuses.add(transactionStatusArgumentCaptor.value)
    }

    private fun reduceEvents(events: List<TransactionEvent<out Any>>): BaseTransaction {
        val emptyTransaction = EmptyTransaction()
        val listToReduce: List<Any> = listOf(emptyTransaction).plus(events)
        return listToReduce.reduce { trx, event ->
            (trx as it.pagopa.ecommerce.commons.domain.v1.Transaction).applyEvent(event)
        } as BaseTransaction
    }
}
