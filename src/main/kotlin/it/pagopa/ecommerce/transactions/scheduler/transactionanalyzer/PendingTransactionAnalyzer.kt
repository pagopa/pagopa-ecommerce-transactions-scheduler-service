package it.pagopa.ecommerce.transactions.scheduler.transactionanalyzer

import it.pagopa.ecommerce.commons.documents.BaseTransactionView
import it.pagopa.ecommerce.commons.documents.v1.TransactionClosedEvent as TransactionClosedEventV1
import it.pagopa.ecommerce.commons.documents.v1.TransactionClosureData as TransactionClosureDataV1
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
        return searchPendingTransactionsV2(
                baseTransactionViewFlux,
                batchExecutionInterTime,
                totalRecordFound,
                page
            )
            .switchIfEmpty(
                searchPendingTransactionsV1(
                    baseTransactionViewFlux,
                    batchExecutionInterTime,
                    totalRecordFound,
                    page
                )
            )
            .switchIfEmpty(Mono.just(true))
    }

    private fun searchPendingTransactionsV1(
        baseTransactionViewFlux: Flux<BaseTransactionView>,
        batchExecutionInterTime: Long,
        totalRecordFound: Long,
        page: Pageable
    ): Mono<Boolean> {
        return baseTransactionViewFlux
            .flatMap {
                logger.info("Analyzing transaction: $it")
                analyzeTransactionV1(it.transactionId)
            }
            .collectList()
            .flatMap { expiredTransactions ->
                if (expiredTransactions.isEmpty()) {
                    logger.info("expired transaction should be empty")
                    Mono.empty()
                } else {
                    logger.info("expired transaction is v1 $expiredTransactions")
                    expiredTransactionEventPublisherV1.publishExpiryEvents(
                        expiredTransactions,
                        batchExecutionInterTime,
                        totalRecordFound,
                        page
                    )
                }
            }
    }

    private fun searchPendingTransactionsV2(
        baseTransactionViewFlux: Flux<BaseTransactionView>,
        batchExecutionInterTime: Long,
        totalRecordFound: Long,
        page: Pageable
    ): Mono<Boolean> {
        return baseTransactionViewFlux
            .flatMap {
                logger.info("Analyzing transaction: $it")
                analyzeTransactionV2(it.transactionId)
            }
            .collectList()
            .flatMap { expiredTransactions ->
                if (expiredTransactions.isEmpty()) {
                    logger.info("expired transaction should be empty")
                    Mono.empty()
                } else {
                    logger.info("expired transaction is v2 $expiredTransactions")
                    expiredTransactionEventPublisherV2.publishExpiryEvents(
                        expiredTransactions,
                        batchExecutionInterTime,
                        totalRecordFound,
                        page
                    )
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
        logger.info("Analyze v1")
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
        logger.info("Analyze v2")
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
