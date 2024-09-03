package it.pagopa.ecommerce.transactions.scheduler.utils

import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OperationDto
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OperationResultDto
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OperationTypeDto
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OrderResponseDto
import reactor.core.publisher.Mono

class OrderResponseBuilder {
    companion object {
        fun buildOrderResponseDtoForNgpOrderNotAuthorized(): Mono<OrderResponseDto> {
            val orderResponseDto = OrderResponseDto()
            val operationDto = OperationDto()
            operationDto.operationId = "operationId"
            operationDto.operationType = OperationTypeDto.AUTHORIZATION
            operationDto.operationResult = OperationResultDto.FAILED
            orderResponseDto.operations = listOf(operationDto)
            return Mono.just(orderResponseDto)
        }
        fun buildOrderResponseDtoForNgpOrderAuthorized(): Mono<OrderResponseDto> {
            val orderResponseDto = OrderResponseDto()
            val operationDto = OperationDto()
            operationDto.operationId = "operationId"
            operationDto.operationType = OperationTypeDto.AUTHORIZATION
            operationDto.operationResult = OperationResultDto.EXECUTED
            orderResponseDto.operations = listOf(operationDto)
            return Mono.just(orderResponseDto)
        }
        fun buildOrderResponseDtoForNpgOrderVoid(): Mono<OrderResponseDto> {
            val orderResponseDto = OrderResponseDto()
            val operationDto = OperationDto()
            operationDto.operationId = "operationId"
            operationDto.operationType = OperationTypeDto.VOID
            operationDto.operationResult = OperationResultDto.VOIDED
            orderResponseDto.operations = listOf(operationDto)
            return Mono.just(orderResponseDto)
        }
        fun buildOrderResponseDtoForNpgOrderRefunded(): Mono<OrderResponseDto> {
            val orderResponseDto = OrderResponseDto()
            val operationDto = OperationDto()
            operationDto.operationId = "operationId"
            operationDto.operationType = OperationTypeDto.REFUND
            operationDto.operationResult = OperationResultDto.VOIDED
            orderResponseDto.operations = listOf(operationDto)
            return Mono.just(orderResponseDto)
        }
        fun buildOrderResponseDtoForNpgOrderRefundedFaulty(): Mono<OrderResponseDto> {
            val orderResponseDto = OrderResponseDto()
            val operationDto = OperationDto()
            operationDto.operationId = "operationId"
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
    }
}
