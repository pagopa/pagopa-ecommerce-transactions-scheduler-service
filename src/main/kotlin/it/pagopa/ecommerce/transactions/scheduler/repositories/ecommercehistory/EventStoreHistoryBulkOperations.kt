package it.pagopa.ecommerce.transactions.scheduler.repositories.ecommercehistory

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent
import it.pagopa.ecommerce.transactions.scheduler.utils.MigrationUtils.Companion.executeBestEffortBulkPipeline
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.mongodb.core.BulkOperations
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Repository
class EventStoreHistoryBulkOperations(
    @param:Qualifier("ecommerceHistoryReactiveMongoTemplate")
    private val reactiveMongoTemplate: ReactiveMongoTemplate
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun bulkInsert(events: Flux<BaseTransactionEvent<*>>): Flux<BaseTransactionEvent<*>> {
        return events
            .collectList()
            .flatMap { items -> executeBulkInsert(items) }
            .flatMapIterable { it }
    }

    private fun executeBulkInsert(
        events: List<BaseTransactionEvent<*>>
    ): Mono<List<BaseTransactionEvent<*>>> {
        if (events.isEmpty()) return Mono.empty()

        val bulkOps =
            reactiveMongoTemplate.bulkOps(
                BulkOperations.BulkMode.UNORDERED,
                BaseTransactionEvent::class.java
            ).insert(events)

        return executeBestEffortBulkPipeline(bulkOps, events, "Bulk upsert")
    }
}
