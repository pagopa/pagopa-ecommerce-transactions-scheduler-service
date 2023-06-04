package it.pagopa.ecommerce.transactions.scheduler.publishers

import com.azure.core.util.BinaryData
import com.azure.storage.queue.QueueAsyncClient
import it.pagopa.ecommerce.commons.documents.v1.TransactionEvent
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger
import org.springframework.data.domain.PageRequest
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

abstract class EventPublisher<E>(
    private val queueAsyncClient: QueueAsyncClient,
    private val logger: Logger,
    private val parallelEventsToProcess: Int
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
                        null
                    )
                    .flatMap {
                        logger.info {
                            "Event: [$event] successfully sent with visibility timeout: [${it.value.timeNextVisible}] ms to queue: [${queueAsyncClient.queueName}]"
                        }
                        Mono.just(true)
                    }
                    .doOnError { exception ->
                        logger.log(Level.SEVERE, "Error sending event: [${event}].", exception)
                    }
            }
            .onErrorResume {
                logger.log(
                    Level.SEVERE,
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
        batchExecutionWindowMillis: Long,
        totalRecordFound: Long,
        pageRequest: PageRequest
    ): Mono<Boolean> {
        val alreadyProcessedTransactions = pageRequest.pageNumber * pageRequest.pageSize
        val offsetIncrement = batchExecutionWindowMillis / totalRecordFound
        val eventOffset = AtomicLong(alreadyProcessedTransactions.toLong() * offsetIncrement)
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
