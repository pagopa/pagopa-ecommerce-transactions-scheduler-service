package it.pagopa.ecommerce.transactions.scheduler.repositories.ecommerce

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent
import java.time.LocalDate
import org.springframework.data.domain.Pageable
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux

@Repository
interface TransactionsEventStoreRepository<T> :
    ReactiveCrudRepository<BaseTransactionEvent<T>, String> {
    fun findByTransactionIdOrderByCreationDateAsc(
        idTransaction: String
    ): Flux<BaseTransactionEvent<T>>

    /**
     * Finds transactions where the 'ttl' field does not exist AND the 'creationDate' is older than
     * the specified cutoff date. Results are sorted by creationDate ascending. The limit is handled
     * by the Pageable parameter.
     * @param cutoffDate The date before which transactions are considered old.
     * @param pageable The parameter to limit results (sorting is defined in the query).
     * @return A Flux of transactions.
     */
    @Query(
        value = "{ 'ttl' : null, 'creationDate' : { '\$lt' : ?0 } }",
        sort = "{ 'creationDate' : 1 }"
    )
    fun findByTtlIsNullAndCreationDateLessThan(
        cutoffDate: LocalDate,
        pageable: Pageable
    ): Flux<BaseTransactionEvent<*>>
}
