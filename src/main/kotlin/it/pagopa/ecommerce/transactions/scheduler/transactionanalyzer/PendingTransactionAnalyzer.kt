package it.pagopa.ecommerce.transactions.scheduler.transactionanalyzer

import it.pagopa.ecommerce.commons.domain.v1.EmptyTransaction
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
import it.pagopa.ecommerce.transactions.scheduler.publishers.TransactionExpiredEventPublisher
import it.pagopa.ecommerce.transactions.scheduler.repositories.TransactionsEventStoreRepository
import it.pagopa.ecommerce.transactions.scheduler.repositories.TransactionsViewRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import java.time.LocalDateTime
import java.util.logging.Logger

@Service
class PendingTransactionAnalyzer(
    private val logger: Logger = Logger.getGlobal(),
    @Autowired private val expiredTransactionEventPublisher: TransactionExpiredEventPublisher,
    @Autowired private val viewRepository: TransactionsViewRepository,
    @Autowired private val eventStoreRepository: TransactionsEventStoreRepository<Any>
) {
    /*
     * Set of all transactions statuses that will not be considered during batch execution.
     * Those statuses are all the transaction final statuses and the expired status
     * for which no further elaboration is needed (avoid to re-process the same transaction
     * multiple times)
     */
    private val transactionStatusesToExcludeFromView =
        setOf(
            TransactionStatusDto.EXPIRED_NOT_AUTHORIZED,
            TransactionStatusDto.CANCELED,
            TransactionStatusDto.UNAUTHORIZED,
            TransactionStatusDto.NOTIFIED,
            TransactionStatusDto.EXPIRED,
            TransactionStatusDto.REFUNDED,
        )

    /*
     * Set of all transaction statuses for which send expiry event
     */
    private val transactionStatusesForSendExpiryEvent =
        setOf(
            TransactionStatusDto.ACTIVATED,
            TransactionStatusDto.AUTHORIZATION_REQUESTED,
            TransactionStatusDto.AUTHORIZATION_COMPLETED,
            TransactionStatusDto.CLOSURE_ERROR,
            TransactionStatusDto.CLOSED,
        )

    fun searchPendingTransactions(
        lowerThreshold: LocalDateTime,
        upperThreshold: LocalDateTime,
        batchExecutionIntertime: Long
    ): Mono<Boolean> {
        return viewRepository
            .findTransactionInTimeRangeWithExcludedStatuses(
                lowerThreshold.toString(),
                upperThreshold.toString(),
                transactionStatusesToExcludeFromView
            )
            .flatMap {
                logger.info("Analyzing transaction: $it")
                analyzeTransaction(it.transactionId)
            }
            .collectList()
            .flatMap {
                if (it.isEmpty()) {
                    Mono.just(true)
                } else {
                    expiredTransactionEventPublisher.publishExpiryEvents(
                        it,
                        batchExecutionIntertime
                    )
                }
            }
    }

    private fun analyzeTransaction(transactionId: String): Mono<BaseTransaction> {
        return eventStoreRepository
            .findByTransactionId(transactionId)
            .reduce(
                EmptyTransaction(),
                it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent
            )
            .cast(BaseTransaction::class.java)
            .flatMap {
                val sendExpiryEvent = transactionStatusesForSendExpiryEvent.contains(it.status)
                logger.info(
                    "Transaction with id: [${it.transactionId}] state: [${it.status}], expired transaction statuses: $transactionStatusesForSendExpiryEvent. Send event: $sendExpiryEvent"
                )
                if (sendExpiryEvent) {
                    Mono.just(it)
                } else {
                    Mono.empty()
                }
            }
    }
}
