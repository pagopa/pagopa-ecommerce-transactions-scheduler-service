package it.pagopa.ecommerce.transactions.scheduler.services

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent
import it.pagopa.ecommerce.commons.documents.BaseTransactionView
import it.pagopa.ecommerce.transactions.scheduler.configurations.TransactionMigrationWriteServiceConfig
import it.pagopa.ecommerce.transactions.scheduler.repositories.ecommercehistory.TransactionsEventStoreHistoryRepository
import it.pagopa.ecommerce.transactions.scheduler.repositories.ecommercehistory.TransactionsViewHistoryRepository
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Service
class TransactionMigrationWriteService(
    @param:Autowired private val eventHistoryRepository: TransactionsEventStoreHistoryRepository,
    @param:Autowired private val viewHistoryRepository: TransactionsViewHistoryRepository,
    @Qualifier("ecommerceReactiveMongoTemplate")
    private val ecommerceMongoTemplate: ReactiveMongoTemplate,
    @param:Autowired
    private val transactionMigrationWriteServiceConfig: TransactionMigrationWriteServiceConfig
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Migrates events to history database, then updates TTL in batch. Logs the count of
     * successfully copied and TTL-updated documents.
     *
     * @return Mono<Void> that completes when migration finishes
     */
    fun writeEvents(events: Flux<BaseTransactionEvent<*>>): Mono<Void> {
        return events
            // Step 1: Copy all documents to history database (parallel, fast)
            .flatMap { event ->
                eventHistoryRepository
                    .save(event)
                    .doOnSuccess { logger.debug("Successfully copied event to history: ${it.id}") }
                    .doOnError { error ->
                        logger.error("Failed to copy event with id: ${event.id}", error)
                    }
                    .onErrorResume {
                        logger.warn("Skipping failed event migration for id: ${event.id}")
                        Mono.empty()
                    }
            }
            .map { it as BaseTransactionEvent<*> }
            .collectList() // Collect all successfully migrated events
            // Step 2: Batch update TTL for all migrated events in source database
            .flatMap { migratedEvents ->
                if (migratedEvents.isEmpty()) {
                    logger.info("No events were copied to history")
                    Mono.empty()
                } else {
                    logger.info("Successfully copied ${migratedEvents.size} events to history")
                    val eventIds = migratedEvents.map { it.id }
                    batchUpdateEventTtl(eventIds).onErrorResume { error ->
                        // Log TTL update failure
                        logger.error(
                            "Failed to update TTL for ${eventIds.size} events. " +
                                "Documents were copied but TTL update failed.",
                            error
                        )
                        Mono.empty() // Continue anyway
                    }
                }
            }
            .then()
    }

    /**
     * Migrates transaction views to history database, then updates TTL in batch. Logs the count of
     * successfully copied and TTL-updated documents.
     *
     * @return Mono<Void> that completes when migration finishes
     */
    fun writeTransactionViews(views: Flux<BaseTransactionView>): Mono<Void> {
        return views
            // Step 1: Copy all documents to history database (parallel, fast)
            .flatMap { view ->
                viewHistoryRepository
                    .save(view)
                    .doOnSuccess {
                        logger.debug("Successfully copied view to history: ${it.transactionId}")
                    }
                    .onErrorResume {
                        logger.warn("Skipping failed view migration for id: ${view.transactionId}")
                        Mono.empty()
                    }
            }
            .collectList() // Collect all successfully migrated views
            // Step 2: Batch update TTL for all migrated views in source database
            .flatMap { migratedViews ->
                if (migratedViews.isEmpty()) {
                    logger.info("No views were copied to history")
                    Mono.empty()
                } else {
                    logger.info("Successfully copied ${migratedViews.size} views to history")
                    val viewTransactionIds = migratedViews.map { it.transactionId }
                    batchUpdateViewTtl(viewTransactionIds).onErrorResume { error ->
                        // Log TTL update failure
                        logger.error(
                            "Failed to update TTL for ${viewTransactionIds.size} views. " +
                                "Documents were copied but TTL update failed.",
                            error
                        )
                        Mono.empty() // Continue anyway
                    }
                }
            }
            .then()
    }

    /**
     * Batch updates TTL for multiple events in the SOURCE database (ecommerce). Logs the count of
     * successfully updated documents.
     */
    private fun batchUpdateEventTtl(eventIds: List<String>): Mono<Void> {
        if (eventIds.isEmpty()) return Mono.empty()

        val query = Query.query(Criteria.where("_id").`in`(eventIds))
        val ttlDate =
            Instant.now()
                .plusSeconds(transactionMigrationWriteServiceConfig.eventstore.ttlSeconds.toLong())
        val update = Update().set("ttl", ttlDate)

        return ecommerceMongoTemplate
            .updateMulti(query, update, BaseTransactionEvent::class.java)
            .doOnSuccess { result ->
                logger.info(
                    "Batch TTL update for events: ${result.modifiedCount}/${eventIds.size} documents updated successfully"
                )
            }
            .doOnError { error ->
                logger.error("Batch TTL update failed for ${eventIds.size} events", error)
            }
            .then()
    }

    /**
     * Batch updates TTL for multiple views in the SOURCE database (ecommerce). Logs the count of
     * successfully updated documents.
     */
    private fun batchUpdateViewTtl(transactionIds: List<String>): Mono<Void> {
        if (transactionIds.isEmpty()) return Mono.empty()

        val query = Query.query(Criteria.where("_id").`in`(transactionIds))
        val ttlDate =
            Instant.now()
                .plusSeconds(
                    transactionMigrationWriteServiceConfig.transactionsView.ttlSeconds.toLong()
                )
        val update = Update().set("ttl", ttlDate)

        return ecommerceMongoTemplate
            .updateMulti(query, update, BaseTransactionView::class.java)
            .doOnSuccess { result ->
                logger.info(
                    "Batch TTL update for views: ${result.modifiedCount}/${transactionIds.size} documents updated successfully"
                )
            }
            .doOnError { error ->
                logger.error("Batch TTL update failed for ${transactionIds.size} views", error)
            }
            .then()
    }
}
