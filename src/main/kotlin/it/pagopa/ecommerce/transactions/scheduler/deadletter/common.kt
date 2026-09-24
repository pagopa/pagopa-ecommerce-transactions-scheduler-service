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
            .details(mapOf("event" to eventData))
            .logDebug(CommonLogger.logger, "Read event from queue")
    }

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
                LogTracingUtils.loggerTracingUtils()
                    .failure()
                    .logErrorWithStackTrace(
                        CommonLogger.logger,
                        exception,
                        "Exception processing event info"
                    )
                Mono.just(DeadLetterTransactionInfo())
            }

    return checkPointer
        .success()
        .doOnSuccess {
            LogTracingUtils.loggerTracingUtils()
                .success()
                .logInfo(CommonLogger.logger, "Event checkpoint performed successfully")
        }
        .doOnError { exception ->
            LogTracingUtils.loggerTracingUtils()
                .failure()
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
                .details(mapOf("inserted_event" to it.id))
                .dependency(LogTracingUtils.MONGO_DEPENDENCY)
                .logInfo(CommonLogger.logger, "Event inserted successfully")
        }
        .then()
        .onErrorResume {
            LogTracingUtils.loggerTracingUtils()
                .failure()
                .logErrorWithStackTrace(
                    CommonLogger.logger,
                    it,
                    "Exception processing dead letter event, performing checkpoint failure"
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
}
