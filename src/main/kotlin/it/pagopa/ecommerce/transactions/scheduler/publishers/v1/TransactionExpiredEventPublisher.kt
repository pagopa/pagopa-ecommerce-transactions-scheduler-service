package it.pagopa.ecommerce.transactions.scheduler.publishers.v1

import it.pagopa.ecommerce.commons.client.QueueAsyncClient
import it.pagopa.ecommerce.commons.documents.v1.Transaction as TransactionV1
import it.pagopa.ecommerce.commons.documents.v1.TransactionExpiredData as TransactionExpiredDataV1
import it.pagopa.ecommerce.commons.documents.v1.TransactionExpiredEvent as TransactionExpiredEventV1
import it.pagopa.ecommerce.commons.domain.TransactionId
import it.pagopa.ecommerce.commons.domain.v1.TransactionWithClosureError as TransactionWithClosureErrorV1
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction as BaseTransactionV1
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransactionWithCancellationRequested as BaseTransactionWithCancellationRequestedV1
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransactionWithRequestedAuthorization as BaseTransactionWithRequestedAuthorizationV1
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

@Component("TransactionExpiredEventPublisherV1")
class TransactionExpiredEventPublisher(
    private val logger: Logger =
        LoggerFactory.getLogger(TransactionExpiredEventPublisher::class.java),
    @Autowired
    @Qualifier("expiredEventQueueAsyncClientV1")
    private val expiredEventQueueAsyncClient: QueueAsyncClient,
    @Autowired private val viewRepository: TransactionsViewRepository,
    @Autowired
    private val eventStoreRepository: TransactionsEventStoreRepository<TransactionExpiredDataV1>,
    @Value("\${pendingTransactions.batch.transactionsAnalyzer.parallelEventsToProcess}")
    private val parallelEventToProcess: Int,
    @Value("\${azurestorage.queues.transientQueues.ttlSeconds}")
    private val transientQueueTTLSeconds: Int,
    @Autowired private val tracingUtils: TracingUtils
) :
    EventPublisher<TransactionExpiredEventV1, BaseTransactionV1>(
        queueAsyncClient = expiredEventQueueAsyncClient,
        logger = logger,
        parallelEventsToProcess = parallelEventToProcess,
        transientQueueTTLSeconds = transientQueueTTLSeconds,
        tracingUtils = tracingUtils
    ) {

    fun publishExpiryEvents(
        baseTransactions: List<BaseTransactionV1>,
        batchExecutionTimeWindow: Long,
        totalRecordFound: Long,
        alreadyProcessedTransactions: Long
    ): Mono<Boolean> {
        // split expired transaction in two lists: one for transactions without requested
        // authorization and one with requested authorization
        val (baseTransactionsWithRequestedAuthorization, baseTransactionsNotActivated) =
            baseTransactions.partition {
                it is BaseTransactionWithRequestedAuthorizationV1 ||
                    it is TransactionWithClosureErrorV1 &&
                        it.transactionAtPreviousState()
                            .map { txAtPrevStep -> txAtPrevStep.isRight }
                            .orElse(false)
            }
        // taking transaction for which no authorization was performed another split is done between
        // transactions with canceled by user and not
        val (baseTransactionUserCanceled, baseTransactionActivatedOnly) =
            baseTransactionsNotActivated.partition {
                it is BaseTransactionWithCancellationRequestedV1 ||
                    // here we analyze a transaction with closure error. a transaction in this state
                    // can come from a transaction both cancelled by user or not
                    // so partitioning is performed taking this in mind and checking for the
                    // transaction at previous step type
                    (it is TransactionWithClosureErrorV1 &&
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
        transaction: BaseTransactionV1,
        newStatus: TransactionStatusDto
    ): Mono<TransactionExpiredEventV1> =
        toEvent(transaction)
            .flatMap { eventStoreRepository.save(it) }
            .flatMap { event ->
                viewRepository
                    .findByTransactionId(transaction.transactionId.value())
                    .cast(TransactionV1::class.java)
                    .flatMap {
                        it.status = newStatus
                        viewRepository.save(it)
                    }
                    .flatMap { Mono.just(event) }
            }

    override fun toEvent(baseTrasaction: BaseTransactionV1): Mono<TransactionExpiredEventV1> =
        Mono.just(
            TransactionExpiredEventV1(
                baseTrasaction.transactionId.value(),
                TransactionExpiredDataV1(baseTrasaction.status)
            )
        )

    override fun getTransactionId(baseTransaction: BaseTransaction): TransactionId {
        return baseTransaction.transactionId
    }
}
