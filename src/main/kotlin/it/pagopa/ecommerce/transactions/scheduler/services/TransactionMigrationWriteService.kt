package it.pagopa.ecommerce.transactions.scheduler.services

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent
import it.pagopa.ecommerce.commons.documents.BaseTransactionView
import it.pagopa.ecommerce.commons.mdcutilities.LogTracingUtils
import it.pagopa.ecommerce.transactions.scheduler.configurations.TransactionMigrationWriteServiceConfig
import it.pagopa.ecommerce.transactions.scheduler.repositories.ecommerce.EventStoreBulkOperations
import it.pagopa.ecommerce.transactions.scheduler.repositories.ecommerce.TransactionsViewBulkOperations
import it.pagopa.ecommerce.transactions.scheduler.repositories.ecommercehistory.EventStoreHistoryBulkOperations
import it.pagopa.ecommerce.transactions.scheduler.repositories.ecommercehistory.TransactionsEventStoreHistoryRepository
import it.pagopa.ecommerce.transactions.scheduler.repositories.ecommercehistory.TransactionsViewHistoryBulkOperations
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
    private val transactionsViewHistoryBulkOperations: TransactionsViewHistoryBulkOperations,
    @param:Autowired private val eventStoreHistoryBulkOperations: EventStoreHistoryBulkOperations,
    @param:Autowired private val transactionsViewBulkOperations: TransactionsViewBulkOperations,
    @param:Autowired private val eventStoreBulkOperations: EventStoreBulkOperations,
    @param:Autowired
    @param:Qualifier("ecommerceReactiveMongoTemplate")
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
                    .insert(event)
                    .doOnSuccess {
                        LogTracingUtils.loggerTracingUtils()
                            .success()
                            .details(mapOf("event_id" to it.id))
                            .logDebug(logger, "Successfully copied event to history")
                    }
                    .onErrorResume { error ->
                        LogTracingUtils.loggerTracingUtils()
                            .failure()
                            .details(mapOf("event_id" to event.id))
                            .logErrorWithStackTrace(
                                logger,
                                error,
                                "Skipping failed event migration"
                            )
                        Mono.empty()
                    }
            }
            .map { it as BaseTransactionEvent<*> }
    }

    /**
     * Migrates events to history database.
     * @return Flux of successfully migrated events
     */
    fun writeBulkEvents(events: Flux<BaseTransactionEvent<*>>): Flux<BaseTransactionEvent<*>> {
        return eventStoreHistoryBulkOperations
            .bulkInsert(events)
            .doOnNext {
                LogTracingUtils.loggerTracingUtils()
                    .success()
                    .details(mapOf("event_id" to it.id))
                    .logDebug(logger, "Event migrated to history")
            }
            .onErrorResume { error ->
                LogTracingUtils.loggerTracingUtils()
                    .failure()
                    .logErrorWithStackTrace(logger, error, "Skipping failed events migration")
                Mono.empty()
            }
    }

    /**
     * Update ttls on the given eventstore documents
     * @return Flux of successfully updated events
     */
    fun updateEventsTtl(events: Flux<BaseTransactionEvent<*>>): Flux<BaseTransactionEvent<*>> {
        return events.filterWhen { event ->
            updateSingleEventTtl(event)
                .onErrorResume { error ->
                    LogTracingUtils.loggerTracingUtils()
                        .failure()
                        .dependency(LogTracingUtils.MONGO_DEPENDENCY)
                        .logErrorWithStackTrace(logger, error, "Failed to update TTL for event")
                    Mono.just(false)
                }
                .contextWrite { context ->
                    LogTracingUtils.enrichContextForEvent(
                        mapOf(
                            LogTracingUtils.AttributeKeys.CTX_TRANSACTION_ID to event.transactionId,
                            LogTracingUtils.AttributeKeys.CTX_EVENT_ID to event.id,
                            LogTracingUtils.AttributeKeys.CTX_EVENT_CODE to event.eventCode
                        ),
                        context
                    )
                }
        }
    }

    /**
     * Update ttls on the given eventstore documents
     * @return Flux of successfully updated events
     */
    fun updateBulkEventsTtl(events: Flux<BaseTransactionEvent<*>>): Flux<BaseTransactionEvent<*>> {
        return eventStoreBulkOperations.bulkUpdateTtl(
            events,
            transactionMigrationWriteServiceConfig.eventstore.ttlSeconds.toLong()
        )
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
                    LogTracingUtils.loggerTracingUtils()
                        .dependency(LogTracingUtils.MONGO_DEPENDENCY)
                        .success()
                        .logInfo(logger, "Updated TTL for event")
                } else {
                    LogTracingUtils.loggerTracingUtils()
                        .dependency(LogTracingUtils.MONGO_DEPENDENCY)
                        .failure()
                        .logWarn(logger, "Event not modified")
                }
                updated
            }
            .doOnError { error ->
                LogTracingUtils.loggerTracingUtils()
                    .failure()
                    .dependency(LogTracingUtils.MONGO_DEPENDENCY)
                    .logErrorWithStackTrace(logger, error, "Failed to update TTL for event")
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
                        LogTracingUtils.loggerTracingUtils()
                            .success()
                            .dependency(LogTracingUtils.MONGO_DEPENDENCY)
                            .details(mapOf("transaction_id" to it.transactionId))
                            .logInfo(logger, "Successfully copied view to history")
                    }
                    .onErrorResume { error ->
                        LogTracingUtils.loggerTracingUtils()
                            .failure()
                            .dependency(LogTracingUtils.MONGO_DEPENDENCY)
                            .details(mapOf("transaction_id" to view.transactionId))
                            .logError(logger, error, "Skipping failed view migration")
                        Mono.empty()
                    }
            }
    }

    /**
     * Migrates transaction views to history database.
     * @return Flux of successfully migrated views
     */
    fun writeBulkTransactionViews(views: Flux<BaseTransactionView>): Flux<BaseTransactionView> {
        return transactionsViewHistoryBulkOperations.bulkInsert(views).onErrorResume { error ->
            LogTracingUtils.loggerTracingUtils()
                .failure()
                .dependency(LogTracingUtils.MONGO_DEPENDENCY)
                .logErrorWithStackTrace(logger, error, "Skipping failed views migration")
            Mono.empty()
        }
    }

    /**
     * Update ttls on the given transactions-view documents
     * @return Flux of successfully updated views
     */
    fun updateViewsTtl(views: Flux<BaseTransactionView>): Flux<BaseTransactionView> {
        return views.filterWhen { view ->
            updateSingleViewTtl(view)
                .onErrorResume { error ->
                    LogTracingUtils.loggerTracingUtils()
                        .failure()
                        .dependency(LogTracingUtils.MONGO_DEPENDENCY)
                        .logErrorWithStackTrace(logger, error, "Failed to update TTL")
                    Mono.just(false)
                }
                .contextWrite { context ->
                    LogTracingUtils.enrichContextForEvent(
                        mapOf(
                            LogTracingUtils.AttributeKeys.CTX_TRANSACTION_ID to view.transactionId
                        ),
                        context
                    )
                }
        }
    }

    /**
     * Update ttls on the given eventstore documents
     * @return Flux of successfully updated events
     */
    fun updateBulkViewsTtl(views: Flux<BaseTransactionView>): Flux<BaseTransactionView> {
        return transactionsViewBulkOperations.bulkUpdateTtl(
            views,
            transactionMigrationWriteServiceConfig.transactionsView.ttlSeconds.toLong()
        )
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
                    LogTracingUtils.loggerTracingUtils()
                        .dependency(LogTracingUtils.MONGO_DEPENDENCY)
                        .success()
                        .logInfo(logger, "Updated TTL for view")
                } else {
                    LogTracingUtils.loggerTracingUtils()
                        .dependency(LogTracingUtils.MONGO_DEPENDENCY)
                        .failure()
                        .logWarn(logger, "View not modified")
                }
                updated
            }
            .doOnError { error ->
                LogTracingUtils.loggerTracingUtils()
                    .failure()
                    .dependency(LogTracingUtils.MONGO_DEPENDENCY)
                    .logErrorWithStackTrace(logger, error, "Failed to update TTL for view")
            }
    }
}
