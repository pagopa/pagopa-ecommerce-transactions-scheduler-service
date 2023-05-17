package it.pagopa.ecommerce.transactions.scheduler.publishers

import com.azure.storage.queue.QueueAsyncClient
import it.pagopa.ecommerce.commons.documents.v1.*
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransactionWithRequestedAuthorization
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
import it.pagopa.ecommerce.transactions.scheduler.repositories.TransactionsEventStoreRepository
import it.pagopa.ecommerce.transactions.scheduler.repositories.TransactionsViewRepository
import java.util.logging.Logger
import java.util.stream.Collectors
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

@Component
class TransactionExpiredEventPublisher(
    private val logger: Logger = Logger.getGlobal(),
    @Autowired private val expiredEventQueueAsyncClient: QueueAsyncClient,
    @Autowired private val viewRepository: TransactionsViewRepository,
    @Autowired
    private val eventStoreRepository: TransactionsEventStoreRepository<TransactionExpiredData>,
    @Value("\${pendingTransactions.batch.transactionsAnalyzer.parallelEventsToProcess}")
    private val parallelEventToProcess: Int
) :
    EventPublisher<TransactionExpiredEvent>(
        queueAsyncClient = expiredEventQueueAsyncClient,
        logger = logger,
        parallelEventsToProcess = parallelEventToProcess
    ) {

    fun publishExpiryEvents(
        baseTransactions: List<BaseTransaction>,
        batchExecutionTimeWindow: Long
    ): Mono<Boolean> {
        // split expired transaction in two lists: one for transactions without requested
        // authorization and one with requested authorization
        val splittedTransactions: Map<Boolean, List<BaseTransaction>> =
            baseTransactions
                .stream()
                .collect(Collectors.groupingBy { it is BaseTransactionWithRequestedAuthorization })
        val baseTransactionsWithRequestedAuthorization = splittedTransactions[true] ?: emptyList()
        val baseTransactionsActivatedOnly = splittedTransactions[false] ?: emptyList()
        val mergedTransactions =
            baseTransactionsWithRequestedAuthorization
                .map { Pair(it, TransactionStatusDto.EXPIRED) }
                .plus(
                    baseTransactionsActivatedOnly.map {
                        Pair(it, TransactionStatusDto.EXPIRED_NOT_AUTHORIZED)
                    }
                )
        logger.info(
            "Total expired transactions: [${mergedTransactions.size}], of which [${baseTransactionsWithRequestedAuthorization.size}] with requested authorization and [${baseTransactionsActivatedOnly.size}] activated only"
        )
        return publishAllEvents(mergedTransactions, batchExecutionTimeWindow)
    }

    override fun storeEventAndUpdateView(
        transaction: BaseTransaction,
        newStatus: TransactionStatusDto
    ): Mono<TransactionExpiredEvent> =
        toEvent(transaction)
            .flatMap { eventStoreRepository.save(it) }
            .flatMap { event ->
                viewRepository
                    .findByTransactionId(transaction.transactionId.value())
                    .flatMap {
                        it.status = newStatus
                        viewRepository.save(it)
                    }
                    .flatMap { Mono.just(event) }
            }

    override fun toEvent(baseTrasaction: BaseTransaction): Mono<TransactionExpiredEvent> =
        Mono.just(
            TransactionExpiredEvent(
                baseTrasaction.transactionId.value(),
                TransactionExpiredData(baseTrasaction.status)
            )
        )
}
