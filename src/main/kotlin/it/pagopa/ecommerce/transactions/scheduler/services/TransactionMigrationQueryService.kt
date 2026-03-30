package it.pagopa.ecommerce.transactions.scheduler.services

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent
import it.pagopa.ecommerce.commons.documents.BaseTransactionView
import it.pagopa.ecommerce.transactions.scheduler.configurations.TransactionMigrationQueryServiceConfig
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
        logger.info("Calculated paged request for finding eligible events: $pageRequest")
        return transactionsEventStoreRepository.findByTtlIsNullAndCreationDateLessThan(
            cutoffDate.toString(),
            pageRequest
        )
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
        logger.info("Calculated paged request for finding eligible views: $pageRequest")
        return transactionViewRepository.findByTtlIsNullAndCreationDateLessThan(
            cutoffDate.toString(),
            pageRequest
        )
    }
}
