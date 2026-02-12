package it.pagopa.ecommerce.transactions.scheduler.repositories.ecommercehistory

import com.mongodb.MongoBulkWriteException
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent
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
import kotlin.collections.forEach

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

        return bulkOps
            .execute()
            .map {
                // CASE A: 100% Success
                events
            }
            .onErrorResume { ex ->
                // CASE B: Partial Success
                val mongoEx = extractMongoException(ex)
                if (mongoEx != null) {
                    // Failed items
                    val failedIndexes = mongoEx.writeErrors.map { it.index }.toSet()

                    // Filter out failed items
                    val survivors =
                        events.filterIndexed { index, _ -> !failedIndexes.contains(index) }
                    logger.warn(
                        "Bulk upsert partial failure. ${failedIndexes.size} failed, ${survivors.size} succeeded."
                    )
                    Mono.just(survivors)
                } else {
                    // CASE C: Total System Failure (Network down, DB down, etc)
                    logger.error("Bulk upsert failed completely", ex)
                    Mono.empty()
                }
            }
    }

    private fun extractMongoException(ex: Throwable): MongoBulkWriteException? {
        return when {
            ex is MongoBulkWriteException -> ex
            ex.cause is MongoBulkWriteException -> ex.cause as MongoBulkWriteException
            else -> null
        }
    }
}