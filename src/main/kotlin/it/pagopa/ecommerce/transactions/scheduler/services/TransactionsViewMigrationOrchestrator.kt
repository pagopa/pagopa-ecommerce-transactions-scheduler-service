package it.pagopa.ecommerce.transactions.scheduler.services

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

@Service
class TransactionsViewMigrationOrchestrator(
    @param:Autowired private val transactionMigrationQueryService: TransactionMigrationQueryService,
    @param:Autowired private val transactionMigrationWriteService: TransactionMigrationWriteService
) : ApplicationListener<ApplicationReadyEvent>{
    private val logger = LoggerFactory.getLogger(javaClass)

    fun runMigration() {
        logger.info("transactions-view migration process started")
        transactionMigrationQueryService
            .findEligibleTransactions()
            .doOnNext { tx -> logger.debug("Processing transaction: ${tx.transactionId}") }
            .transform { transactionMigrationWriteService.writeTransactionViews(it) }
            .collectList()
            .doOnSuccess { processedViews ->
                logger.info(
                    "transactions-view migration process completed. Processed: ${processedViews.size}"
                )
            }
            .onErrorResume { error ->
                logger.error("transactions-view migration process failed", error)
                Mono.empty()
            }
            .subscribe()
    }

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        this.runMigration()
    }
}
