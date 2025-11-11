package it.pagopa.ecommerce.transactions.scheduler.services

import it.pagopa.ecommerce.commons.documents.BaseTransactionView
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.kotlin.core.util.function.component1
import reactor.kotlin.core.util.function.component2
import reactor.util.function.Tuple2

@Service
class TransactionsViewMigrationOrchestrator(
    @param:Autowired private val transactionMigrationQueryService: TransactionMigrationQueryService,
    @param:Autowired private val transactionMigrationWriteService: TransactionMigrationWriteService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun createMigrationPipeline(): Mono<Tuple2<Long, List<BaseTransactionView>>> {
        logger.info("transactions-view migration process started")
        return transactionMigrationQueryService
            .findEligibleTransactions()
            .doOnNext { tx -> logger.debug("Processing transaction: ${tx.transactionId}") }
            .transform { tx -> transactionMigrationWriteService.writeTransactionViews(tx) }
            .transform { tx -> transactionMigrationWriteService.updateViewsTtl(tx) }
            .collectList()
            .elapsed()
            .doOnSuccess { (elapsedMs, processedViews) ->
                logger.info(
                    "transactions-view migration process completed. Processed ${processedViews.size} items in $elapsedMs ms"
                )
            }
            .onErrorResume { error ->
                logger.error("transactions-view migration process failed", error)
                Mono.empty()
            }
    }

    fun runMigration(): Mono<Tuple2<Long, List<BaseTransactionView>>> {
        return this.createMigrationPipeline()
    }
}
