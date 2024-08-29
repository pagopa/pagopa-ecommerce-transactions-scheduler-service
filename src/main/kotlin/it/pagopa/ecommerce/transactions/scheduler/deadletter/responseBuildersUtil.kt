package it.pagopa.ecommerce.transactions.scheduler.deadletter

import it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedData
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationCompletedData
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestData
import it.pagopa.ecommerce.commons.documents.v2.authorization.*
import it.pagopa.ecommerce.commons.documents.v2.refund.NpgGatewayRefundData
import it.pagopa.ecommerce.commons.domain.v2.TransactionWithClosureError
import it.pagopa.ecommerce.commons.domain.v2.pojos.*
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OperationResultDto
import it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto
import it.pagopa.ecommerce.commons.utils.v2.TransactionUtils.getTransactionFee
import it.pagopa.ecommerce.transactions.scheduler.models.common.GatewayAuthorizationData
import java.util.*

fun getTransactionFees(baseTransaction: BaseTransaction): Optional<Int> =
    when (baseTransaction) {
        is BaseTransactionExpired -> getTransactionFee(baseTransaction.transactionAtPreviousState)
        is BaseTransactionWithClosureError ->
            getTransactionFee(baseTransaction.transactionAtPreviousState)
        else -> getTransactionFee(baseTransaction)
    }

fun getTransactionActivatedData(
    baseTransaction: BaseTransaction
): TransactionActivatedData? =
    if (baseTransaction is BaseTransactionWithPaymentToken) {
        baseTransaction.transactionActivatedData
    } else {
        null
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


fun getGatewayAuthorizationData(
    transactionGatewayAuthorizationData: TransactionGatewayAuthorizationData?
): GatewayAuthorizationData? {
    return when (transactionGatewayAuthorizationData) {
        is NpgTransactionGatewayAuthorizationData ->
            GatewayAuthorizationData(
                transactionGatewayAuthorizationData.operationResult.value,
                transactionGatewayAuthorizationData.errorCode
            )
        is PgsTransactionGatewayAuthorizationData ->
            GatewayAuthorizationData(
                transactionGatewayAuthorizationData.authorizationResultDto.value,
                transactionGatewayAuthorizationData.errorCode
            )
        is RedirectTransactionGatewayAuthorizationData ->
            GatewayAuthorizationData(
                transactionGatewayAuthorizationData.outcome.name,
                transactionGatewayAuthorizationData.errorCode
            )
        else -> null
    }
}

fun getAuthorizationOperationId(baseTransaction: BaseTransaction): String? =
    when (baseTransaction) {
        is BaseTransactionExpired ->
            getAuthorizationOperationId(baseTransaction.transactionAtPreviousState)
        is BaseTransactionWithClosureError ->
            getAuthorizationOperationId(baseTransaction.transactionAtPreviousState)
        is BaseTransactionWithCompletedAuthorization -> {
            val gatewayAuthData =
                baseTransaction.transactionAuthorizationCompletedData
                    .transactionGatewayAuthorizationData
            when (gatewayAuthData) {
                is NpgTransactionGatewayAuthorizationData -> gatewayAuthData.operationId
                else -> null
            }
        }
        is BaseTransactionWithRefundRequested -> {
            val authorizationGatewayData =
                baseTransaction?.transactionAuthorizationGatewayData ?: 0

            when (authorizationGatewayData) {
                is NpgTransactionGatewayAuthorizationData -> authorizationGatewayData.operationId
                else -> null
            }
        }
        else -> null
    }

fun getRefundOperationId(baseTransaction: BaseTransaction): String? =
    when (baseTransaction) {
        is BaseTransactionRefunded -> {
            when (
                val gatewayOperationData =
                    baseTransaction.transactionRefundedData.gatewayOperationData
            ) {
                is NpgGatewayRefundData -> gatewayOperationData.operationId
                else -> null
            }
        }
        else -> null
    }

fun getTransactionDetailsStatus(baseTransaction: BaseTransaction): String =
    when (getAuthorizationOutcomeV2(baseTransaction)) {
        AuthorizationResultDto.OK -> "Confermato"
        AuthorizationResultDto.KO -> "Rifiutato"
        else -> "Cancellato"
    }


fun getAuthorizationOutcomeV2(baseTransaction: BaseTransaction): AuthorizationResultDto? =
    when (baseTransaction) {
        is BaseTransactionExpired ->
            getAuthorizationOutcomeV2(baseTransaction.transactionAtPreviousState)
        is BaseTransactionWithRefundRequested ->
            getAuthorizationOutcomeV2(baseTransaction.transactionAtPreviousState)
        is TransactionWithClosureError ->
            getAuthorizationOutcomeV2(baseTransaction.transactionAtPreviousState)
        is BaseTransactionWithCompletedAuthorization -> {
            val gatewayAuthData =
                baseTransaction.transactionAuthorizationCompletedData
                    .transactionGatewayAuthorizationData
            when (gatewayAuthData) {
                is PgsTransactionGatewayAuthorizationData -> gatewayAuthData.authorizationResultDto
                is NpgTransactionGatewayAuthorizationData ->
                    if (gatewayAuthData.operationResult == OperationResultDto.EXECUTED) {
                        AuthorizationResultDto.OK
                    } else {
                        AuthorizationResultDto.KO
                    }
                is RedirectTransactionGatewayAuthorizationData ->
                    if (
                        gatewayAuthData.outcome ==
                        RedirectTransactionGatewayAuthorizationData.Outcome.OK
                    ) {
                        AuthorizationResultDto.OK
                    } else {
                        AuthorizationResultDto.KO
                    }
                else -> null
            }
        }
        else -> null
    }

fun getStatusDetail(
    transactionGatewayAuthorizationData: TransactionGatewayAuthorizationData?
): String? =
    when (transactionGatewayAuthorizationData) {
        is PgsTransactionGatewayAuthorizationData -> transactionGatewayAuthorizationData.errorCode
        is NpgTransactionGatewayAuthorizationData -> null
        is RedirectTransactionGatewayAuthorizationData ->
            transactionGatewayAuthorizationData.errorCode
        else -> null
    }

fun getBrand(authorizationRequestedData: TransactionGatewayAuthorizationRequestedData?) =
    when (authorizationRequestedData) {
        is NpgTransactionGatewayAuthorizationRequestedData -> authorizationRequestedData.brand
        is PgsTransactionGatewayAuthorizationRequestedData ->
            authorizationRequestedData.brand?.toString()
        is RedirectTransactionGatewayAuthorizationRequestedData -> "N/A"
        else -> null
    }