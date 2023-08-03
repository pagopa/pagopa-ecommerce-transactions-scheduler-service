package it.pagopa.ecommerce.transactions.scheduler.publishers

import it.pagopa.ecommerce.commons.client.QueueAsyncClient
import it.pagopa.ecommerce.commons.documents.v1.TransactionEvent
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
import it.pagopa.ecommerce.commons.queues.QueueEvent
import it.pagopa.ecommerce.commons.queues.TracingUtils
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.Logger
import org.springframework.data.domain.Pageable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

abstract class EventPublisher<E>(
    private val queueAsyncClient: QueueAsyncClient,
    private val logger: Logger,
    private val parallelEventsToProcess: Int,
    private val transientQueueTTLSeconds: Int,
    private val tracingUtils: TracingUtils,
) where E : TransactionEvent<*> {

    private fun publishEvent(
        baseTransaction: BaseTransaction,
        newStatus: TransactionStatusDto,
        visibilityTimeoutMillis: Long
    ): Mono<Boolean> {
        return Mono.just(baseTransaction)
            .flatMap { storeEventAndUpdateView(it, newStatus) }
            .flatMap { event ->
                tracingUtils.traceMono(this.javaClass.simpleName) { tracingInfo ->
                    queueAsyncClient
                        .sendMessageWithResponse(
                            QueueEvent(event, tracingInfo),
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
        batchExecutionWindowMillis: Long,
        totalRecordFound: Long,
        page: Pageable
    ): Mono<Boolean> {
        val alreadyProcessedTransactions = page.pageNumber * page.pageSize
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
