package it.pagopa.ecommerce.transactions.scheduler.repositories.ecommerce

import it.pagopa.ecommerce.commons.documents.BaseTransactionView
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
import org.springframework.data.domain.Pageable
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Repository
interface TransactionsViewRepository : ReactiveCrudRepository<BaseTransactionView, String> {

    @Query("{'creationDate': {'\$gte': '?0','\$lte': '?1'}, 'status':{'\$nin':?2}}")
    fun findTransactionInTimeRangeWithExcludedStatusesPaginated(
        from: String,
        to: String,
        excludedStatuses: Set<TransactionStatusDto>,
        pagination: Pageable
    ): Flux<BaseTransactionView>

    @Query("{'creationDate': {'\$gte': '?0','\$lte': '?1'}, 'status':{'\$nin':?2}}", count = true)
    fun countTransactionInTimeRangeWithExcludedStatuses(
        from: String,
        to: String,
        excludedStatuses: Set<TransactionStatusDto>
    ): Mono<Long>

    fun findByTransactionId(transactionId: String): Mono<BaseTransactionView>

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
        cutoffDate: String,
        pageable: Pageable
    ): Flux<BaseTransactionView>
}
