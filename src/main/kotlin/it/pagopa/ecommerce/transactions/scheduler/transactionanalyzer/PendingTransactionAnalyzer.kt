package it.pagopa.ecommerce.transactions.scheduler.transactionanalyzer

import it.pagopa.ecommerce.commons.documents.BaseTransactionView
import it.pagopa.ecommerce.commons.documents.v1.Transaction as TransactionV1
import it.pagopa.ecommerce.commons.documents.v1.TransactionClosedEvent as TransactionClosedEventV1
import it.pagopa.ecommerce.commons.documents.v1.TransactionClosureData as TransactionClosureDataV1
import it.pagopa.ecommerce.commons.documents.v2.Transaction as TransactionV2
import it.pagopa.ecommerce.commons.documents.v2.TransactionClosedEvent as TransactionClosedEventV2
import it.pagopa.ecommerce.commons.documents.v2.TransactionClosureData as TransactionClosureDataV2
import it.pagopa.ecommerce.commons.domain.v1.EmptyTransaction as EmptyTransactionV1
import it.pagopa.ecommerce.commons.domain.v1.TransactionEventCode as TransactionEventCodeV1
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction as BaseTransactionV1
import it.pagopa.ecommerce.commons.domain.v2.EmptyTransaction as EmptyTransactionV2
import it.pagopa.ecommerce.commons.domain.v2.TransactionEventCode as TransactionEventCodeV2
import it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction as BaseTransactionV2
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
import it.pagopa.ecommerce.transactions.scheduler.publishers.v1.TransactionExpiredEventPublisher as TransactionExpiredEventPublisherV1
import it.pagopa.ecommerce.transactions.scheduler.publishers.v2.TransactionExpiredEventPublisher as TransactionExpiredEventPublisherV2
import it.pagopa.ecommerce.transactions.scheduler.repositories.TransactionsEventStoreRepository
import it.pagopa.ecommerce.transactions.scheduler.repositories.TransactionsViewRepository
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZonedDateTime
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Service
class PendingTransactionAnalyzer(
    private val logger: Logger = LoggerFactory.getLogger(PendingTransactionAnalyzer::class.java),
    @Autowired private val expiredTransactionEventPublisherV1: TransactionExpiredEventPublisherV1,
    @Autowired private val expiredTransactionEventPublisherV2: TransactionExpiredEventPublisherV2,
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
        batchExecutionInterTime: Long,
        totalRecordFound: Long,
        page: Pageable
    ): Mono<Boolean> {
        // take here always the first page since every interaction update records in DB changing
        // transaction statuses
        val pageRequest = PageRequest.of(0, page.pageSize, Sort.by("creationDate").ascending())
        logger.info("Searching for transaction with page request: {}", pageRequest)
        val baseTransactionViewFlux =
            viewRepository.findTransactionInTimeRangeWithExcludedStatusesPaginated(
                lowerThreshold.toString(),
                upperThreshold.toString(),
                transactionStatusesToExcludeFromView,
                page,
            )
        return searchPendingTransactions(
            baseTransactionViewFlux,
            batchExecutionInterTime,
            totalRecordFound,
            page
        )
    }
    private fun searchPendingTransactions(
        baseTransactionViewFlux: Flux<BaseTransactionView>,
        batchExecutionInterTime: Long,
        totalRecordFound: Long,
        page: Pageable
    ): Mono<Boolean> {
        return baseTransactionViewFlux
            .flatMap {
                logger.info("Analyzing transaction: $it")
                when (it) {
                    is TransactionV1 -> analyzeTransactionV1(it.transactionId)
                    is TransactionV2 -> analyzeTransactionV2(it.transactionId)
                    else ->
                        Mono.error(
                            RuntimeException("Not a known event version for transaction found")
                        )
                }
            }
            .collectList()
            .flatMap { expiredTransactions ->
                if (expiredTransactions.isEmpty()) {
                    Mono.just(true)
                } else {
                    val (expiredTransactionsV1, expiredTransactionsV2) =
                        expiredTransactions.partition { it is BaseTransactionV1 }
                    if (expiredTransactionsV1.isNotEmpty() && expiredTransactionsV2.isNotEmpty()) {
                        Mono.error(
                            RuntimeException(
                                "Expired event transactions belong to multiple events version"
                            )
                        )
                    } else if (expiredTransactionsV1.isNotEmpty()) {
                        val baseTransactionV1 =
                            expiredTransactionsV1.map { it as BaseTransactionV1 }
                        expiredTransactionEventPublisherV1.publishExpiryEvents(
                            baseTransactionV1,
                            batchExecutionInterTime,
                            totalRecordFound,
                            page
                        )
                    } else if (expiredTransactionsV2.isNotEmpty()) {
                        val baseTransactionV2 =
                            expiredTransactionsV2.map { it as BaseTransactionV2 }
                        expiredTransactionEventPublisherV2.publishExpiryEvents(
                            baseTransactionV2,
                            batchExecutionInterTime,
                            totalRecordFound,
                            page
                        )
                    } else {
                        Mono.error(
                            RuntimeException(
                                "Expired event transactions belongs to unknown events version"
                            )
                        )
                    }
                }
            }
    }

    fun getTotalTransactionCount(
        lowerThreshold: LocalDateTime,
        upperThreshold: LocalDateTime,
    ) =
        viewRepository.countTransactionInTimeRangeWithExcludedStatuses(
            lowerThreshold.toString(),
            upperThreshold.toString(),
            transactionStatusesToExcludeFromView
        )

    private fun analyzeTransactionV1(transactionId: String): Mono<BaseTransactionV1> {
        logger.info("Analyze Transaction v1 $transactionId")
        val events = eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId)
        return events
            .reduce(
                EmptyTransactionV1(),
                it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent
            )
            .filter { it !is EmptyTransactionV1 }
            .cast(BaseTransactionV1::class.java)
            .filterWhen { baseTransaction ->
                val skipTransaction =
                    if (baseTransaction.status == TransactionStatusDto.CLOSED) {
                        events
                            .filter {
                                it.eventCode.equals(
                                    TransactionEventCodeV1.TRANSACTION_CLOSED_EVENT.toString()
                                ) &&
                                    (it as TransactionClosedEventV1).data.responseOutcome ==
                                        TransactionClosureDataV1.Outcome.OK
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
                                return@map timeLeft >= Duration.ZERO
                            }
                            .switchIfEmpty(Mono.just(false))
                    } else {
                        Mono.just(false)
                    }
                skipTransaction.map {
                    val sendExpiryEvent =
                        transactionStatusesForSendExpiryEvent.contains(baseTransaction.status)
                    logger.info(
                        "Transaction with id: [${baseTransaction.transactionId}] state: [${baseTransaction.status}], expired transaction statuses: ${transactionStatusesForSendExpiryEvent}. Send event: $sendExpiryEvent, Skip transaction: $it"
                    )
                    sendExpiryEvent && !it
                }
            }
    }

    private fun analyzeTransactionV2(transactionId: String): Mono<BaseTransactionV2> {
        logger.info("Analyze Transaction v2 $transactionId")
        val events = eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId)
        return events
            .reduce(
                EmptyTransactionV2(),
                it.pagopa.ecommerce.commons.domain.v2.Transaction::applyEvent
            )
            .filter { it !is EmptyTransactionV2 }
            .cast(BaseTransactionV2::class.java)
            .filterWhen { baseTransaction ->
                val skipTransaction =
                    if (baseTransaction.status == TransactionStatusDto.CLOSED) {
                        events
                            .filter {
                                it.eventCode.equals(
                                    TransactionEventCodeV2.TRANSACTION_CLOSED_EVENT.toString()
                                ) &&
                                    (it as TransactionClosedEventV2).data.responseOutcome ==
                                        TransactionClosureDataV2.Outcome.OK
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
                                return@map timeLeft >= Duration.ZERO
                            }
                            .switchIfEmpty(Mono.just(false))
                    } else {
                        Mono.just(false)
                    }
                skipTransaction.map {
                    val sendExpiryEvent =
                        transactionStatusesForSendExpiryEvent.contains(baseTransaction.status)
                    logger.info(
                        "Transaction with id: [${baseTransaction.transactionId}] state: [${baseTransaction.status}], expired transaction statuses: ${transactionStatusesForSendExpiryEvent}. Send event: $sendExpiryEvent, Skip transaction: $it"
                    )
                    sendExpiryEvent && !it
                }
            }
    }
}
