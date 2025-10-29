package it.pagopa.ecommerce.transactions.scheduler.services

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent
import it.pagopa.ecommerce.commons.documents.BaseTransactionView
import it.pagopa.ecommerce.transactions.scheduler.configurations.TransactionMigrationQueryServiceConfig
import it.pagopa.ecommerce.transactions.scheduler.repositories.ecommerce.TransactionsEventStoreRepository
import it.pagopa.ecommerce.transactions.scheduler.repositories.ecommerce.TransactionsViewRepository
import java.time.LocalDate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
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
) : ApplicationListener<ApplicationReadyEvent> {
    fun findEligibleEvents(): Flux<BaseTransactionEvent<*>> {
        val cutoffDate =
            LocalDate.now()
                .minusMonths(
                    transactionMigrationQueryServiceConfig.eventstore.cutoffMonthOffset.toLong()
                )
        val pageRequest: Pageable =
            PageRequest.of(0, transactionMigrationQueryServiceConfig.eventstore.maxResults)

        return transactionsEventStoreRepository.findByTtlIsNullAndCreationDateLessThan(
            cutoffDate,
            pageRequest
        )
    }

    fun findEligibleTransactions(): Flux<BaseTransactionView> {
        return Flux.empty()
    }

    override fun onApplicationEvent(event: ApplicationReadyEvent) {

        val result: BaseTransactionEvent<*>? = this.findEligibleEvents().blockLast()
        println(result)
        println("here")
    }
}
