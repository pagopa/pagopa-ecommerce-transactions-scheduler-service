package it.pagopa.ecommerce.transactions.scheduler.repositories.ecommercehistory

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface TransactionsEventStoreHistoryRepository<T> :
    ReactiveCrudRepository<BaseTransactionEvent<T>, String> {}
