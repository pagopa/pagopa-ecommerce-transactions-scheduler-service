package it.pagopa.ecommerce.transactions.scheduler.repositories.ecommerce

import com.mongodb.MongoBulkWriteException
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent
import kotlin.collections.forEach
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.mongodb.core.BulkOperations
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Repository
class EventStoreBulkOperations(
    @param:Qualifier("ecommerceReactiveMongoTemplate")
    private val reactiveMongoTemplate: ReactiveMongoTemplate
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun bulkUpdateTtl(
        events: Flux<BaseTransactionEvent<*>>,
        ttlDate: Long
    ): Flux<BaseTransactionEvent<*>> {
        return events
            .collectList()
            .flatMap { items -> executeBulkUpdateTtl(items, ttlDate) }
            .flatMapIterable { it }
    }

    private fun executeBulkUpdateTtl(
        items: List<BaseTransactionEvent<*>>,
        ttlDate: Long
    ): Mono<List<BaseTransactionEvent<*>>> {
        if (items.isEmpty()) return Mono.just(emptyList())

        val bulkOps =
            reactiveMongoTemplate.bulkOps(
                BulkOperations.BulkMode.UNORDERED,
                BaseTransactionEvent::class.java
            )

        // Queue up the updates
        items.forEach { event ->
            bulkOps.updateOne(
                Query.query(Criteria.where("_id").`is`(event.id)),
                Update().set("ttl", ttlDate)
            )
        }

        return bulkOps
            .execute()
            .map { result ->
                if (result.modifiedCount < items.size) {
                    logger.warn(
                        "Bulk TTL update: ${result.modifiedCount} updated out of ${items.size} submitted."
                    )
                }
                items
            }
            .onErrorResume(DataIntegrityViolationException::class.java) { ex ->
                val mongoEx = ex.cause as? MongoBulkWriteException
                if (mongoEx != null) {
                    // Filter out failed items
                    val failedIndexes = mongoEx.writeErrors.map { it.index }.toSet()
                    val survivors =
                        items.filterIndexed { index, _ -> !failedIndexes.contains(index) }

                    logger.warn(
                        "Bulk TTL update partial failure. ${failedIndexes.size} failed, ${survivors.size} succeeded."
                    )
                    Mono.just(survivors)
                } else {
                    logger.error("Bulk TTL update failed completely", ex)
                    Mono.empty()
                }
            }
    }
}
