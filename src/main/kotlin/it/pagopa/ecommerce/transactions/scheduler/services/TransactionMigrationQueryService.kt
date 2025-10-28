package it.pagopa.ecommerce.transactions.scheduler.services

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent
import it.pagopa.ecommerce.commons.documents.BaseTransactionView
import it.pagopa.ecommerce.transactions.scheduler.configurations.TransactionMigrationQueryServiceConfig
import it.pagopa.ecommerce.transactions.scheduler.repositories.ecommercehistory.TransactionsEventStoreHistoryRepository
import it.pagopa.ecommerce.transactions.scheduler.repositories.ecommercehistory.TransactionsViewHistoryRepository
import java.time.LocalDate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import reactor.core.publisher.Flux

class TransactionMigrationQueryService(
    @param:Autowired
    private val transactionsEventStoreHistoryRepository: TransactionsEventStoreHistoryRepository<*>,
    @param:Autowired
    private val transactionViewHistoryRepository: TransactionsViewHistoryRepository,
    @param:Autowired
    private val transactionMigrationQueryServiceConfig: TransactionMigrationQueryServiceConfig
) {
    fun findEligibleEvents(): Flux<BaseTransactionEvent<*>> {
        val cutoffDate =
            LocalDate.now()
                .minusMonths(
                    transactionMigrationQueryServiceConfig.eventstore.cutoffMonthOffset.toLong()
                )
        val sort = Sort.by(Sort.Direction.ASC, "creationDate")
        val pageRequest: Pageable =
            PageRequest.of(0, transactionMigrationQueryServiceConfig.eventstore.maxResults, sort)

        return transactionsEventStoreHistoryRepository.findByTtlIsNullAndCreationDateLessThan(
            cutoffDate,
            pageRequest
        )
    }

    fun findEligibleTransactions(): Flux<BaseTransactionView> {
        return Flux.empty()
    }
}
