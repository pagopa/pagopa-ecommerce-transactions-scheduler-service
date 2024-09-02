package it.pagopa.ecommerce.transactions.scheduler.deadletter

import it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedData
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationCompletedData
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestData
import it.pagopa.ecommerce.commons.documents.v2.activation.NpgTransactionGatewayActivationData
import it.pagopa.ecommerce.commons.documents.v2.info.NpgTransactionInfoDetailsData
import it.pagopa.ecommerce.commons.documents.v2.info.TransactionInfo
import it.pagopa.ecommerce.commons.domain.v2.pojos.*
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OperationResultDto
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
import it.pagopa.ecommerce.commons.utils.v2.TransactionUtils.getTransactionFee
import it.pagopa.ecommerce.transactions.scheduler.repositories.TransactionsEventStoreRepository
import java.util.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

@Service
class TransactionInfoBuilder(
    @Autowired private val transactionsEventStoreRepository: TransactionsEventStoreRepository<Any>
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
            .map { baseTransaction -> baseTransactionToTransactionInfoDto(baseTransaction) }
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
        is BaseTransactionExpired -> getTransactionFee(baseTransaction.transactionAtPreviousState)
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

fun baseTransactionToTransactionInfoDto(baseTransaction: BaseTransaction): TransactionInfo {

    val amount = baseTransaction.paymentNotices.sumOf { it.transactionAmount.value }
    val fee = getTransactionFees(baseTransaction).orElse(0)
    val grandTotal = amount.plus(fee)
    val transactionActivatedData = getTransactionActivatedData(baseTransaction)
    val transactionGatewayActivationData =
        transactionActivatedData?.transactionGatewayActivationData
    val transactionAuthorizationRequestData = getTransactionAuthRequestedData(baseTransaction)
    val transactionAuthorizationCompletedData = getTransactionAuthCompletedData(baseTransaction)

    val gateway = transactionAuthorizationRequestData?.paymentGateway
    val npgCorrelationId =
        if (transactionGatewayActivationData is NpgTransactionGatewayActivationData)
            UUID.fromString(transactionGatewayActivationData.correlationId)
        else null

    val details =
        when (gateway) {
            TransactionAuthorizationRequestData.PaymentGateway.NPG ->
                NpgTransactionInfoDetailsData(OperationResultDto.EXECUTED, "test", npgCorrelationId)
            // TransactionAuthorizationRequestData.PaymentGateway.REDIRECT -> println("x is 1")
            else -> null
        }

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
