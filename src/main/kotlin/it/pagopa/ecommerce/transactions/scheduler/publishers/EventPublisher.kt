package it.pagopa.ecommerce.transactions.scheduler.publishers

import it.pagopa.ecommerce.commons.client.QueueAsyncClient
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
import it.pagopa.ecommerce.commons.mdcutilities.LogTracingUtils
import it.pagopa.ecommerce.commons.queues.QueueEvent
import it.pagopa.ecommerce.commons.queues.TracingUtils
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.Logger
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
                tracingUtils
                    .traceMono(this.javaClass.simpleName) { tracingInfo ->
                        queueAsyncClient
                            .sendMessageWithResponse(
                                QueueEvent(event, tracingInfo),
                                Duration.ofMillis(visibilityTimeoutMillis),
                                Duration.ofSeconds(transientQueueTTLSeconds.toLong())
                            )
                            .flatMap {
                                LogTracingUtils.loggerTracingUtils()
                                    .success()
                                    .dependency(LogTracingUtils.STORAGE_QUEUE_DEPENDENCY)
                                    .details(
                                        mapOf(
                                            "visibility_timeout_millis" to
                                                it.value.timeNextVisible.toString(),
                                            "queue_name" to queueAsyncClient.queueName
                                        )
                                    )
                                    .logInfo(logger, "Event successfully sent")
                                Mono.just(true)
                            }
                            .doOnError { exception ->
                                LogTracingUtils.loggerTracingUtils()
                                    .failure()
                                    .dependency(LogTracingUtils.STORAGE_QUEUE_DEPENDENCY)
                                    .logErrorWithStackTrace(
                                        logger,
                                        exception,
                                        "Error sending event"
                                    )
                            }
                    }
                    .contextWrite { context ->
                        LogTracingUtils.enrichContextForEvent(
                            mapOf(
                                LogTracingUtils.AttributeKeys.CTX_TRANSACTION_ID to
                                    event.transactionId,
                                LogTracingUtils.AttributeKeys.CTX_EVENT_CODE to event.eventCode,
                                LogTracingUtils.AttributeKeys.CTX_EVENT_ID to event.id
                            ),
                            context
                        )
                    }
            }
            .onErrorResume {
                LogTracingUtils.loggerTracingUtils()
                    .failure()
                    .logErrorWithStackTrace(logger, it, "Error processing transaction")
                Mono.just(false)
            }
    }

    abstract fun getTransactionId(baseTransaction: F): String

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
        LogTracingUtils.loggerTracingUtils()
            .success()
            .details(
                mapOf(
                    "total_expired_transactions" to mergedTransactions.size.toString(),
                    "requested_authorization_transactions" to
                        baseTransactionsWithRequestedAuthorization.size.toString(),
                    "activated_only_transactions" to baseTransactionActivatedOnly.size.toString(),
                    "user_canceled_transactions" to baseTransactionUserCanceled.size.toString()
                )
            )
            .logInfo(logger, "Expired transactions merged successfully")
        return mergedTransactions
    }

    protected fun publishAllEvents(
        transactions: List<Pair<F, TransactionStatusDto>>,
        batchExecutionWindowMillis: Long,
        totalRecordFound: Long,
        alreadyProcessedTransactions: Long
    ): Mono<Boolean> {
        val offsetIncrement = batchExecutionWindowMillis / totalRecordFound
        val eventOffset = AtomicLong(alreadyProcessedTransactions * offsetIncrement)
        return Flux.fromIterable(transactions)
            .parallel(parallelEventsToProcess)
            .runOn(Schedulers.parallel())
            .flatMap { (transaction, status) ->
                publishEvent(transaction, status, eventOffset.addAndGet(offsetIncrement))
            }
            .sequential()
            .collectList()
            .map { it.none { eventSent -> !eventSent } }
            .switchIfEmpty(Mono.just(true))
    }
}
