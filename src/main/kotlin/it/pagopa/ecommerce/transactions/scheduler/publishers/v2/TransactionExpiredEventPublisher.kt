package it.pagopa.ecommerce.transactions.scheduler.publishers.v2

import it.pagopa.ecommerce.commons.client.QueueAsyncClient
import it.pagopa.ecommerce.commons.documents.v2.Transaction as TransactionV2
import it.pagopa.ecommerce.commons.documents.v2.TransactionExpiredData as TransactionExpiredDataV2
import it.pagopa.ecommerce.commons.documents.v2.TransactionExpiredEvent as TransactionExpiredEventV2
import it.pagopa.ecommerce.commons.domain.v2.TransactionWithClosureError as TransactionWithClosureErrorV2
import it.pagopa.ecommerce.commons.domain.v2.TransactionWithClosureRequested
import it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction as BaseTransactionV2
import it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction
import it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransactionWithCancellationRequested as BaseTransactionWithCancellationRequestedV2
import it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransactionWithRequestedAuthorization as BaseTransactionWithRequestedAuthorizationV2
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
import it.pagopa.ecommerce.commons.queues.TracingUtils
import it.pagopa.ecommerce.transactions.scheduler.publishers.EventPublisher
import it.pagopa.ecommerce.transactions.scheduler.repositories.TransactionsEventStoreRepository
import it.pagopa.ecommerce.transactions.scheduler.repositories.TransactionsViewRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

@Component("TransactionExpiredEventPublisherV2")
class TransactionExpiredEventPublisher(
    private val logger: Logger =
        LoggerFactory.getLogger(TransactionExpiredEventPublisher::class.java),
    @Autowired
    @Qualifier("expiredEventQueueAsyncClientV2")
    private val expiredEventQueueAsyncClient: QueueAsyncClient,
    @Autowired private val viewRepository: TransactionsViewRepository,
    @Autowired
    private val eventStoreRepository: TransactionsEventStoreRepository<TransactionExpiredDataV2>,
    @Value("\${pendingTransactions.batch.transactionsAnalyzer.parallelEventsToProcess}")
    private val parallelEventToProcess: Int,
    @Value("\${azurestorage.queues.transientQueues.ttlSeconds}")
    private val transientQueueTTLSeconds: Int,
    @Autowired private val tracingUtils: TracingUtils,
    @Value("\${transactionsview.update.enabled}")
    private val transactionsViewUpdateEnabled: Boolean,
) :
    EventPublisher<TransactionExpiredEventV2, BaseTransactionV2>(
        queueAsyncClient = expiredEventQueueAsyncClient,
        logger = logger,
        parallelEventsToProcess = parallelEventToProcess,
        transientQueueTTLSeconds = transientQueueTTLSeconds,
        tracingUtils = tracingUtils
    ) {

    fun publishExpiryEvents(
        baseTransactions: List<BaseTransactionV2>,
        batchExecutionTimeWindow: Long,
        totalRecordFound: Long,
        alreadyProcessedTransactions: Long
    ): Mono<Boolean> {
        // split expired transaction in two lists: one for transactions without requested
        // authorization and one with requested authorization
        val (baseTransactionsWithRequestedAuthorization, baseTransactionsNotActivated) =
            baseTransactions.partition {
                it is BaseTransactionWithRequestedAuthorizationV2 ||
                    it is TransactionWithClosureRequested ||
                    it is TransactionWithClosureErrorV2 &&
                        it.transactionAtPreviousState()
                            .map { txAtPrevStep -> txAtPrevStep.isRight }
                            .orElse(false)
            }
        // taking transaction for which no authorization was performed another split is done between
        // transactions with canceled by user and not
        val (baseTransactionUserCanceled, baseTransactionActivatedOnly) =
            baseTransactionsNotActivated.partition {
                it is BaseTransactionWithCancellationRequestedV2 ||
                    // here we analyze a transaction with closure error. a transaction in this state
                    // can come from a transaction both cancelled by user or not
                    // so partitioning is performed taking this in mind and checking for the
                    // transaction at previous step type
                    (it is TransactionWithClosureErrorV2 &&
                        it.transactionAtPreviousState()
                            .map { txAtPreviousStep -> txAtPreviousStep.isLeft }
                            .orElse(false))
            }
        val mergedTransactions =
            mergeTransaction(
                baseTransactionsWithRequestedAuthorization,
                baseTransactionUserCanceled,
                baseTransactionActivatedOnly
            )

        return publishAllEvents(
            mergedTransactions,
            batchExecutionTimeWindow,
            totalRecordFound,
            alreadyProcessedTransactions
        )
    }

    override fun storeEventAndUpdateView(
        transaction: BaseTransactionV2,
        newStatus: TransactionStatusDto
    ): Mono<TransactionExpiredEventV2> =
        toEvent(transaction)
            .flatMap { eventStoreRepository.save(it) }
            .flatMap { event ->
                conditionallySaveTransactionView(transaction, newStatus).then(Mono.just(event))
            }

    /**
     * Save the transaction in the transactions-view collection, iff the transactions-view update is
     * enabled. Otherwise, do nothing.
     */
    private fun conditionallySaveTransactionView(
        transaction: BaseTransactionV2,
        newStatus: TransactionStatusDto
    ): Mono<TransactionV2> =
        Mono.just(transactionsViewUpdateEnabled)
            .filter { it }
            .map { viewRepository.findByTransactionId(transaction.transactionId.value()) }
            .flatMap { it.cast(TransactionV2::class.java) }
            .flatMap {
                it.status = newStatus
                viewRepository.save(it)
            }

    override fun toEvent(baseTransaction: BaseTransactionV2): Mono<TransactionExpiredEventV2> =
        Mono.just(
            TransactionExpiredEventV2(
                baseTransaction.transactionId.value(),
                TransactionExpiredDataV2(baseTransaction.status)
            )
        )

    override fun getTransactionId(baseTransaction: BaseTransaction): String {
        return baseTransaction.transactionId.value()
    }
}
