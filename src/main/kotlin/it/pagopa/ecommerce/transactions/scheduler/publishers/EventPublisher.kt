package it.pagopa.ecommerce.transactions.scheduler.publishers

import it.pagopa.ecommerce.commons.client.QueueAsyncClient
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent
import it.pagopa.ecommerce.commons.domain.TransactionId
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

abstract class EventPublisher<E, F>(
    private val queueAsyncClient: QueueAsyncClient,
    private val logger: Logger,
    private val parallelEventsToProcess: Int,
    private val transientQueueTTLSeconds: Int,
    private val tracingUtils: TracingUtils,
) where E : BaseTransactionEvent<*>, F : Any {

    private fun publishEvent(
        baseTransaction: F,
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
                    "Error processing transaction with id: [${getTransactionId(baseTransaction)}]",
                    it
                )
                Mono.just(false)
            }
    }

    abstract fun getTransactionId(baseTransaction: F): TransactionId

    abstract fun storeEventAndUpdateView(transaction: F, newStatus: TransactionStatusDto): Mono<E>

    abstract fun toEvent(baseTransaction: F): Mono<E>

    protected fun mergeTransaction(
        baseTransactionsWithRequestedAuthorization: List<F>,
        baseTransactionUserCanceled: List<F>,
        baseTransactionActivatedOnly: List<F>
    ): List<Pair<F, TransactionStatusDto>> {
        val mergedTransactions =
            baseTransactionsWithRequestedAuthorization
                .map { Pair(it, TransactionStatusDto.EXPIRED) }
                .plus(
                    baseTransactionUserCanceled.map {
                        Pair(it, TransactionStatusDto.CANCELLATION_EXPIRED)
                    }
                )
                .plus(
                    baseTransactionActivatedOnly.map {
                        Pair(it, TransactionStatusDto.EXPIRED_NOT_AUTHORIZED)
                    }
                )
        logger.info(
            "Total expired transactions: [${mergedTransactions.size}], of which [${baseTransactionsWithRequestedAuthorization.size}] with requested authorization, [${baseTransactionActivatedOnly.size}] activated only and [${baseTransactionUserCanceled.size}] canceled by user"
        )
        return mergedTransactions
    }

    protected fun publishAllEvents(
        transactions: List<Pair<F, TransactionStatusDto>>,
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
