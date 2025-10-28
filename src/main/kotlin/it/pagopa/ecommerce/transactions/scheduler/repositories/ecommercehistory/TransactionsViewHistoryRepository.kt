package it.pagopa.ecommerce.transactions.scheduler.repositories.ecommercehistory

import it.pagopa.ecommerce.commons.documents.BaseTransactionView
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Mono

@Repository
interface TransactionsViewHistoryRepository : ReactiveCrudRepository<BaseTransactionView, String> {

    fun findByTransactionId(transactionId: String): Mono<BaseTransactionView>
}
