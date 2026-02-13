package it.pagopa.ecommerce.transactions.scheduler.repositories.ecommerce

import it.pagopa.ecommerce.commons.documents.BaseTransactionView
import it.pagopa.ecommerce.transactions.scheduler.utils.MigrationUtils.Companion.executeBestEffortBulkPipeline
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.mongodb.core.BulkOperations
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Repository
class TransactionsViewBulkOperations(
    @param:Qualifier("ecommerceReactiveMongoTemplate")
    private val reactiveMongoTemplate: ReactiveMongoTemplate
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun bulkUpdateTtl(views: Flux<BaseTransactionView>, ttlDate: Long): Flux<BaseTransactionView> {
        return views
            .collectList()
            .flatMap { items -> executeBulkUpdateTtl(items, ttlDate) }
            .flatMapIterable { it }
    }

    private fun executeBulkUpdateTtl(
        items: List<BaseTransactionView>,
        ttlDate: Long
    ): Mono<List<BaseTransactionView>> {
        if (items.isEmpty()) return Mono.just(emptyList())

        val bulkOps =
            reactiveMongoTemplate.bulkOps(
                BulkOperations.BulkMode.UNORDERED,
                BaseTransactionView::class.java
            )

        // Queue up the updates
        items.forEach { view ->
            bulkOps.updateOne(
                Query.query(Criteria.where("_id").`is`(view.transactionId)),
                Update().set("ttl", ttlDate)
            )
        }

        return executeBestEffortBulkPipeline(
            bulkOps = bulkOps,
            items = items,
            operationName = "Bulk TTL Update"
        ) { result, originalItems ->
            if (result.modifiedCount < originalItems.size) {
                logger.warn(
                    "Bulk TTL Update: ${result.modifiedCount} updated out of ${originalItems.size} submitted."
                )
            }
        }
    }
}
