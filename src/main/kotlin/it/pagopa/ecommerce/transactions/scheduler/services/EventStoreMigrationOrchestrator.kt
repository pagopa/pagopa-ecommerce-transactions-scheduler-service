package it.pagopa.ecommerce.transactions.scheduler.services

import it.pagopa.ecommerce.commons.utils.OpenTelemetryUtils
import it.pagopa.ecommerce.transactions.scheduler.utils.MigrationTracingUtils
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

    fun createMigrationPipeline(): Mono<Tuple2<Long, MigrationTracingUtils.MigrationStats>> {
        logger.info("eventstore migration process started")

        return transactionMigrationQueryService
            .findEligibleEvents()
            .doOnNext { tx -> logger.debug("Processing event: ${tx.id}") }
            .transform { tx -> transactionMigrationWriteService.writeEvents(tx) }
            .transform { tx -> transactionMigrationWriteService.updateEventsTtl(tx) }
            .reduce(MigrationTracingUtils.MigrationStats.empty()) { acc, tx ->
                MigrationTracingUtils.MigrationStats(acc.count + 1, tx.creationDate ?: "")
            }
            .elapsed()
            .map { (elapsedMs, migrationStats) ->
                openTelemetryUtils.addSpanWithAttributes(
                    ECOMMERCE_MIGRATION_SPAN_NAME,
                    getIterationSpanAttributes(
                        elapsedMs,
                        migrationStats.count,
                        "eventstore",
                        migrationStats.lastCreationDate
                    )
                )
                Tuples.of(elapsedMs, migrationStats)
            }
            .doOnSuccess { (elapsedMs, migrationStats) ->
                logger.info(
                    "eventstore migration process completed. Processed ${migrationStats.count} items in $elapsedMs ms. Last creation date ${migrationStats.lastCreationDate}"
                )
            }
            .onErrorResume { error ->
                logger.error("eventstore migration process failed", error)
                Mono.empty()
            }
    }

    fun runMigration(): Mono<Tuple2<Long, MigrationTracingUtils.MigrationStats>> {
        return this.createMigrationPipeline()
    }
}
