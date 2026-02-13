package it.pagopa.ecommerce.transactions.scheduler.repositories.ecommercehistory

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent
import it.pagopa.ecommerce.transactions.scheduler.utils.MigrationUtils.Companion.executeBestEffortBulkPipeline
import kotlin.collections.forEach
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
class EventStoreHistoryBulkOperations(
    @param:Qualifier("ecommerceHistoryReactiveMongoTemplate")
    private val reactiveMongoTemplate: ReactiveMongoTemplate
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun bulkUpsert(events: Flux<BaseTransactionEvent<*>>): Flux<BaseTransactionEvent<*>> {
        return events
            .collectList()
            .flatMap { items -> executeBulkUpsert(items) }
            .flatMapIterable { it }
    }

    private fun executeBulkUpsert(
        events: List<BaseTransactionEvent<*>>
    ): Mono<List<BaseTransactionEvent<*>>> {
        if (events.isEmpty()) return Mono.empty()

        val bulkOps =
            reactiveMongoTemplate.bulkOps(
                BulkOperations.BulkMode.UNORDERED,
                BaseTransactionEvent::class.java
            )

        events.forEach { event ->
            bulkOps.replaceOne(
                Query.query(Criteria.where("_id").`is`(event.id)),
                event,
                FindAndReplaceOptions.options().upsert()
            )
        }

        return executeBestEffortBulkPipeline(bulkOps, events, "Bulk upsert")
    }
}
