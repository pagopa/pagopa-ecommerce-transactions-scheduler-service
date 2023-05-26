package it.pagopa.ecommerce.transactions.scheduler.repositories

import it.pagopa.ecommerce.commons.documents.v1.TransactionEvent
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux

@Repository
interface TransactionsEventStoreRepository<T> :
    ReactiveCrudRepository<TransactionEvent<T>, String> {
    fun findByTransactionIdOrderByCreationDateAsc(idTransaction: String): Flux<TransactionEvent<T>>
}
