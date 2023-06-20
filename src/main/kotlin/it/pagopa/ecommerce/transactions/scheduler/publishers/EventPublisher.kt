package it.pagopa.ecommerce.transactions.scheduler.publishers

import com.azure.core.util.BinaryData
import com.azure.storage.queue.QueueAsyncClient
import it.pagopa.ecommerce.commons.documents.v1.TransactionEvent
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.Logger
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

abstract class EventPublisher<E>(
    private val queueAsyncClient: QueueAsyncClient,
    private val logger: Logger,
    private val parallelEventsToProcess: Int,
    private val transientQueueTTLSeconds: Int
) where E : TransactionEvent<*> {

    private fun publishEvent(
        baseTransaction: BaseTransaction,
        newStatus: TransactionStatusDto,
        visibilityTimeoutMillis: Long
    ): Mono<Boolean> {
        return Mono.just(baseTransaction)
            .flatMap { storeEventAndUpdateView(it, newStatus) }
            .flatMap { event ->
                queueAsyncClient
                    .sendMessageWithResponse(
                        BinaryData.fromObject(event),
                        Duration.ofMillis(visibilityTimeoutMillis),
                        Duration.ofSeconds(transientQueueTTLSeconds.toLong())
                    )
                    .flatMap {
                        logger.info(
                            "Event: [$event] successfully sent with visibility timeout: [${it.value.timeNextVisible}] ms to queue: [${queueAsyncClient.queueName}]"
                        )
                        Mono.just(true)
                    }
                    .doOnError { exception ->
                        logger.error("Error sending event: [${event}].", exception)
                    }
            }
            .onErrorResume {
                logger.error(
                    "Error processing transaction with id: [${baseTransaction.transactionId}]",
                    it
                )
                Mono.just(false)
            }
    }

    abstract fun storeEventAndUpdateView(
        transaction: BaseTransaction,
        newStatus: TransactionStatusDto
    ): Mono<E>

    abstract fun toEvent(baseTransaction: BaseTransaction): Mono<E>

    protected fun publishAllEvents(
        transactions: List<Pair<BaseTransaction, TransactionStatusDto>>,
        batchExecutionWindowMillis: Long
    ): Mono<Boolean> {
        val eventOffset = AtomicLong(0L)
        val offsetIncrement = batchExecutionWindowMillis / transactions.size
        return Flux.fromIterable(transactions)
            .parallel(parallelEventsToProcess)
            .runOn(Schedulers.parallel())
            .flatMap { (transaction, status) ->
                publishEvent(transaction, status, eventOffset.addAndGet(offsetIncrement))
            }
            .sequential()
            .collectList()
            .map { it.none { eventSent -> !eventSent } }
    }
}
