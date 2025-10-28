package it.pagopa.ecommerce.transactions.scheduler.repositories.ecommercehistory

import it.pagopa.ecommerce.commons.documents.BaseTransactionView
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface TransactionsViewHistoryRepository : ReactiveCrudRepository<BaseTransactionView, String> {}
