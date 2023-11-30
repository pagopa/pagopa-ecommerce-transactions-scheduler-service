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

object CommonLogger {
    val logger: Logger = LoggerFactory.getLogger(CommonLogger::class.java)
}

fun writeEventToDeadLetterCollection(
    payload: ByteArray,
    queueName: String,
    checkPointer: Checkpointer,
    deadLetterEventRepository: DeadLetterEventRepository
): Mono<Unit> {
    val eventData = payload.toString(StandardCharsets.UTF_8)
    CommonLogger.logger.info("Read event from queue: {}", eventData)
    return Mono.just(checkPointer.success())
        .then(mono { eventData })
        .map {
            DeadLetterEvent(
                UUID.randomUUID().toString(),
                queueName,
                OffsetDateTime.now().toString(),
                it
            )
        }
        .flatMap {
            deadLetterEventRepository
                .save(it)
                .then(
                    checkPointer
                        .success()
                        .doOnSuccess {
                            CommonLogger.logger.info("Event checkpoint performed successfully")
                        }
                        .doOnError { exception ->
                            CommonLogger.logger.error(
                                "Error performing checkpoint for read event",
                                exception
                            )
                        }
                )
        }
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
