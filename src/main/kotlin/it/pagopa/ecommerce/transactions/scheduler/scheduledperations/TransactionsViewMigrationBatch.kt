package it.pagopa.ecommerce.transactions.scheduler.scheduledperations

import it.pagopa.ecommerce.transactions.scheduler.services.SchedulerLockService
import it.pagopa.ecommerce.transactions.scheduler.services.TransactionsViewMigrationOrchestrator
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

@Component
class TransactionsViewMigrationBatch(
    @param:Autowired
    private val transactionsViewMigrationOrchestrator: TransactionsViewMigrationOrchestrator,
    @param:Autowired private val schedulerLockService: SchedulerLockService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${migration.transaction.batch.transactionsView.cronExpression}")
    fun execute() {
        schedulerLockService
            // acquire lock
            .acquireJobLock(jobName = "transactions-view-migration-batch")
            .doOnError { logger.error("Unable to start job without lock acquiring", it) }
            .flatMap { lockDocument ->
                transactionsViewMigrationOrchestrator
                    // run job/batch
                    .runMigration()
                    .doOnSuccess {
                        logger.info("Transactions view migration completed successfully")
                    }
                    .doOnError {
                        logger.error("Exception processing transactions view migration", it)
                    }
                    .doFinally {
                        schedulerLockService
                            // release lock (always runs)
                            .releaseJobLock(lockDocument)
                            .subscribeOn(Schedulers.boundedElastic())
                            .subscribe()
                    }
                    .then()
                    .onErrorResume { Mono.empty() }
            }
            .subscribe()
    }
}
