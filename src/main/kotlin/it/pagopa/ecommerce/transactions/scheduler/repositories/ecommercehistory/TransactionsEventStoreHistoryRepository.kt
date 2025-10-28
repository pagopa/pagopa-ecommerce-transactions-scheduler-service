package it.pagopa.ecommerce.transactions.scheduler.repositories.ecommercehistory

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent
import java.time.LocalDate
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux

@Repository
interface TransactionsEventStoreHistoryRepository<T> :
    ReactiveCrudRepository<BaseTransactionEvent<T>, String> {
    /**
     * Finds transactions where the 'ttl' field is null or not set.
     * @param pageable The parameter to limit results and define sorting.
     * @return A Flux of transactions.
     */
    fun findByTtlIsNullAndCreationDateLessThan(
        cutoff: LocalDate,
        pageable: Pageable
    ): Flux<BaseTransactionEvent<*>>
}
