package it.pagopa.ecommerce.transactions.scheduler.deadletter

import com.azure.core.util.serializer.JsonSerializerProviders
import com.azure.core.util.serializer.TypeReference
import com.azure.spring.messaging.checkpoint.Checkpointer
import com.fasterxml.jackson.databind.JsonNode
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent
import it.pagopa.ecommerce.commons.documents.DeadLetterEvent
import it.pagopa.ecommerce.commons.documents.v2.activation.NpgTransactionGatewayActivationData
import it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
import it.pagopa.ecommerce.transactions.scheduler.repositories.DeadLetterEventRepository
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.*
import kotlinx.coroutines.reactor.mono
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono

data class GatewayAuthorizationData(val authorizationStatus: String, val errorCode: String?)

object CommonLogger {
    val logger: Logger = LoggerFactory.getLogger(CommonLogger::class.java)
}

fun baseTransactionToTransactionInfoDto(
    baseTransaction: BaseTransaction
): Int {

    val amount = baseTransaction.paymentNotices.sumOf { it.transactionAmount.value }
    val fee = getTransactionFees(baseTransaction).orElse(0)
    val totalAmount = amount.plus(fee)
    val transactionActivatedData = getTransactionActivatedData(baseTransaction)
    val transactionGatewayActivationData =
        transactionActivatedData?.transactionGatewayActivationData
    val transactionAuthorizationRequestData = getTransactionAuthRequestedData(baseTransaction)
    val transactionAuthorizationCompletedData = getTransactionAuthCompletedData(baseTransaction)

    val transactionId = baseTransaction.transactionId.value()
    val authorizationRequestId = transactionAuthorizationRequestData?.authorizationRequestId
    val eCommerceStatus = TransactionStatusDto.valueOf(baseTransaction.status.toString())
    val gateway = transactionAuthorizationRequestData?.paymentGateway?.toString()
    val paymentToken = baseTransaction.paymentNotices.map { it.paymentToken.value }
    val pspID = transactionAuthorizationRequestData?.pspId
    val npgCorrelationId =
        if (transactionGatewayActivationData is NpgTransactionGatewayActivationData)
            UUID.fromString(transactionGatewayActivationData.correlationId)
        else null
    val paymentMethodName = transactionAuthorizationRequestData?.paymentMethodName
    val grandTotal = totalAmount
    val rrn = transactionAuthorizationCompletedData?.rrn
    // "npgOperationId":"xx",
    // "npgOperationResult":"xxx"

    /*val transactionInfoForDeadLetter = TransactionInfo(
    "",
    "",
    it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSED,
    TransactionInfo.Gateway.NPG,
    "",
    "",
    "",
    BigDecimal(1),
    "",
    NpgTransactionInfoDetailsData(OperationResultDto.EXECUTED, "",UUID.randomUUID()))*/

    return 0
}

fun writeEventToDeadLetterCollection(
    payload: ByteArray,
    queueName: String,
    checkPointer: Checkpointer,
    deadLetterEventRepository: DeadLetterEventRepository,
    transactionInfoBuilder: TransactionInfoBuilder
): Mono<Unit> {
    val eventData = payload.toString(StandardCharsets.UTF_8)

    // extract transaction Id
    val eventDataAsInputStream = ByteArrayInputStream(payload)
    val jsonSerializer = JsonSerializerProviders.createInstance()
    val jsonNode =
        jsonSerializer.deserialize(eventDataAsInputStream, object : TypeReference<JsonNode>() {})
    val transactionId = jsonNode["transactionId"].asText()

    // recover here info on event based on transactionId
    val transactionInfo = transactionInfoBuilder.getTransactionInfoByTransactionId(transactionId)

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
