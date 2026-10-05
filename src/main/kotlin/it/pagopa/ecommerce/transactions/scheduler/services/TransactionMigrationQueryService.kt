package it.pagopa.ecommerce.transactions.scheduler.services

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent
import it.pagopa.ecommerce.commons.documents.BaseTransactionView
import it.pagopa.ecommerce.commons.mdcutilities.LogTracingUtils
import it.pagopa.ecommerce.transactions.scheduler.configurations.TransactionMigrationQueryServiceConfig
import it.pagopa.ecommerce.transactions.scheduler.deadletter.CommonLogger
import it.pagopa.ecommerce.transactions.scheduler.repositories.ecommerce.TransactionsEventStoreRepository
import it.pagopa.ecommerce.transactions.scheduler.repositories.ecommerce.TransactionsViewRepository
import java.time.LocalDate
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux

@Service
class TransactionMigrationQueryService(
    @param:Autowired
    private val transactionsEventStoreRepository: TransactionsEventStoreRepository<*>,
    @param:Autowired private val transactionViewRepository: TransactionsViewRepository,
    @param:Autowired
    private val transactionMigrationQueryServiceConfig: TransactionMigrationQueryServiceConfig
) {

    val logger: Logger = LoggerFactory.getLogger(javaClass)
    fun findEligibleEvents(): Flux<BaseTransactionEvent<*>> {
        val timeBasedRate = transactionMigrationQueryServiceConfig.eventStoreTimeBasedRate
        val cutoffDate =
            LocalDate.now()
                .minusMonths(
                    transactionMigrationQueryServiceConfig.eventstore.cutoffMonthOffset.toLong()
                )
        val pageRequest: Pageable = PageRequest.of(0, timeBasedRate.calculateRate())
        return transactionsEventStoreRepository
            .findByTtlIsNullAndCreationDateLessThan(cutoffDate.toString(), pageRequest)
            .doOnNext {
                if (CommonLogger.logger.isDebugEnabled) {
                    LogTracingUtils.loggerTracingUtils()
                        .success()
                        .details(mapOf("event_id" to it.id))
                        .dependency(LogTracingUtils.MONGO_DEPENDENCY)
                        .logDebug(logger, "Eligible event found")
                }
            }
            .doOnComplete {
                LogTracingUtils.loggerTracingUtils()
                    .success()
                    .details(mapOf("page_request" to pageRequest.toString()))
                    .dependency(LogTracingUtils.MONGO_DEPENDENCY)
                    .logInfo(logger, "Eligible events retrieved successfully")
            }
    }

    fun findEligibleTransactions(): Flux<BaseTransactionView> {
        val timeBasedRate = transactionMigrationQueryServiceConfig.transactionsViewTimeBasedRate
        val cutoffDate =
            LocalDate.now()
                .minusMonths(
                    transactionMigrationQueryServiceConfig.transactionsView.cutoffMonthOffset
                        .toLong()
                )
        val pageRequest: Pageable = PageRequest.of(0, timeBasedRate.calculateRate())
        return transactionViewRepository
            .findByTtlIsNullAndCreationDateLessThan(cutoffDate.toString(), pageRequest)
            .doOnNext {
                if (CommonLogger.logger.isDebugEnabled) {
                    LogTracingUtils.loggerTracingUtils()
                        .success()
                        .details(mapOf("transaction_id" to it.transactionId))
                        .dependency(LogTracingUtils.MONGO_DEPENDENCY)
                        .logDebug(logger, "Eligible view found")
                }
            }
            .doOnComplete {
                LogTracingUtils.loggerTracingUtils()
                    .success()
                    .details(mapOf("page_request" to pageRequest.toString()))
                    .dependency(LogTracingUtils.MONGO_DEPENDENCY)
                    .logInfo(logger, "Eligible views retrieved successfully")
            }
    }
}
