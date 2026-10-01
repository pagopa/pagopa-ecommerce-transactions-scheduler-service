package it.pagopa.ecommerce.transactions.scheduler.services

import it.pagopa.ecommerce.commons.documents.BaseTransactionView
import it.pagopa.ecommerce.commons.mdcutilities.LogTracingUtils
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
class TransactionsViewMigrationOrchestrator(
    @param:Autowired private val transactionMigrationQueryService: TransactionMigrationQueryService,
    @param:Autowired private val transactionMigrationWriteService: TransactionMigrationWriteService,
    @param:Autowired private val openTelemetryUtils: OpenTelemetryUtils
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun createMigrationPipeline(): Mono<Tuple2<Long, MigrationTracingUtils.MigrationStats>> {
        return transactionMigrationQueryService
            .findEligibleTransactions()
            .transform { tx -> transactionMigrationWriteService.writeBulkTransactionViews(tx) }
            .transform { tx -> transactionMigrationWriteService.updateBulkViewsTtl(tx) }
            .reduce(MigrationTracingUtils.MigrationStats.empty()) { acc, tx ->
                MigrationTracingUtils.MigrationStats(acc.count + 1, getLastCreationDate(tx))
            }
            .elapsed()
            .map { (transactionsViewElapsedMs, transactionsViewMigrationStats) ->
                openTelemetryUtils.addSpanWithAttributes(
                    ECOMMERCE_MIGRATION_SPAN_NAME,
                    getIterationSpanAttributes(
                        transactionsViewElapsedMs,
                        transactionsViewMigrationStats.count,
                        "transactions-view",
                        transactionsViewMigrationStats.lastCreationDate
                    )
                )
                Tuples.of(transactionsViewElapsedMs, transactionsViewMigrationStats)
            }
            .doOnSuccess { (transactionsViewElapsedMs, transactionsViewMigrationStats) ->
                LogTracingUtils.loggerTracingUtils()
                    .success()
                    .details(
                        mapOf(
                            "processed_items" to transactionsViewMigrationStats.count.toString(),
                            "elapsed_millis" to transactionsViewElapsedMs.toString(),
                            "last_creation_date" to transactionsViewMigrationStats.lastCreationDate
                        )
                    )
                    .logInfo(logger, "Transactions-view migration process completed")
            }
            .onErrorResume { exception ->
                LogTracingUtils.loggerTracingUtils()
                    .failure()
                    .logErrorWithStackTrace(
                        logger,
                        exception,
                        "Transactions-view migration process failed"
                    )
                Mono.empty()
            }
    }

    private fun getLastCreationDate(transactionsView: BaseTransactionView): String {
        if (transactionsView is it.pagopa.ecommerce.commons.documents.v1.Transaction)
            return transactionsView.creationDate
        else if (transactionsView is it.pagopa.ecommerce.commons.documents.v2.Transaction)
            return transactionsView.creationDate

        return ""
    }

    fun runMigration(): Mono<Tuple2<Long, MigrationTracingUtils.MigrationStats>> {
        return this.createMigrationPipeline()
    }
}
