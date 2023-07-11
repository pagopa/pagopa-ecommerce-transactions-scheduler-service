package it.pagopa.ecommerce.transactions.scheduler.repositories

import it.pagopa.ecommerce.commons.documents.v1.Transaction
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
import org.springframework.data.domain.Pageable
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Repository
interface TransactionsViewRepository : ReactiveCrudRepository<Transaction, String> {

    @Query("{'creationDate': {'\$gte': '?0','\$lte': '?1'}, 'status':{'\$nin':?2}}")
    fun findTransactionInTimeRangeWithExcludedStatusesPaginated(
        from: String,
        to: String,
        excludedStatuses: Set<TransactionStatusDto>,
        pagination: Pageable
    ): Flux<Transaction>

    @Query("{'creationDate': {'\$gte': '?0','\$lte': '?1'}, 'status':{'\$nin':?2}}", count = true)
    fun countTransactionInTimeRangeWithExcludedStatuses(
        from: String,
        to: String,
        excludedStatuses: Set<TransactionStatusDto>
    ): Mono<Long>

    fun findByTransactionId(transactionId: String): Mono<Transaction>
}
