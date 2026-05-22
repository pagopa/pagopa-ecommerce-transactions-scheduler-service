package it.pagopa.ecommerce.transactions.scheduler.repositories.ecommercehistory

import it.pagopa.ecommerce.commons.documents.BaseTransactionView
import it.pagopa.ecommerce.transactions.scheduler.utils.MigrationUtils.Companion.executeBestEffortBulkPipeline
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.mongodb.core.BulkOperations
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Repository
class TransactionsViewHistoryBulkOperations(
    @param:Qualifier("ecommerceHistoryReactiveMongoTemplate")
    private val reactiveMongoTemplate: ReactiveMongoTemplate
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun bulkInsert(views: Flux<BaseTransactionView>): Flux<BaseTransactionView> {
        return views
            .collectList()
            .flatMap { items -> executeBulkInsert(items) }
            .flatMapIterable { it }
    }

    private fun executeBulkInsert(
        views: List<BaseTransactionView>
    ): Mono<List<BaseTransactionView>> {
        if (views.isEmpty()) return Mono.empty()

        val bulkOps =
            reactiveMongoTemplate.bulkOps(
                BulkOperations.BulkMode.UNORDERED,
                BaseTransactionView::class.java
            ).insert(views)



        return executeBestEffortBulkPipeline(bulkOps, views, "Bulk upsert")
    }
}
