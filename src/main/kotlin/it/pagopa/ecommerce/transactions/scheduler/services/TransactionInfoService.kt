package it.pagopa.ecommerce.transactions.scheduler.services

import it.pagopa.ecommerce.commons.client.NpgClient
import it.pagopa.ecommerce.commons.client.NpgClient.PaymentMethod
import it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedData
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationCompletedData
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestData
import it.pagopa.ecommerce.commons.documents.v2.activation.NpgTransactionGatewayActivationData
import it.pagopa.ecommerce.commons.documents.v2.info.NpgTransactionInfoDetailsData
import it.pagopa.ecommerce.commons.documents.v2.info.RedirectTransactionInfoDetailsData
import it.pagopa.ecommerce.commons.documents.v2.info.TransactionInfo
import it.pagopa.ecommerce.commons.documents.v2.info.TransactionInfoDetailsData
import it.pagopa.ecommerce.commons.domain.TransactionId
import it.pagopa.ecommerce.commons.domain.v2.pojos.*
import it.pagopa.ecommerce.commons.exceptions.NpgResponseException
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OperationDto
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OperationResultDto
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OperationTypeDto
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OrderResponseDto
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
import it.pagopa.ecommerce.commons.utils.NpgApiKeyConfiguration
import it.pagopa.ecommerce.commons.utils.v2.TransactionUtils.getTransactionFee
import it.pagopa.ecommerce.transactions.scheduler.deadletter.CommonLogger
import it.pagopa.ecommerce.transactions.scheduler.repositories.TransactionsEventStoreRepository
import java.util.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

