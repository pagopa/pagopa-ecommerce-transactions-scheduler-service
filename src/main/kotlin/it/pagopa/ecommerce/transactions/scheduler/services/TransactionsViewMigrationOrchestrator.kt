package it.pagopa.ecommerce.transactions.scheduler.services

import it.pagopa.ecommerce.transactions.scheduler.utils.CommonTracingUtils
import it.pagopa.ecommerce.transactions.scheduler.utils.CommonTracingUtils.Companion.ECOMMERCE_MIGRATION_SPAN_NAME
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.kotlin.core.util.function.component1
import reactor.kotlin.core.util.function.component2
import reactor.util.function.Tuple2
import reactor.util.function.Tuples

@Service
class TransactionsViewMigrationOrchestrator(
    @param:Autowired private val transactionMigrationQueryService: TransactionMigrationQueryService,
    @param:Autowired private val transactionMigrationWriteService: TransactionMigrationWriteService,
    @param:Autowired private val commonTracingUtils: CommonTracingUtils
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun createMigrationPipeline(): Mono<Tuple2<Long, Long>> {
        logger.info("transactions-view migration process started")
        return transactionMigrationQueryService
            .findEligibleTransactions()
            .doOnNext { tx -> logger.debug("Processing transaction: ${tx.transactionId}") }
            .transform { tx -> transactionMigrationWriteService.writeTransactionViews(tx) }
            .transform { tx -> transactionMigrationWriteService.updateViewsTtl(tx) }
            .count()
            .elapsed()
            .map { (elapsedMs, processedEvents) ->
                commonTracingUtils.addSpan(
                    ECOMMERCE_MIGRATION_SPAN_NAME,
                    commonTracingUtils.getIterationSpanAttributes(elapsedMs, processedEvents)
                )
                Tuples.of(elapsedMs, processedEvents)
            }
            .doOnSuccess { (elapsedMs, processedEventsCount) ->
                logger.info(
                    "transactions-view migration process completed. Processed $processedEventsCount items in $elapsedMs ms"
                )
            }
            .onErrorResume { error ->
                logger.error("transactions-view migration process failed", error)
                Mono.empty()
            }
    }

    fun runMigration() {
        this.createMigrationPipeline().subscribe()
    }
}
