package it.pagopa.ecommerce.transactions.scheduler.transactionanalyzer

import it.pagopa.ecommerce.commons.documents.v1.Transaction
import it.pagopa.ecommerce.commons.documents.v1.TransactionClosureData
import it.pagopa.ecommerce.commons.documents.v1.TransactionEvent
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

    private lateinit var pendingTransactionAnalyzer: PendingTransactionAnalyzer

    @BeforeEach
    fun `init`() {
        pendingTransactionAnalyzer =
            PendingTransactionAnalyzer(
                Logger.getGlobal(),
                transactionExpiredEventPublisher,
                viewRepository,
                eventStoreRepository
            )
    }

    @Test
    fun `Should send event for pending transaction in ACTIVATED state`() {
        // assertions
        val events =
            listOf(TransactionTestUtils.transactionActivateEvent()) as List<TransactionEvent<Any>>
        val transactions =
            listOf(
                TransactionTestUtils.transactionDocument(
                    TransactionStatusDto.ACTIVATED,
                    ZonedDateTime.now()
                )
            )
        checkThatExpiryEventIsSent(events, transactions)
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

        val transactions =
            listOf(
                TransactionTestUtils.transactionDocument(
                    TransactionStatusDto.AUTHORIZATION_REQUESTED,
                    ZonedDateTime.now()
                )
            )
        checkThatExpiryEventIsSent(events, transactions)
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

        val transactions =
            listOf(
                TransactionTestUtils.transactionDocument(
                    TransactionStatusDto.AUTHORIZATION_COMPLETED,
                    ZonedDateTime.now()
                )
            )
        checkThatExpiryEventIsSent(events, transactions)
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

        val transactions =
            listOf(
                TransactionTestUtils.transactionDocument(
                    TransactionStatusDto.CLOSURE_ERROR,
                    ZonedDateTime.now()
                )
            )
        checkThatExpiryEventIsSent(events, transactions)
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

        val transactions =
            listOf(
                TransactionTestUtils.transactionDocument(
                    TransactionStatusDto.CLOSED,
                    ZonedDateTime.now()
                )
            )
        checkThatExpiryEventIsSent(events, transactions)
    }

    @Test
    fun `Should not send event for pending transaction in EXPIRED_NOT_AUTHORIZED state`() {
        // assertions
        val events =
            listOf(
                TransactionTestUtils.transactionActivateEvent(),
                TransactionTestUtils.transactionExpiredEvent(TransactionStatusDto.ACTIVATED)
            )
                as List<TransactionEvent<Any>>

        val transactions =
            listOf(
                TransactionTestUtils.transactionDocument(
                    TransactionStatusDto.EXPIRED_NOT_AUTHORIZED,
                    ZonedDateTime.now()
                )
            )
        checkThatExpiryEventIsNotSent(events, transactions)
    }

    @Test
    fun `Should not send event for pending transaction in CANCELED state`() {
        // assertions
        val events =
            listOf(
                TransactionTestUtils.transactionActivateEvent(),
                TransactionTestUtils.transactionUserCanceledEvent()
            )
                as List<TransactionEvent<Any>>

        val transactions =
            listOf(
                TransactionTestUtils.transactionDocument(
                    TransactionStatusDto.CANCELED,
                    ZonedDateTime.now()
                )
            )
        checkThatExpiryEventIsNotSent(events, transactions)
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

        val transactions =
            listOf(
                TransactionTestUtils.transactionDocument(
                    TransactionStatusDto.UNAUTHORIZED,
                    ZonedDateTime.now()
                )
            )
        checkThatExpiryEventIsNotSent(events, transactions)
    }

    @Test
    fun `Should not send event for pending transaction in NOTIFIED state`() {
        // assertions
        val events =
            listOf(
                TransactionTestUtils.transactionActivateEvent(),
                TransactionTestUtils.transactionAuthorizationRequestedEvent(),
                TransactionTestUtils.transactionAuthorizationCompletedEvent(
                    AuthorizationResultDto.OK
                ),
                TransactionTestUtils.transactionClosedEvent(TransactionClosureData.Outcome.OK),
                TransactionTestUtils.transactionUserReceiptAddedEvent()
            )
                as List<TransactionEvent<Any>>

        val transactions =
            listOf(
                TransactionTestUtils.transactionDocument(
                    TransactionStatusDto.NOTIFIED,
                    ZonedDateTime.now()
                )
            )
        checkThatExpiryEventIsNotSent(events, transactions)
    }

    @Test
    fun `Should not send event for pending transaction in EXPIRED state`() {
        // assertions
        val events =
            listOf(
                TransactionTestUtils.transactionActivateEvent(),
                TransactionTestUtils.transactionAuthorizationRequestedEvent(),
                TransactionTestUtils.transactionAuthorizationCompletedEvent(
                    AuthorizationResultDto.OK
                ),
                TransactionTestUtils.transactionClosedEvent(TransactionClosureData.Outcome.OK),
                TransactionTestUtils.transactionExpiredEvent(TransactionStatusDto.CLOSED)
            )
                as List<TransactionEvent<Any>>

        val transactions =
            listOf(
                TransactionTestUtils.transactionDocument(
                    TransactionStatusDto.EXPIRED,
                    ZonedDateTime.now()
                )
            )
        checkThatExpiryEventIsNotSent(events, transactions)
    }

    @Test
    fun `Should not send event for pending transaction in REFUNDED state`() {
        // assertions
        val events =
            listOf(
                TransactionTestUtils.transactionActivateEvent(),
                TransactionTestUtils.transactionAuthorizationRequestedEvent(),
                TransactionTestUtils.transactionAuthorizationCompletedEvent(
                    AuthorizationResultDto.OK
                ),
                TransactionTestUtils.transactionClosedEvent(TransactionClosureData.Outcome.OK),
                TransactionTestUtils.transactionExpiredEvent(TransactionStatusDto.CLOSED),
                TransactionTestUtils.transactionRefundedEvent(TransactionStatusDto.EXPIRED)
            )
                as List<TransactionEvent<Any>>

        val transactions =
            listOf(
                TransactionTestUtils.transactionDocument(
                    TransactionStatusDto.EXPIRED,
                    ZonedDateTime.now()
                )
            )
        checkThatExpiryEventIsNotSent(events, transactions)
    }

    private fun checkThatExpiryEventIsSent(
        events: List<TransactionEvent<Any>>,
        transactions: List<Transaction>
    ) {
        given(viewRepository.findTransactionInTimeRangeWithExcludedStatuses(any(), any(), any()))
            .willReturn(Flux.just(*transactions.toTypedArray()))
        given(eventStoreRepository.findByTransactionId(any()))
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
    }

    private fun checkThatExpiryEventIsNotSent(
        events: List<TransactionEvent<Any>>,
        transactions: List<Transaction>
    ) {
        given(viewRepository.findTransactionInTimeRangeWithExcludedStatuses(any(), any(), any()))
            .willReturn(Flux.just(*transactions.toTypedArray()))
        given(eventStoreRepository.findByTransactionId(any()))
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
    }
}
