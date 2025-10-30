package it.pagopa.ecommerce.transactions.scheduler.services

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent
import it.pagopa.ecommerce.commons.documents.BaseTransactionView
import it.pagopa.ecommerce.transactions.scheduler.configurations.TransactionMigrationWriteServiceConfig
import it.pagopa.ecommerce.transactions.scheduler.repositories.ecommercehistory.TransactionsEventStoreHistoryRepository
import it.pagopa.ecommerce.transactions.scheduler.repositories.ecommercehistory.TransactionsViewHistoryRepository
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
    @param:Autowired
    @Qualifier("ecommerceReactiveMongoTemplate")
    private val ecommerceMongoTemplate: ReactiveMongoTemplate,
    @param:Autowired
    private val transactionMigrationWriteServiceConfig: TransactionMigrationWriteServiceConfig
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Migrates events to history database.
     * @return Flux of successfully migrated events
     */
    fun writeEvents(events: Flux<BaseTransactionEvent<*>>): Flux<BaseTransactionEvent<*>> {
        return events
            .flatMap { event ->
                eventHistoryRepository
                    .save(event)
                    .doOnSuccess { logger.debug("Successfully copied event to history: ${it.id}") }
                    .onErrorResume { error ->
                        logger.warn("Skipping failed event migration for id: ${event.id}", error)
                        Mono.empty()
                    }
            }
            .map { it as BaseTransactionEvent<*> }
    }

    /**
     * Update ttls on the given eventstore documents
     * @return Flux of successfully updated events
     */
    fun updateEventsTtl(events: Flux<BaseTransactionEvent<*>>): Flux<BaseTransactionEvent<*>> {
        return events.filterWhen { event ->
            updateSingleEventTtl(event).onErrorResume { error ->
                logger.error("Failed to update TTL for event: ${event.id}", error)
                Mono.just(false)
            }
        }
    }

    /**
     * Updates the ttl for a single event.
     * @return true if the update operation was successful
     */
    private fun updateSingleEventTtl(event: BaseTransactionEvent<*>): Mono<Boolean> {
        val query = Query.query(Criteria.where("_id").`is`(event.id))
        val ttlDate = transactionMigrationWriteServiceConfig.eventstore.ttlSeconds.toLong()
        val update = Update().set("ttl", ttlDate)

        return ecommerceMongoTemplate
            .updateFirst(query, update, BaseTransactionEvent::class.java)
            .map { result ->
                val updated = result.modifiedCount > 0
                if (updated) {
                    logger.debug("Updated TTL for event: ${event.id}")
                } else {
                    logger.warn("Event not modified: ${event.id}")
                }
                updated
            }
            .doOnError { error ->
                logger.error("Failed to update TTL for event: ${event.id}", error)
            }
    }

    /**
     * Migrates transaction views to history database.
     * @return Flux of successfully migrated views
     */
    fun writeTransactionViews(views: Flux<BaseTransactionView>): Flux<BaseTransactionView> {
        return views
            // step 1: copy all documents to history database
            .flatMap { view ->
                viewHistoryRepository
                    .save(view)
                    .doOnSuccess {
                        logger.debug("Successfully copied view to history: ${it.transactionId}")
                    }
                    .onErrorResume { error ->
                        logger.warn(
                            "Skipping failed view migration for id: ${view.transactionId}",
                            error
                        )
                        Mono.empty()
                    }
            }
    }

    /**
     * Update ttls on the given transactions-view documents
     * @return Flux of successfully updated views
     */
    fun updateViewsTtl(views: Flux<BaseTransactionView>): Flux<BaseTransactionView> {
        return views.filterWhen { view ->
            updateSingleViewTtl(view).onErrorResume { error ->
                logger.error("Failed to update TTL for view: ${view.transactionId}", error)
                Mono.just(false)
            }
        }
    }

    /**
     * Updates the ttl for a single view.
     * @return true if the update operation was successful
     */
    private fun updateSingleViewTtl(view: BaseTransactionView): Mono<Boolean> {
        val query = Query.query(Criteria.where("_id").`is`(view.transactionId))
        val ttlDate = transactionMigrationWriteServiceConfig.transactionsView.ttlSeconds.toLong()
        val update = Update().set("ttl", ttlDate)

        return ecommerceMongoTemplate
            .updateFirst(query, update, BaseTransactionView::class.java)
            .map { result ->
                val updated = result.modifiedCount > 0
                if (updated) {
                    logger.debug("Updated TTL for event: ${view.transactionId}")
                } else {
                    logger.warn("Event not modified: ${view.transactionId}")
                }
                updated
            }
            .doOnError { error ->
                logger.error("Failed to update TTL for event: ${view.transactionId}", error)
            }
    }
}
