package it.pagopa.ecommerce.transactions.scheduler.transactionanalyzer

import it.pagopa.ecommerce.commons.documents.v1.TransactionClosedEvent
import it.pagopa.ecommerce.commons.documents.v1.TransactionClosureData
import it.pagopa.ecommerce.commons.domain.v1.EmptyTransaction
import it.pagopa.ecommerce.commons.domain.v1.TransactionEventCode
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
import it.pagopa.ecommerce.transactions.scheduler.publishers.TransactionExpiredEventPublisher
import it.pagopa.ecommerce.transactions.scheduler.repositories.TransactionsEventStoreRepository
import it.pagopa.ecommerce.transactions.scheduler.repositories.TransactionsViewRepository
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZonedDateTime
import kotlinx.coroutines.reactor.mono
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

@Service
class PendingTransactionAnalyzer(
    private val logger: Logger = LoggerFactory.getLogger(PendingTransactionAnalyzer::class.java),
    @Autowired private val expiredTransactionEventPublisher: TransactionExpiredEventPublisher,
    @Autowired private val viewRepository: TransactionsViewRepository,
    @Autowired private val eventStoreRepository: TransactionsEventStoreRepository<Any>,
    /*
     * Set of all transactions statuses that will not be considered during batch execution.
     * Those statuses are all the transaction final statuses and the expired status
     * for which no further elaboration is needed (avoid to re-process the same transaction
     * multiple times)
     */
    val transactionStatusesToExcludeFromView: Set<TransactionStatusDto> =
        setOf(
            TransactionStatusDto.EXPIRED_NOT_AUTHORIZED,
            TransactionStatusDto.CANCELED,
            TransactionStatusDto.UNAUTHORIZED,
            TransactionStatusDto.NOTIFIED_OK,
            TransactionStatusDto.EXPIRED,
            TransactionStatusDto.REFUND_REQUESTED,
            TransactionStatusDto.REFUND_ERROR,
            TransactionStatusDto.REFUNDED,
            TransactionStatusDto.CANCELLATION_EXPIRED,
        ),
    /*
     * Set of all transaction statuses for which send expiry event
     */
    val transactionStatusesForSendExpiryEvent: Set<TransactionStatusDto> =
        setOf(
            TransactionStatusDto.ACTIVATED,
            TransactionStatusDto.AUTHORIZATION_REQUESTED,
            TransactionStatusDto.AUTHORIZATION_COMPLETED,
            TransactionStatusDto.CANCELLATION_REQUESTED,
            TransactionStatusDto.CLOSURE_ERROR,
            TransactionStatusDto.CLOSED,
            TransactionStatusDto.NOTIFIED_KO,
            TransactionStatusDto.NOTIFICATION_REQUESTED,
            TransactionStatusDto.NOTIFICATION_ERROR,
        ),
    @Value("\${pendingTransactions.batch.sendPaymentResultTimeout}")
    private val sendPaymentResultTimeoutSeconds: Int,
) {

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
            .flatMap { expiredTransactions ->
                if (expiredTransactions.isEmpty()) {
                    Mono.just(true)
                } else {
                    expiredTransactionEventPublisher.publishExpiryEvents(
                        expiredTransactions,
                        batchExecutionIntertime
                    )
                }
            }
    }

    private fun analyzeTransaction(transactionId: String): Mono<BaseTransaction> {
        val events = eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId)
        return events
            .reduce(
                EmptyTransaction(),
                it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent
            )
            .cast(BaseTransaction::class.java)
            .filterWhen { baseTransaction ->
                val skipEvent =
                    if (baseTransaction.status == TransactionStatusDto.CLOSED) {
                        events
                            .filter {
                                it.eventCode == TransactionEventCode.TRANSACTION_CLOSED_EVENT &&
                                    (it as TransactionClosedEvent).data.responseOutcome ==
                                        TransactionClosureData.Outcome.OK
                            }
                            .next()
                            .map {
                                val timeout =
                                    Duration.ofSeconds(sendPaymentResultTimeoutSeconds.toLong())
                                val closePaymentDate = ZonedDateTime.parse(it.creationDate)
                                val now = ZonedDateTime.now()
                                val timeLeft = Duration.between(now, closePaymentDate.plus(timeout))
                                logger.info(
                                    "Transaction close payment done at: $closePaymentDate, time left: $timeLeft"
                                )
                                return@map timeLeft <= Duration.ZERO
                            }
                    } else {
                        mono { false }
                    }
                skipEvent.map {
                    val sendExpiryEvent =
                        transactionStatusesForSendExpiryEvent.contains(baseTransaction.status)
                    logger.info(
                        "Transaction with id: [${baseTransaction.transactionId}] state: [${baseTransaction.status}], expired transaction statuses: ${transactionStatusesForSendExpiryEvent}. Send event: $sendExpiryEvent, Skip event: $it"
                    )
                    sendExpiryEvent && !it
                }
            }
    }
}
