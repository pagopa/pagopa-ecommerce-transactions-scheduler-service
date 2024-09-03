package it.pagopa.ecommerce.transactions.scheduler.services

import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OperationDto
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OperationResultDto
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OperationTypeDto
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OrderResponseDto

// TODO move to commons?
sealed interface NpgOrderStatus

data class UnknownNpgOrderStatus(val order: OrderResponseDto) : NpgOrderStatus

data class NgpOrderAuthorized(
    val authorization: OperationDto,
) : NpgOrderStatus

data class NpgOrderRefunded(
    val refundOperation: OperationDto,
    val authorization: OperationDto? = null
) : NpgOrderStatus

data class NgpOrderNotAuthorized(
    val operation: OperationDto,
) : NpgOrderStatus

val IS_AUTHORIZED: (OperationDto) -> Boolean = {
    it.operationType == OperationTypeDto.AUTHORIZATION &&
        it.operationResult == OperationResultDto.EXECUTED
}
val IS_REFUNDED: (OperationDto) -> Boolean = {
    it.operationType == OperationTypeDto.REFUND && it.operationResult == OperationResultDto.VOIDED
}
