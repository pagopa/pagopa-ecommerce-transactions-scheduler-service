package it.pagopa.ecommerce.transactions.scheduler.services

import it.pagopa.ecommerce.commons.client.NpgClient
import it.pagopa.ecommerce.commons.client.NpgClient.PaymentMethod
import it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedData
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationCompletedData
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestData
import it.pagopa.ecommerce.commons.documents.v2.activation.NpgTransactionGatewayActivationData
import it.pagopa.ecommerce.commons.documents.v2.deadletter.DeadLetterNpgTransactionInfoDetailsData
import it.pagopa.ecommerce.commons.documents.v2.deadletter.DeadLetterRedirectTransactionInfoDetailsData
import it.pagopa.ecommerce.commons.documents.v2.deadletter.DeadLetterTransactionInfo
import it.pagopa.ecommerce.commons.documents.v2.deadletter.DeadLetterTransactionInfoDetailsData
import it.pagopa.ecommerce.commons.domain.v2.TransactionId
import it.pagopa.ecommerce.commons.domain.v2.pojos.*
import it.pagopa.ecommerce.commons.exceptions.NpgResponseException
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OperationDto
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OperationResultDto
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OperationTypeDto
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OrderResponseDto
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
import it.pagopa.ecommerce.commons.mdcutilities.LogTracingUtils
import it.pagopa.ecommerce.commons.utils.NpgApiKeyConfiguration
import it.pagopa.ecommerce.commons.utils.NpgClientUtils
import it.pagopa.ecommerce.commons.utils.v2.TransactionUtils.getTransactionFee
import it.pagopa.ecommerce.transactions.scheduler.deadletter.CommonLogger
import it.pagopa.ecommerce.transactions.scheduler.exceptions.*
import it.pagopa.ecommerce.transactions.scheduler.repositories.ecommerce.TransactionsEventStoreRepository
import it.pagopa.ecommerce.transactions.scheduler.utils.*
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

    fun getTransactionInfoByTransactionId(transactionId: String): Mono<DeadLetterTransactionInfo> {
        val events =
            Mono.just(transactionId)
                .flatMapMany {
                    transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
                        transactionId
                    )
                }
                .doOnNext {
                    LogTracingUtils.loggerTracingUtils()
                        .success()
                        .dependency(LogTracingUtils.MONGO_DEPENDENCY)
                        .logInfo(CommonLogger.logger, "Transaction info retrieved successfully")
                }
                .cache()

        return events
            .reduce(
                it.pagopa.ecommerce.commons.domain.v2.EmptyTransaction(),
                it.pagopa.ecommerce.commons.domain.v2.Transaction::applyEvent
            )
            .cast(BaseTransaction::class.java)
            .flatMap { baseTransaction -> events.collectList().map { baseTransaction } }
            .flatMap { baseTransaction ->
                getTransactionInfoDetails(baseTransaction)
                    .flatMap { details ->
                        Mono.just(baseTransactionToTransactionInfoDto(baseTransaction, details))
                    }
                    .doOnError { exception ->
                        LogTracingUtils.loggerTracingUtils()
                            .failure()
                            .logErrorWithStackTrace(
                                CommonLogger.logger,
                                exception,
                                "Error performing get transactionInfoDetails"
                            )
                    }
                    .onErrorResume {
                        Mono.just(
                            baseTransactionToTransactionInfoDto(
                                baseTransaction,
                                DeadLetterNpgTransactionInfoDetailsData()
                            )
                        )
                    }
                    .contextWrite { context ->
                        LogTracingUtils.enrichContextForEvent(
                            mapOf(
                                LogTracingUtils.AttributeKeys.CTX_TRANSACTION_ID to transactionId
                            ),
                            context
                        )
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
        details: DeadLetterTransactionInfoDetailsData
    ): DeadLetterTransactionInfo {

        val amount = baseTransaction.paymentNotices.sumOf { it.transactionAmount.value }
        val fee = getTransactionFees(baseTransaction).orElse(0)
        val grandTotal = amount.plus(fee)

        val transactionAuthorizationRequestData = getTransactionAuthRequestedData(baseTransaction)
        val transactionAuthorizationCompletedData = getTransactionAuthCompletedData(baseTransaction)

        val gateway = transactionAuthorizationRequestData?.paymentGateway

        return DeadLetterTransactionInfo(
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
    ): Mono<DeadLetterTransactionInfoDetailsData> {
        val transactionActivatedData = getTransactionActivatedData(baseTransaction)
        val transactionAuthorizationRequestData = getTransactionAuthRequestedData(baseTransaction)
        val gatewayActivatedData = transactionActivatedData?.transactionGatewayActivationData
        val correlationId =
            if (gatewayActivatedData is NpgTransactionGatewayActivationData) {
                gatewayActivatedData.correlationId
            } else {
                null
            }
        // based on the type of payment I retrieve the gateway information
        LogTracingUtils.loggerTracingUtils()
            .success()
            .details(
                mapOf("gateway" to transactionAuthorizationRequestData?.paymentGateway.toString())
            )
            .logInfo(CommonLogger.logger, "Retrieve gateway info")
        return when (transactionAuthorizationRequestData?.paymentGateway) {
            TransactionAuthorizationRequestData.PaymentGateway.NPG ->
                performGetOrderNPG(
                        transactionId = TransactionId(baseTransaction.transactionId.value()),
                        orderId = transactionAuthorizationRequestData.authorizationRequestId,
                        pspId = transactionAuthorizationRequestData.pspId,
                        correlationId = correlationId!!,
                        paymentMethod =
                            PaymentMethod.fromMethodTypeCode(
                                transactionAuthorizationRequestData.paymentTypeCode
                            )
                    )
                    .doOnNext { order ->
                        LogTracingUtils.loggerTracingUtils()
                            .success()
                            .dependency(LogTracingUtils.NPG_DEPENDENCY)
                            .details(
                                mapOf(
                                    "last_operation_result" to
                                        order.orderStatus?.lastOperationType.toString(),
                                    "operations" to
                                        order.operations
                                            ?.joinToString {
                                                it.operationType.toString() +
                                                    "-" +
                                                    it.operationResult.toString()
                                            }
                                            .toString()
                                )
                            )
                            .logInfo(CommonLogger.logger, "Performed get order successfully")
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
                                NoOperationFoundException(baseTransaction.transactionId.value())
                            )
                    }
                    .handle { it, sink ->
                        when (it) {
                            is NgpOrderAuthorized ->
                                sink.next(
                                    DeadLetterNpgTransactionInfoDetailsData(
                                        it.authorization.operationResult,
                                        it.authorization.operationId,
                                        correlationId,
                                        NpgClientUtils.getPaymentEndToEndId(it.authorization)
                                    )
                                )
                            is NgpOrderNotAuthorized ->
                                sink.next(
                                    DeadLetterNpgTransactionInfoDetailsData(
                                        it.operation.operationResult,
                                        it.operation.operationId,
                                        correlationId,
                                        NpgClientUtils.getPaymentEndToEndId(it.operation)
                                    )
                                )
                            is NpgOrderRefunded ->
                                sink.next(
                                    DeadLetterNpgTransactionInfoDetailsData(
                                        it.refundOperation.operationResult,
                                        it.refundOperation.operationId,
                                        correlationId,
                                        NpgClientUtils.getPaymentEndToEndId(it.refundOperation)
                                    )
                                )
                            else ->
                                sink.error(
                                    InvalidNpgOrderException(baseTransaction.transactionId.value())
                                )
                        }
                    }
            TransactionAuthorizationRequestData.PaymentGateway.REDIRECT ->
                Mono.just(DeadLetterRedirectTransactionInfoDetailsData(""))
            else -> Mono.error(InvalidGatewayException(baseTransaction.transactionId.value()))
        }
    }

    private fun performGetOrderNPG(
        transactionId: TransactionId,
        orderId: String,
        pspId: String,
        correlationId: String,
        paymentMethod: PaymentMethod
    ): Mono<OrderResponseDto> {
        LogTracingUtils.loggerTracingUtils()
            .success()
            .details(
                mapOf(
                    "order_id" to orderId,
                    "psp_id" to pspId,
                    "correlation_id" to correlationId,
                    "payment_method" to paymentMethod.serviceName
                )
            )
            .logInfo(CommonLogger.logger, "Performing get order")
        return npgApiKeyConfiguration[paymentMethod, pspId].fold(
            { ex -> Mono.error(ex) },
            { apiKey ->
                npgClient.getOrder(UUID.fromString(correlationId), apiKey, orderId).onErrorMap(
                    NpgResponseException::class.java
                ) { exception: NpgResponseException ->
                    val responseStatusCode = exception.statusCode
                    responseStatusCode
                        .map {
                            if (it.is5xxServerError) {
                                NpgBadGatewayException("$it")
                            } else {
                                NpgBadRequestException(transactionId.value(), "$it")
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
