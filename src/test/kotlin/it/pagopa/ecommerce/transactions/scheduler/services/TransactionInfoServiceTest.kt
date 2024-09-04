package it.pagopa.ecommerce.transactions.scheduler.deadletter

import com.azure.spring.messaging.checkpoint.Checkpointer
import it.pagopa.ecommerce.commons.documents.v2.*
import it.pagopa.ecommerce.commons.documents.v2.activation.NpgTransactionGatewayActivationData
import it.pagopa.ecommerce.commons.documents.v2.refund.NpgGatewayRefundData
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OperationResultDto
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils
import it.pagopa.ecommerce.transactions.scheduler.repositories.TransactionsEventStoreRepository
import it.pagopa.ecommerce.transactions.scheduler.services.TransactionInfoService
import it.pagopa.ecommerce.transactions.scheduler.services.baseTransactionToTransactionInfoDto
import java.time.ZonedDateTime
import java.util.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class TransactionInfoServiceTest {

    private val transactionEventRepository: TransactionsEventStoreRepository<Any> = mock()
    private val checkPointer: Checkpointer = mock()
    private val transactionInfoService =
        TransactionInfoService(transactionsEventStoreRepository = transactionEventRepository)

    @Test
    fun `Should process correctly the event into a transaction info`() {

        val transactionView =
            TransactionTestUtils.transactionDocument(
                TransactionStatusDto.NOTIFIED_OK,
                ZonedDateTime.now()
            )

        val transactionUserReceiptData =
            TransactionTestUtils.transactionUserReceiptData(TransactionUserReceiptData.Outcome.OK)
        val transactionActivatedEvent = TransactionTestUtils.transactionActivateEvent()
        val authorizationRequestedEvent =
            TransactionTestUtils.transactionAuthorizationRequestedEvent()
        val closureSentEvent =
            TransactionTestUtils.transactionClosedEvent(TransactionClosureData.Outcome.KO)
        val addUserReceiptEvent =
            TransactionTestUtils.transactionUserReceiptRequestedEvent(transactionUserReceiptData)
        val userReceiptAddErrorEvent =
            TransactionTestUtils.transactionUserReceiptAddErrorEvent(addUserReceiptEvent.data)
        val userReceiptAddedEvent =
            TransactionTestUtils.transactionUserReceiptAddedEvent(userReceiptAddErrorEvent.data)
        val events =
            listOf(
                transactionActivatedEvent,
                authorizationRequestedEvent,
                closureSentEvent,
                addUserReceiptEvent,
                userReceiptAddErrorEvent,
                userReceiptAddedEvent
            )
                as List<TransactionEvent<Any>>

        given(checkPointer.success()).willReturn(Mono.empty())
        given(
                transactionEventRepository.findByTransactionIdOrderByCreationDateAsc(
                    transactionView.transactionId
                )
            )
            .willReturn(Flux.fromIterable(events))

        val baseTransaction = TransactionTestUtils.reduceEvents(*events.toTypedArray())

        val expected = baseTransactionToTransactionInfoDto(baseTransaction)

        StepVerifier.create(
                transactionInfoService.getTransactionInfoByTransactionId(
                    transactionView.transactionId
                )
            )
            .expectNext(expected)
            .verifyComplete()
    }

    @Test
    fun `Should process the events when they contain refund requests`() {
        val orderId = "orderId"
        val refundOperationId = "refundOperationId"
        val correlationId = UUID.randomUUID().toString()

        val transactionView =
            TransactionTestUtils.transactionDocument(
                TransactionStatusDto.REFUNDED,
                ZonedDateTime.now()
            )
        val transactionActivatedEvent =
            TransactionTestUtils.transactionActivateEvent(
                NpgTransactionGatewayActivationData(orderId, correlationId)
            )
        val transactionAuthorizationRequestedEvent =
            TransactionTestUtils.transactionAuthorizationRequestedEvent(
                TransactionAuthorizationRequestData.PaymentGateway.NPG
            )

        val transactionExpiredEvent =
            TransactionTestUtils.transactionExpiredEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent
                )
            )
        val transactionRefundRequestedEvent =
            TransactionTestUtils.transactionRefundRequestedEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent,
                    transactionExpiredEvent
                ),
                null // N.B.: Is null when getting error while retrieving authorization data from
                // gateway
                )
        val transactionRefundErrorEvent =
            TransactionTestUtils.transactionRefundErrorEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent,
                    transactionExpiredEvent,
                    transactionRefundRequestedEvent
                )
            )
        val transactionRefundRetryEvent =
            TransactionTestUtils.transactionRefundRetriedEvent(
                1,
                TransactionTestUtils.npgTransactionGatewayAuthorizationData(
                    OperationResultDto.EXECUTED
                )
            )
        val transactionRefundedEvent =
            TransactionTestUtils.transactionRefundedEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent,
                    transactionExpiredEvent,
                    transactionRefundRequestedEvent,
                    transactionRefundErrorEvent,
                    transactionRefundRetryEvent
                ),
                NpgGatewayRefundData(refundOperationId)
            )

        val events =
            listOf(
                transactionActivatedEvent,
                transactionAuthorizationRequestedEvent,
                transactionExpiredEvent,
                transactionRefundRequestedEvent,
                transactionRefundErrorEvent,
                transactionRefundRetryEvent,
                transactionRefundedEvent
            )
                as List<TransactionEvent<Any>>

        given(checkPointer.success()).willReturn(Mono.empty())
        given(
                transactionEventRepository.findByTransactionIdOrderByCreationDateAsc(
                    transactionView.transactionId
                )
            )
            .willReturn(Flux.fromIterable(events))

        val baseTransaction = TransactionTestUtils.reduceEvents(*events.toTypedArray())

        val expected = baseTransactionToTransactionInfoDto(baseTransaction)

        StepVerifier.create(
                transactionInfoService.getTransactionInfoByTransactionId(
                    transactionView.transactionId
                )
            )
            .expectNext(expected)
            .verifyComplete()
    }
}