@Service
class TransactionInfoService(
    @Autowired private val transactionsEventStoreRepository: TransactionsEventStoreRepository<Any>,
    @Autowired private val npgClient: NpgClient,
    @Autowired private val npgApiKeyConfiguration: NpgApiKeyConfiguration
) {

    fun getTransactionInfoByTransactionId(transactionId: String): Mono<TransactionInfo> {
        val events =
            Mono.just(transactionId).flatMapMany {
                transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
                    transactionId
                )
            }

        return events
            .reduce(
                it.pagopa.ecommerce.commons.domain.v2.EmptyTransaction(),
                it.pagopa.ecommerce.commons.domain.v2.Transaction::applyEvent
            )
            .cast(it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction::class.java)
            .flatMap { baseTransaction -> events.collectList().map { baseTransaction } }
            .flatMap { baseTransaction ->
                getTransactionInfoDetails(baseTransaction).map { details ->
                    baseTransactionToTransactionInfoDto(baseTransaction, details)
                }
            }
    }

    fun getTransactionActivatedData(baseTransaction: BaseTransaction): TransactionActivatedData? =
        if (baseTransaction is BaseTransactionWithPaymentToken) {
            baseTransaction.transactionActivatedData
        } else {
            null
        }

    fun getTransactionFees(baseTransaction: BaseTransaction): Optional<Int> =
        when (baseTransaction) {
            is BaseTransactionExpired ->
                getTransactionFee(baseTransaction.transactionAtPreviousState)
            is BaseTransactionWithClosureError ->
                getTransactionFee(baseTransaction.transactionAtPreviousState)
            else -> getTransactionFee(baseTransaction)
        }

    fun getTransactionAuthRequestedData(
        baseTransaction: BaseTransaction
    ): TransactionAuthorizationRequestData? =
        when (baseTransaction) {
            is BaseTransactionExpired ->
                getTransactionAuthRequestedData(baseTransaction.transactionAtPreviousState)
            is BaseTransactionWithClosureError ->
                getTransactionAuthRequestedData(baseTransaction.transactionAtPreviousState)
            is BaseTransactionWithRequestedAuthorization ->
                baseTransaction.transactionAuthorizationRequestData
            else -> null
        }

    fun getTransactionAuthCompletedData(
        baseTransaction: BaseTransaction
    ): TransactionAuthorizationCompletedData? =
        when (baseTransaction) {
            is BaseTransactionExpired ->
                getTransactionAuthCompletedData(baseTransaction.transactionAtPreviousState)
            is BaseTransactionWithClosureError ->
                getTransactionAuthCompletedData(baseTransaction.transactionAtPreviousState)
            is BaseTransactionWithRefundRequested ->
                getTransactionAuthCompletedData(baseTransaction.transactionAtPreviousState)
            is BaseTransactionWithCompletedAuthorization ->
                baseTransaction.transactionAuthorizationCompletedData
            else -> null
        }

    fun baseTransactionToTransactionInfoDto(
        baseTransaction: BaseTransaction,
        details: TransactionInfoDetailsData
    ): TransactionInfo {

        val amount = baseTransaction.paymentNotices.sumOf { it.transactionAmount.value }
        val fee = getTransactionFees(baseTransaction).orElse(0)
        val grandTotal = amount.plus(fee)
        val transactionAuthorizationRequestData = getTransactionAuthRequestedData(baseTransaction)
        val transactionAuthorizationCompletedData = getTransactionAuthCompletedData(baseTransaction)

        val gateway = transactionAuthorizationRequestData?.paymentGateway

        return TransactionInfo(
            baseTransaction.transactionId.value(),
            transactionAuthorizationRequestData?.authorizationRequestId,
            TransactionStatusDto.valueOf(baseTransaction.status.toString()),
            gateway,
            baseTransaction.paymentNotices.map { it.paymentToken.value },
            transactionAuthorizationRequestData?.pspId,
            transactionAuthorizationRequestData?.paymentMethodName,
            grandTotal,
            transactionAuthorizationCompletedData?.rrn,
            details
        )
    }

    fun getTransactionInfoDetails(
        baseTransaction: BaseTransaction
    ): Mono<TransactionInfoDetailsData> {
        val transactionActivatedData = getTransactionActivatedData(baseTransaction)
        val transactionAuthorizationRequestData = getTransactionAuthRequestedData(baseTransaction)
        val correlationId =
            (transactionActivatedData?.transactionGatewayActivationData
                    as NpgTransactionGatewayActivationData)
                .correlationId
        // based on the type of payment I retrieve the gateway information
        CommonLogger.logger.info(
            "Retrive gateway info for transactionId: [{}],  gateway: [{}]",
            baseTransaction.transactionId,
            transactionAuthorizationRequestData?.paymentGateway
        )
        return when (transactionAuthorizationRequestData?.paymentGateway) {
            TransactionAuthorizationRequestData.PaymentGateway.NPG ->
                performGetOrderNPG(
                        transactionId = TransactionId(baseTransaction.transactionId.value()),
                        orderId = transactionAuthorizationRequestData.authorizationRequestId,
                        pspId = transactionAuthorizationRequestData.pspId,
                        correlationId = correlationId,
                        paymentMethod =
                            PaymentMethod.valueOf(
                                transactionAuthorizationRequestData.paymentMethodName
                            )
                    )
                    .doOnNext { order ->
                        CommonLogger.logger.info(
                            "Performed get order for transaction with id: [{}], last operation result: [{}], operations: [{}]",
                            baseTransaction.transactionId,
                            order.orderStatus?.lastOperationType,
                            order.operations?.joinToString {
                                "${it.operationType}-${it.operationResult}"
                            },
                        )
                    }
                    .flatMap { orderResponse ->
                        orderResponse.operations
                            ?.fold(
                                UnknownNpgOrderStatus(orderResponse) as NpgOrderStatus,
                                this::reduceOperations
                            )
                            ?.toMono()
                            ?.map {
                                if (it is NpgOrderRefunded) {
                                    it.copy(
                                        authorization =
                                            orderResponse?.operations?.find(IS_AUTHORIZED)
                                    )
                                } else {
                                    it
                                }
                            }
                            ?: Mono.error(
                                RuntimeException(
                                    "No operations found for transaction with id: ${baseTransaction.transactionId.value()}"
                                )
                            )
                    }
                    .handle { it, sink ->
                        when {
                            it is NgpOrderAuthorized ->
                                sink.next(
                                    NpgTransactionInfoDetailsData(
                                        it.authorization.operationResult,
                                        it.authorization.operationId,
                                        UUID.fromString(correlationId)
                                    )
                                )
                            it is NgpOrderNotAuthorized ->
                                sink.next(
                                    NpgTransactionInfoDetailsData(
                                        it.operation.operationResult,
                                        it.operation.operationId,
                                        UUID.fromString(correlationId)
                                    )
                                )
                            it is NpgOrderRefunded ->
                                sink.next(
                                    NpgTransactionInfoDetailsData(
                                        it.refundOperation.operationResult,
                                        it.refundOperation.operationId,
                                        UUID.fromString(correlationId)
                                    )
                                )
                            else ->
                                sink.error(
                                    RuntimeException(
                                        "Invalid operation retrived from gateway for transaction: ${baseTransaction.transactionId.value()}"
                                    )
                                )
                        }
                    }
            TransactionAuthorizationRequestData.PaymentGateway.REDIRECT ->
                Mono.just(RedirectTransactionInfoDetailsData(""))
            else ->
                Mono.error(
                    NullPointerException(
                        "Transaction with id: ${baseTransaction.transactionId} has invalid gateway"
                    )
                )
        }
    }

    private fun performGetOrderNPG(
        transactionId: TransactionId,
        orderId: String,
        pspId: String,
        correlationId: String,
        paymentMethod: PaymentMethod
    ): Mono<OrderResponseDto> {
        CommonLogger.logger.info(
            "Performing get order for transaction with id: [{}], orderId [{}], pspId: [{}], correlationId: [{}], paymentMethod: [{}]",
            transactionId.value(),
            orderId,
            pspId,
            correlationId,
            paymentMethod.serviceName,
        )
        return npgApiKeyConfiguration[paymentMethod, pspId].fold(
            { ex -> Mono.error(ex) },
            { apiKey ->
                npgClient.getOrder(UUID.fromString(correlationId), apiKey, orderId).onErrorMap(
                    NpgResponseException::class.java
                ) { exception: NpgResponseException ->
                    val responseStatusCode = exception.statusCode
                    responseStatusCode
                        .map {
                            val errorCodeReason = "Received HTTP error code from NPG: $it"
                            if (it.is5xxServerError) {
                                RuntimeException("Bad gateway : $errorCodeReason")
                            } else {
                                RuntimeException(
                                    "Transaction with id ${transactionId.value()} npg state cannot be retrieved. Reason: $errorCodeReason"
                                )
                            }
                        }
                        .orElse(exception)
                }
            }
        )
    }

    private fun reduceOperations(
        orderState: NpgOrderStatus,
        operation: OperationDto
    ): NpgOrderStatus =
        when {
            operation.operationType == OperationTypeDto.AUTHORIZATION &&
                operation.operationResult != OperationResultDto.EXECUTED &&
                orderState !is NpgOrderRefunded &&
                orderState !is NgpOrderAuthorized -> NgpOrderNotAuthorized(operation)
            IS_AUTHORIZED(operation) && orderState !is NpgOrderRefunded ->
                NgpOrderAuthorized(operation)
            IS_REFUNDED(operation) -> NpgOrderRefunded(operation)
            else -> orderState
        }
}