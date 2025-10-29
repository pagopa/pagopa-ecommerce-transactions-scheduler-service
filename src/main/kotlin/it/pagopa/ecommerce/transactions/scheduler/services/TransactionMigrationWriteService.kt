package it.pagopa.ecommerce.transactions.scheduler.services

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent
import it.pagopa.ecommerce.commons.documents.BaseTransactionView
import it.pagopa.ecommerce.transactions.scheduler.repositories.ecommercehistory.TransactionsEventStoreHistoryRepository
import it.pagopa.ecommerce.transactions.scheduler.repositories.ecommercehistory.TransactionsViewHistoryRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Service
class TransactionMigrationWriteService(
    @Autowired private val eventHistoryRepository: TransactionsEventStoreHistoryRepository<*>,
    @Autowired private val viewHistoryRepository: TransactionsViewHistoryRepository
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Migrates events to history database.
     * @return Flux of successfully migrated events
     */
    fun writeEvents(events: Flux<BaseTransactionEvent<*>>): Flux<BaseTransactionEvent<*>> {
        return events
            .flatMap { event ->
                (eventHistoryRepository as TransactionsEventStoreHistoryRepository<Any>)
                    .save(event as BaseTransactionEvent<Any>)
                    .doOnSuccess { logger.info("Successfully migrated event with id: ${it.id}") }
                    .doOnError { error ->
                        logger.error("Failed to migrate event with id: ${event.id}", error)
                    }
                    .onErrorResume {
                        logger.warn("Skipping failed event migration for id: ${event.id}")
                        Mono.empty()
                    }
            }
            .map { it as BaseTransactionEvent<*> }
    }

    /**
     * Migrates transaction views to history database.
     * @return Flux of successfully migrated transaction views
     */
    fun writeTransactionViews(views: Flux<BaseTransactionView>): Flux<BaseTransactionView> {
        return views.flatMap { view ->
            viewHistoryRepository
                .save(view)
                .doOnSuccess {
                    logger.info(
                        "Successfully migrated transaction view with id: ${it.transactionId}"
                    )
                }
                .doOnError { error ->
                    logger.error("Failed to migrate view with id: ${view.transactionId}", error)
                }
                .onErrorResume {
                    logger.warn("Skipping failed view migration for id: ${view.transactionId}")
                    Mono.empty()
                }
        }
    }
}
