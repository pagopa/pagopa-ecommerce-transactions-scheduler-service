package it.pagopa.ecommerce.transactions.scheduler.deadletter

import com.azure.core.util.BinaryData
import com.azure.core.util.serializer.TypeReference
import com.azure.spring.messaging.checkpoint.Checkpointer
import it.pagopa.ecommerce.commons.documents.DeadLetterEvent
import it.pagopa.ecommerce.commons.documents.v2.TransactionEvent
import it.pagopa.ecommerce.commons.documents.v2.deadletter.DeadLetterTransactionInfo
import it.pagopa.ecommerce.commons.queues.QueueEvent
import it.pagopa.ecommerce.commons.queues.StrictJsonSerializerProvider
import it.pagopa.ecommerce.transactions.scheduler.repositories.DeadLetterEventRepository
import it.pagopa.ecommerce.transactions.scheduler.services.TransactionInfoService
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.*
import kotlinx.coroutines.reactor.mono
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono

object CommonLogger {
    val logger: Logger = LoggerFactory.getLogger(CommonLogger::class.java)
}

fun writeEventToDeadLetterCollection(
    payload: ByteArray,
    queueName: String,
    checkPointer: Checkpointer,
    deadLetterEventRepository: DeadLetterEventRepository,
    transactionInfoService: TransactionInfoService,
    strictSerializerProviderV2: StrictJsonSerializerProvider
): Mono<Unit> {

    val eventData = payload.toString(StandardCharsets.UTF_8)
    CommonLogger.logger.debug("Read event from queue: {}", eventData)

    val transactionInfo =
        BinaryData.fromBytes(payload)
            .toObjectAsync(
                object : TypeReference<QueueEvent<TransactionEvent<Void>>>() {},
                strictSerializerProviderV2.createInstance()
            )
            .flatMap {
                transactionInfoService.getTransactionInfoByTransactionId(it.event.transactionId)
            }
            .onErrorResume { exception ->
                CommonLogger.logger.error("Error processing event info", exception)
                Mono.just(DeadLetterTransactionInfo())
            }

    return checkPointer
        .success()
        .doOnSuccess { CommonLogger.logger.info("Event checkpoint performed successfully") }
        .doOnError { exception ->
            CommonLogger.logger.error("Error performing checkpoint for read event", exception)
        }
        .then(
            transactionInfo.map { info ->
                DeadLetterEvent(
                    UUID.randomUUID().toString(),
                    queueName,
                    OffsetDateTime.now().toString(),
                    eventData,
                    info
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
