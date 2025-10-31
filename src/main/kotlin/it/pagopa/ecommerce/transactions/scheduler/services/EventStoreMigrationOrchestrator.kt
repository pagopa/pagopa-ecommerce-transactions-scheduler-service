package it.pagopa.ecommerce.transactions.scheduler.services

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.kotlin.core.util.function.component1
import reactor.kotlin.core.util.function.component2
import reactor.util.function.Tuple2

@Service
class EventStoreMigrationOrchestrator(
    @param:Autowired private val transactionMigrationQueryService: TransactionMigrationQueryService,
    @param:Autowired private val transactionMigrationWriteService: TransactionMigrationWriteService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun createMigrationPipeline(): Mono<Tuple2<Long, List<BaseTransactionEvent<*>>>> {
        logger.info("eventstore migration process started")
        return transactionMigrationQueryService
            .findEligibleEvents()
            .doOnNext { tx -> logger.debug("Processing transaction: ${tx.transactionId}") }
            .transform { tx -> transactionMigrationWriteService.writeEvents(tx) }
            .transform { tx -> transactionMigrationWriteService.updateEventsTtl(tx) }
            .collectList()
            .elapsed()
            .doOnSuccess { (elapsedMs, processedEvents) ->
                logger.info(
                    "eventstore migration process completed. Processed ${processedEvents.size} items in $elapsedMs ms"
                )
            }
            .onErrorResume { error ->
                logger.error("eventstore migration process failed", error)
                Mono.empty()
            }
    }

    fun runMigration() {
        this.createMigrationPipeline().subscribe()
    }
}
