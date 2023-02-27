package it.pagopa.ecommerce.transactions.scheduler.publishers

import com.azure.storage.queue.QueueAsyncClient
import it.pagopa.ecommerce.commons.documents.v1.PaymentNotice
import it.pagopa.ecommerce.commons.documents.v1.Transaction
import it.pagopa.ecommerce.commons.documents.v1.TransactionExpiredData
import it.pagopa.ecommerce.commons.documents.v1.TransactionExpiredEvent
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
import it.pagopa.ecommerce.commons.utils.v1.TransactionUtils
import it.pagopa.ecommerce.transactions.scheduler.repositories.TransactionsEventStoreRepository
import it.pagopa.ecommerce.transactions.scheduler.repositories.TransactionsViewRepository
import java.util.logging.Logger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

@Component
class TransactionExpiredEventPublisher(
    logger: Logger = Logger.getGlobal(),
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
    ) = publishAllEvents(baseTransactions, TransactionStatusDto.EXPIRED, batchExecutionTimeWindow)

    override fun storeEventAndUpdateView(
        transaction: BaseTransaction,
        newStatus: TransactionStatusDto
    ): Mono<TransactionExpiredEvent> =
        toEvent(transaction)
            .flatMap { eventStoreRepository.save(it) }
            .flatMap { event ->
                viewRepository
                    .save(
                        Transaction(
                            transaction.transactionId.value.toString(),
                            transaction.paymentNotices.map { notice ->
                                PaymentNotice(
                                    notice.paymentToken.value,
                                    notice.rptId.value,
                                    notice.transactionDescription.value,
                                    notice.transactionAmount.value,
                                    notice.paymentContextCode.value
                                )
                            },
                            TransactionUtils.getTransactionFee(transaction).orElse(null),
                            transaction.email,
                            TransactionStatusDto.EXPIRED,
                            transaction.clientId,
                            transaction.creationDate.toString()
                        )
                    )
                    .flatMap { Mono.just(event) }
            }

    override fun toEvent(baseTrasaction: BaseTransaction): Mono<TransactionExpiredEvent> =
        Mono.just(
            TransactionExpiredEvent(
                baseTrasaction.transactionId.value.toString(),
                TransactionExpiredData(baseTrasaction.status)
            )
        )
}
