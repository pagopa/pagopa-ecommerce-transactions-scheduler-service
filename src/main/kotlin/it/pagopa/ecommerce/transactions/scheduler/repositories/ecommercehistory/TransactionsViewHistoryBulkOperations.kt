package it.pagopa.ecommerce.transactions.scheduler.repositories.ecommercehistory

import it.pagopa.ecommerce.commons.documents.BaseTransactionView
import it.pagopa.ecommerce.transactions.scheduler.utils.MigrationUtils.Companion.executeBestEffortBulkPipeline
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.mongodb.core.BulkOperations
import org.springframework.data.mongodb.core.FindAndReplaceOptions
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Repository
class TransactionsViewHistoryBulkOperations(
    @param:Qualifier("ecommerceHistoryReactiveMongoTemplate")
    private val reactiveMongoTemplate: ReactiveMongoTemplate
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun bulkUpsert(views: Flux<BaseTransactionView>): Flux<BaseTransactionView> {
        return views
            .collectList()
            .flatMap { items -> executeBulkUpsert(items) }
            .flatMapIterable { it }
    }

    private fun executeBulkUpsert(
        views: List<BaseTransactionView>
    ): Mono<List<BaseTransactionView>> {
        if (views.isEmpty()) return Mono.empty()

        val bulkOps =
            reactiveMongoTemplate.bulkOps(
                BulkOperations.BulkMode.UNORDERED,
                BaseTransactionView::class.java
            )

        views.forEach { view ->
            bulkOps.replaceOne(
                Query.query(Criteria.where("_id").`is`(view.transactionId)),
                view,
                FindAndReplaceOptions.options().upsert()
            )
        }

        return executeBestEffortBulkPipeline(bulkOps, views, "Bulk upsert")
    }
}
