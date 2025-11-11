package it.pagopa.ecommerce.transactions.scheduler.services

import it.pagopa.ecommerce.commons.utils.OpenTelemetryUtils
import it.pagopa.ecommerce.transactions.scheduler.utils.MigrationTracingUtils.Companion.ECOMMERCE_MIGRATION_SPAN_NAME
import it.pagopa.ecommerce.transactions.scheduler.utils.MigrationTracingUtils.Companion.getIterationSpanAttributes
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.kotlin.core.util.function.component1
import reactor.kotlin.core.util.function.component2
import reactor.util.function.Tuple2
import reactor.util.function.Tuples

@Service
class EventStoreMigrationOrchestrator(
    @param:Autowired private val transactionMigrationQueryService: TransactionMigrationQueryService,
    @param:Autowired private val transactionMigrationWriteService: TransactionMigrationWriteService,
    @param:Autowired private val openTelemetryUtils: OpenTelemetryUtils
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun createMigrationPipeline(): Mono<Tuple2<Long, Long>> {
        logger.info("eventstore migration process started")
        return transactionMigrationQueryService
            .findEligibleEvents()
            .doOnNext { tx -> logger.debug("Processing event: ${tx.id}") }
            .transform { tx -> transactionMigrationWriteService.writeEvents(tx) }
            .transform { tx -> transactionMigrationWriteService.updateEventsTtl(tx) }
            .count()
            .elapsed()
            .map { (elapsedMs, processedEvents) ->
                openTelemetryUtils.addSpanWithAttributes(
                    ECOMMERCE_MIGRATION_SPAN_NAME,
                    getIterationSpanAttributes(elapsedMs, processedEvents, "eventstore")
                )
                Tuples.of(elapsedMs, processedEvents)
            }
            .doOnSuccess { (elapsedMs, processedEventsCount) ->
                logger.info(
                    "eventstore migration process completed. Processed $processedEventsCount items in $elapsedMs ms"
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
