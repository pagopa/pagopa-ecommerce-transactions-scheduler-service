package it.pagopa.ecommerce.transactions.scheduler.utils

import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestData
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestedEvent
import it.pagopa.ecommerce.commons.documents.v2.TransactionEvent
import it.pagopa.ecommerce.commons.documents.v2.activation.NpgTransactionGatewayActivationData
import it.pagopa.ecommerce.commons.documents.v2.refund.NpgGatewayRefundData
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OperationDto
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OperationResultDto
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OperationTypeDto
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OrderResponseDto
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils
import reactor.core.publisher.Mono

class TransactionInfoUtils {
    companion object {
        val orderId = "orderId"
        val refundOperationId = "refundOperationId"

        fun buildOrderResponseDtoForNgpOrderNotAuthorized(): Mono<OrderResponseDto> {
            val orderResponseDto = OrderResponseDto()
            val operationDto = OperationDto()
            operationDto.operationId = "operationId"
            operationDto.paymentEndToEndId = "paymentEndToEndId"
            operationDto.operationType = OperationTypeDto.AUTHORIZATION
            operationDto.operationResult = OperationResultDto.FAILED
            orderResponseDto.operations = listOf(operationDto)
            return Mono.just(orderResponseDto)
        }
        fun buildOrderResponseDtoForNgpOrderAuthorized(): Mono<OrderResponseDto> {
            val orderResponseDto = OrderResponseDto()
            val operationDto = OperationDto()
            operationDto.operationId = "operationId"
            operationDto.paymentEndToEndId = "paymentEndToEndId"
            operationDto.operationType = OperationTypeDto.AUTHORIZATION
            operationDto.operationResult = OperationResultDto.EXECUTED
            orderResponseDto.operations = listOf(operationDto)
            return Mono.just(orderResponseDto)
        }
        fun buildOrderResponseDtoForNpgOrderVoid(): Mono<OrderResponseDto> {
            val orderResponseDto = OrderResponseDto()
            val operationDto = OperationDto()
            operationDto.operationId = "operationId"
            operationDto.paymentEndToEndId = "paymentEndToEndId"
            operationDto.operationType = OperationTypeDto.VOID
            operationDto.operationResult = OperationResultDto.VOIDED
            orderResponseDto.operations = listOf(operationDto)
            return Mono.just(orderResponseDto)
        }
        fun buildOrderResponseDtoForNpgOrderRefunded(): Mono<OrderResponseDto> {
            val orderResponseDto = OrderResponseDto()
            val operationDto = OperationDto()
            operationDto.operationId = "operationId"
            operationDto.paymentEndToEndId = "paymentEndToEndId"
            operationDto.operationType = OperationTypeDto.REFUND
            operationDto.operationResult = OperationResultDto.VOIDED
            orderResponseDto.operations = listOf(operationDto)
            return Mono.just(orderResponseDto)
        }
        fun buildOrderResponseDtoForNpgOrderRefundedFaulty(): Mono<OrderResponseDto> {
            val orderResponseDto = OrderResponseDto()
            val operationDto = OperationDto()
            operationDto.operationId = "operationId"
            operationDto.paymentEndToEndId = "paymentEndToEndId"
            operationDto.operationType = OperationTypeDto.REFUND
            operationDto.operationResult = OperationResultDto.EXECUTED
            orderResponseDto.operations = listOf(operationDto)
            return Mono.just(orderResponseDto)
        }
        fun buildOrderResponseDtoInvalidOrder(): Mono<OrderResponseDto> {
            val orderResponseDto = OrderResponseDto()
            val operationDto = OperationDto()
            operationDto.operationType = null
            operationDto.operationResult = null
            orderResponseDto.operations = listOf(operationDto)
            return Mono.just(orderResponseDto)
        }
        fun buildOrderResponseDtoNullOperation(): Mono<OrderResponseDto> {
            val orderResponseDto = OrderResponseDto()
            return Mono.just(orderResponseDto)
        }
        fun buildEventsList(
            correlationId: String,
            gateway: TransactionAuthorizationRequestData.PaymentGateway
        ): List<TransactionEvent<Any>> {
            val transactionActivatedEvent =
                TransactionTestUtils.transactionActivateEvent(
                    NpgTransactionGatewayActivationData(orderId, correlationId)
                )
            val transactionAuthorizationRequestedEvent =
                TransactionTestUtils.transactionAuthorizationRequestedEvent(gateway)

            TransactionTestUtils.transactionAuthorizationRequestedEvent(gateway)

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
                    null // N.B.: Is null when getting error while retrieving authorization data
                    // from
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

            return (listOf(
                transactionActivatedEvent,
                transactionAuthorizationRequestedEvent,
                transactionExpiredEvent,
                transactionRefundRequestedEvent,
                transactionRefundErrorEvent,
                transactionRefundRetryEvent,
                transactionRefundedEvent
            )
                as List<TransactionEvent<Any>>)
        }

        fun buildEventsList(
            correlationId: String,
            transactionAuthorizationRequestedEvent: TransactionAuthorizationRequestedEvent
        ): List<TransactionEvent<Any>> {
            val transactionActivatedEvent =
                TransactionTestUtils.transactionActivateEvent(
                    NpgTransactionGatewayActivationData(orderId, correlationId)
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
                    null // N.B.: Is null when getting error while retrieving authorization data
                    // from
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

            return (listOf(
                transactionActivatedEvent,
                transactionAuthorizationRequestedEvent,
                transactionExpiredEvent,
                transactionRefundRequestedEvent,
                transactionRefundErrorEvent,
                transactionRefundRetryEvent,
                transactionRefundedEvent
            )
                as List<TransactionEvent<Any>>)
        }
    }
}
