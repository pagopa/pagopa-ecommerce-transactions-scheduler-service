package it.pagopa.ecommerce.transactions.scheduler.publishers

import com.azure.storage.queue.QueueAsyncClient
import it.pagopa.ecommerce.commons.documents.v1.*
import it.pagopa.ecommerce.commons.domain.v1.TransactionWithClosureError
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransactionWithCancellationRequested
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransactionWithRequestedAuthorization
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
import it.pagopa.ecommerce.transactions.scheduler.repositories.TransactionsEventStoreRepository
import it.pagopa.ecommerce.transactions.scheduler.repositories.TransactionsViewRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

@Component
class TransactionExpiredEventPublisher(
    private val logger: Logger =
        LoggerFactory.getLogger(TransactionExpiredEventPublisher::class.java),
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
        batchExecutionTimeWindow: Long,
        totalRecordFound: Long,
        pageRequest: PageRequest
    ): Mono<Boolean> {
        // split expired transaction in two lists: one for transactions without requested
        // authorization and one with requested authorization
        val (baseTransactionsWithRequestedAuthorization, baseTransactionsNotActivated) =
            baseTransactions.partition {
                it is BaseTransactionWithRequestedAuthorization ||
                    it is TransactionWithClosureError &&
                        it.transactionAtPreviousState()
                            .map { txAtPrevStep -> txAtPrevStep.isRight }
                            .orElse(false)
            }
        // taking transaction for which no authorization was performed another split is done between
        // transactions with canceled by user and not
        val (baseTransactionUserCanceled, baseTransactionActivatedOnly) =
            baseTransactionsNotActivated.partition {
                it is BaseTransactionWithCancellationRequested ||
                    // here we analyze a transaction with closure error. a transaction in this state
                    // can come from a transaction both cancelled by user or not
                    // so partitioning is performed taking this in mind and checking for the
                    // transaction at previous step type
                    (it is TransactionWithClosureError &&
                        it.transactionAtPreviousState()
                            .map { txAtPreviousStep -> txAtPreviousStep.isLeft }
                            .orElse(false))
            }
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
        return publishAllEvents(
            mergedTransactions,
            batchExecutionTimeWindow,
            totalRecordFound,
            pageRequest
        )
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
