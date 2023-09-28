package it.pagopa.ecommerce.transactions.scheduler.transactionanalyzer

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent
import it.pagopa.ecommerce.commons.documents.BaseTransactionView
import it.pagopa.ecommerce.commons.documents.v1.Transaction as TransactionV1
import it.pagopa.ecommerce.commons.documents.v1.TransactionClosedEvent as TransactionClosedEventV1
import it.pagopa.ecommerce.commons.documents.v1.TransactionClosureData as TransactionClosureDataV1
import it.pagopa.ecommerce.commons.documents.v1.TransactionEvent as TransactionEventV1
import it.pagopa.ecommerce.commons.documents.v2.Transaction as TransactionV2
import it.pagopa.ecommerce.commons.documents.v2.TransactionClosedEvent as TransactionClosedEventV2
import it.pagopa.ecommerce.commons.documents.v2.TransactionClosureData as TransactionClosureDataV2
import it.pagopa.ecommerce.commons.documents.v2.TransactionEvent as TransactionEventV2
import it.pagopa.ecommerce.commons.domain.TransactionId
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
import java.util.function.Predicate
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
                when (expiredTransactions.isEmpty()) {
                    true -> Mono.just(true)
                    else -> {
                        val (expiredTransactionsV1, others) =
                            expiredTransactions.partition { it is BaseTransactionV1 }

                        val (expiredTransactionsV2, unmatched) =
                            others.partition { it is BaseTransactionV2 }

                        if (unmatched.isNotEmpty()) {
                            logger.error("Unmatched transactions found {}", unmatched)
                        }

                        val baseTransactionV1 =
                            expiredTransactionsV1.map { it as BaseTransactionV1 }
                        val publishBaseTransactionV1 =
                            expiredTransactionEventPublisherV1.publishExpiryEvents(
                                baseTransactionV1,
                                batchExecutionInterTime,
                                totalRecordFound,
                                page
                            )

                        val baseTransactionV2 =
                            expiredTransactionsV2.map { it as BaseTransactionV2 }
                        val publishBaseTransactionV2 =
                            expiredTransactionEventPublisherV2.publishExpiryEvents(
                                baseTransactionV2,
                                batchExecutionInterTime,
                                totalRecordFound,
                                page
                            )

                        publishBaseTransactionV1
                            .flatMap { v1Outcome ->
                                publishBaseTransactionV2.map { Pair(v1Outcome, it) }
                            }
                            .map { (v1Outcome, v2Outcome) ->
                                logger.info(
                                    "Overall processing outcome -> V1 outcome: {}, V2 outcome: {}",
                                    v1Outcome,
                                    v2Outcome
                                )
                                v1Outcome.and(v2Outcome)
                            }
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
                mapSkipTransaction(baseTransaction.status, events, baseTransaction.transactionId)
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
                mapSkipTransaction(baseTransaction.status, events, baseTransaction.transactionId)
            }
    }

    private fun mapSkipTransaction(
        status: TransactionStatusDto,
        events: Flux<BaseTransactionEvent<Any>>,
        transactionId: TransactionId
    ): Mono<Boolean> {
        val skipTransaction =
            if (status == TransactionStatusDto.CLOSED) {
                filterEvents(events)
            } else {
                Mono.just(false)
            }
        return skipTransaction.map {
            val sendExpiryEvent = transactionStatusesForSendExpiryEvent.contains(status)
            logger.info(
                "Transaction with id: [${transactionId}] state: [${status}], expired transaction statuses: ${transactionStatusesForSendExpiryEvent}. Send event: $sendExpiryEvent, Skip transaction: $it"
            )
            sendExpiryEvent && !it
        }
    }

    private fun predicateEventsV1(
        baseTransactionEvent: BaseTransactionEvent<Any>
    ): Predicate<BaseTransactionEvent<Any>> {
        return Predicate {
            baseTransactionEvent.eventCode ==
                TransactionEventCodeV1.TRANSACTION_CLOSED_EVENT.toString() &&
                (it as TransactionClosedEventV1).data.responseOutcome ==
                    TransactionClosureDataV1.Outcome.OK
        }
    }
    private fun predicateEventsV2(
        baseTransactionEvent: BaseTransactionEvent<Any>
    ): Predicate<BaseTransactionEvent<Any>> {
        return Predicate {
            baseTransactionEvent.eventCode ==
                TransactionEventCodeV2.TRANSACTION_CLOSED_EVENT.toString() &&
                (baseTransactionEvent as TransactionClosedEventV2).data.responseOutcome ==
                    TransactionClosureDataV2.Outcome.OK
        }
    }

    private fun filterEvents(events: Flux<BaseTransactionEvent<Any>>): Mono<Boolean> {
        return events
            .filter {
                when (it) {
                    is TransactionEventV1 -> predicateEventsV1(it).test(it)
                    is TransactionEventV2 -> predicateEventsV2(it).test(it)
                    else -> Predicate<BaseTransactionEvent<Any>> { false }.test(it)
                }
            }
            .next()
            .map {
                val timeout = Duration.ofSeconds(sendPaymentResultTimeoutSeconds.toLong())
                val closePaymentDate = ZonedDateTime.parse(it.creationDate)
                val now = ZonedDateTime.now()
                val timeLeft = Duration.between(now, closePaymentDate.plus(timeout))
                logger.info(
                    "Transaction close payment done at: $closePaymentDate, time left: $timeLeft"
                )
                return@map timeLeft >= Duration.ZERO
            }
            .switchIfEmpty(Mono.just(false))
    }
}
