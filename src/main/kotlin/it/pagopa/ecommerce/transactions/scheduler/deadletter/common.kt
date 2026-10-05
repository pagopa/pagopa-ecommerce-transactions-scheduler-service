package it.pagopa.ecommerce.transactions.scheduler.deadletter

import com.azure.core.util.BinaryData
import com.azure.core.util.serializer.TypeReference
import com.azure.spring.messaging.checkpoint.Checkpointer
import it.pagopa.ecommerce.commons.documents.DeadLetterEvent
import it.pagopa.ecommerce.commons.documents.v2.TransactionEvent
import it.pagopa.ecommerce.commons.documents.v2.deadletter.DeadLetterTransactionInfo
import it.pagopa.ecommerce.commons.mdcutilities.LogTracingUtils
import it.pagopa.ecommerce.commons.queues.QueueEvent
import it.pagopa.ecommerce.commons.queues.StrictJsonSerializerProvider
import it.pagopa.ecommerce.transactions.scheduler.repositories.ecommerce.DeadLetterEventRepository
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
    if (CommonLogger.logger.isDebugEnabled) {
        LogTracingUtils.loggerTracingUtils()
            .success()
            .dependency(LogTracingUtils.STORAGE_QUEUE_DEPENDENCY)
            .logDebug(CommonLogger.logger, "Read event from queue")
    }

    val decodedEvent: Mono<TransactionEvent<Void>> =
        BinaryData.fromBytes(payload)
            .toObjectAsync(
                object : TypeReference<QueueEvent<TransactionEvent<Void>>>() {},
                strictSerializerProviderV2.createInstance()
            )
            .map { it.event }
            .cache()

    // decode or lookup failures must not prevent the event from being archived
    val transactionInfo =
        decodedEvent
            .flatMap { transactionInfoService.getTransactionInfoByTransactionId(it.transactionId) }
            .onErrorResume { exception ->
                LogTracingUtils.loggerTracingUtils()
                    .failure()
                    .logErrorWithStackTrace(
                        CommonLogger.logger,
                        exception,
                        "Error retrieving transaction info for dead letter event"
                    )
                Mono.empty()
            }
            .defaultIfEmpty(DeadLetterTransactionInfo())

    val deadLetterProcessing =
        checkPointer
            .success()
            .doOnSuccess {
                LogTracingUtils.loggerTracingUtils()
                    .success()
                    .dependency(LogTracingUtils.STORAGE_QUEUE_DEPENDENCY)
                    .logInfo(CommonLogger.logger, "Event checkpoint performed successfully")
            }
            .doOnError { exception ->
                LogTracingUtils.loggerTracingUtils()
                    .failure()
                    .dependency(LogTracingUtils.STORAGE_QUEUE_DEPENDENCY)
                    .logErrorWithStackTrace(
                        CommonLogger.logger,
                        exception,
                        "Error performing checkpoint for read event"
                    )
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
            .flatMap { deadLetterEventRepository.insert(it) }
            .doOnNext {
                LogTracingUtils.loggerTracingUtils()
                    .success()
                    .details(
                        mapOf(
                            "event_id" to it.id,
                            "transaction_info" to it.transactionInfo.toString()
                        )
                    )
                    .dependency(LogTracingUtils.MONGO_DEPENDENCY)
                    .logInfo(CommonLogger.logger, "Event inserted successfully")
            }
            .then()
            .onErrorResume {
                LogTracingUtils.loggerTracingUtils()
                    .failure()
                    .dependency(LogTracingUtils.STORAGE_QUEUE_DEPENDENCY)
                    .logErrorWithStackTrace(
                        CommonLogger.logger,
                        it,
                        "Exception processing dead letter event"
                    )
                checkPointer
                    .failure()
                    .doOnSuccess {
                        LogTracingUtils.loggerTracingUtils()
                            .success()
                            .logInfo(
                                CommonLogger.logger,
                                "Event checkpoint failure performed successfully"
                            )
                    }
                    .doOnError { exception ->
                        LogTracingUtils.loggerTracingUtils()
                            .failure()
                            .logErrorWithStackTrace(
                                CommonLogger.logger,
                                exception,
                                "Error performing checkpoint failure"
                            )
                    }
            }
            .then(mono {})

    return decodedEvent
        // already logged by transactionInfo: fall back to processing without event context
        .onErrorResume { Mono.empty() }
        .flatMap { event ->
            deadLetterProcessing.contextWrite { context ->
                LogTracingUtils.enrichContextForEvent(
                    mapOf(
                        LogTracingUtils.AttributeKeys.CTX_TRANSACTION_ID to event.transactionId,
                        LogTracingUtils.AttributeKeys.CTX_EVENT_CODE to event.eventCode,
                        LogTracingUtils.AttributeKeys.CTX_EVENT_ID to event.id,
                    ),
                    context
                )
            }
        }
        .switchIfEmpty(deadLetterProcessing)
        .contextWrite { context ->
            LogTracingUtils.enrichContextForEvent(
                mapOf(LogTracingUtils.AttributeKeys.EVENT_ACTION to "DEAD_LETTER_EVENT_PROCESSING"),
                context
            )
        }
}
