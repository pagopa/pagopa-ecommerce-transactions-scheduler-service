package it.pagopa.ecommerce.transactions.scheduler.deadletter

import com.azure.spring.messaging.checkpoint.Checkpointer
import it.pagopa.ecommerce.commons.documents.DeadLetterEvent
import it.pagopa.ecommerce.transactions.scheduler.repositories.DeadLetterEventRepository
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.*
import kotlinx.coroutines.reactor.mono
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import com.azure.core.util.serializer.JsonSerializerProviders
import com.azure.core.util.serializer.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent
import it.pagopa.ecommerce.commons.documents.v2.activation.NpgTransactionGatewayActivationData
import it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction
import it.pagopa.ecommerce.transactions.scheduler.models.dto.*
import java.io.ByteArrayInputStream
import java.time.ZonedDateTime

object CommonLogger {
    val logger: Logger = LoggerFactory.getLogger(CommonLogger::class.java)
}

fun baseTransactionToTransactionInfoDto(
    baseTransaction: BaseTransaction,
    events: List<BaseTransactionEvent<Any>>
): TransactionResultDto {
    val amount = baseTransaction.paymentNotices.sumOf { it.transactionAmount.value }
    val fee = getTransactionFees(baseTransaction).orElse(0)
    val totalAmount = amount.plus(fee)
    val transactionActivatedData = getTransactionActivatedData(baseTransaction)
    val transactionGatewayActivationData =
        transactionActivatedData?.transactionGatewayActivationData
    val transactionAuthorizationRequestData = getTransactionAuthRequestedData(baseTransaction)
    val transactionAuthorizationCompletedData = getTransactionAuthCompletedData(baseTransaction)
    val gatewayAuthorizationData =
        getGatewayAuthorizationData(
            transactionAuthorizationCompletedData?.transactionGatewayAuthorizationData
        )
    val authorizationOperationId = getAuthorizationOperationId(baseTransaction)
    val refundOperationId = getRefundOperationId(baseTransaction)

    val eventInfoList =
        events.map { event ->
            EventInfoDto()
                .creationDate(ZonedDateTime.parse(event.creationDate).toOffsetDateTime())
                .eventCode(event.eventCode)
        }

    // build transaction info
    val transactionInfo =
        TransactionInfoDto()
            .creationDate(baseTransaction.creationDate.toOffsetDateTime())
            .status(getTransactionDetailsStatus(baseTransaction))
            .statusDetails(
                getStatusDetail(
                    transactionAuthorizationCompletedData?.transactionGatewayAuthorizationData
                )
            )
            .events(eventInfoList)
            .eventStatus(TransactionStatusDto.valueOf(baseTransaction.status.toString()))
            .amount(amount)
            .fee(fee)
            .grandTotal(totalAmount)
            .rrn(transactionAuthorizationCompletedData?.rrn)
            .authorizationCode(transactionAuthorizationCompletedData?.authorizationCode)
            .authorizationOperationId(authorizationOperationId)
            .refundOperationId(refundOperationId)
            .paymentMethodName(transactionAuthorizationRequestData?.paymentMethodName)
            .brand(
                getBrand(
                    transactionAuthorizationRequestData
                        ?.transactionGatewayAuthorizationRequestedData
                )
            )
            .authorizationRequestId(transactionAuthorizationRequestData?.authorizationRequestId)
            .paymentGateway(transactionAuthorizationRequestData?.paymentGateway?.toString())
            .correlationId(
                if (transactionGatewayActivationData is NpgTransactionGatewayActivationData)
                    UUID.fromString(transactionGatewayActivationData.correlationId)
                else null
            )
            .gatewayAuthorizationStatus(gatewayAuthorizationData?.authorizationStatus)
            .gatewayErrorCode(gatewayAuthorizationData?.errorCode)
    // build payment info
    val paymentInfo =
        PaymentInfoDto()
            .origin(baseTransaction.clientId.toString())
            .idTransaction(baseTransaction.transactionId.value())
            .details(
                baseTransaction.paymentNotices.map {
                    PaymentDetailInfoDto()
                        .subject(it.transactionDescription.value)
                        .rptId(it.rptId.value)
                        .amount(it.transactionAmount.value)
                        .paymentToken(it.paymentToken.value)
                        // TODO here set only the first into transferList or take it from rptId
                        // object?
                        .paFiscalCode(it.transferList[0].paFiscalCode)
                        .creditorInstitution(it.companyName.value)
                }
            )
    // build psp info
    val pspInfo =
        PspInfoDto()
            .pspId(transactionAuthorizationRequestData?.pspId)
            .idChannel(transactionAuthorizationRequestData?.pspChannelCode)
            .businessName(transactionAuthorizationRequestData?.pspBusinessName)
    return TransactionResultDto()
        .product(ProductDto.ECOMMERCE)
        .transactionInfo(transactionInfo)
        .paymentInfo(paymentInfo)
        .pspInfo(pspInfo)
}


fun writeEventToDeadLetterCollection(
    payload: ByteArray,
    queueName: String,
    checkPointer: Checkpointer,
    deadLetterEventRepository: DeadLetterEventRepository
): Mono<Unit> {
    val eventData = payload.toString(StandardCharsets.UTF_8)

    // extract transaction Id
    val eventDataAsInputStream = ByteArrayInputStream(payload)
    val jsonSerializer = JsonSerializerProviders.createInstance()
    val jsonNode = jsonSerializer.deserialize(eventDataAsInputStream, object : TypeReference<JsonNode>() {})
    val transactionId = jsonNode["transactionId"].asText()

    // recover here info on event based on transactionId
    val transactionInfo = ""//TransactionInfoBuilder().getTransactionInfoByTransactionId(transactionId);

    CommonLogger.logger.debug("Read event from queue: {}", eventData)
    return checkPointer
        .success()
        .doOnSuccess { CommonLogger.logger.info("Event checkpoint performed successfully") }
        .doOnError { exception ->
            CommonLogger.logger.error("Error performing checkpoint for read event", exception)
        }
        .then(
            mono {
                DeadLetterEvent(
                    UUID.randomUUID().toString(),
                    queueName,
                    OffsetDateTime.now().toString(),
                    eventData
                )
            }
        )
        .flatMap { deadLetterEventRepository.save(it) }
        .then()
        .onErrorResume {
            CommonLogger.logger.error(
                "Exception processing dead letter event, performing checkpoint failure",
                it
            )
            checkPointer
                .failure()
                .doOnSuccess {
                    CommonLogger.logger.info("Event checkpoint failure performed successfully")
                }
                .doOnError { exception ->
                    CommonLogger.logger.error("Error performing checkpoint failure", exception)
                }
        }
        .then(mono {})
}
