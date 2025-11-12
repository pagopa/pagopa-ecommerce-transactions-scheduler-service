package it.pagopa.ecommerce.transactions.scheduler.scheduledperations

import it.pagopa.ecommerce.transactions.scheduler.services.SchedulerLockService
import it.pagopa.ecommerce.transactions.scheduler.services.TransactionsViewMigrationOrchestrator
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

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
            .doOnError {
                logger.error("Lock not acquired for transactions-view-migration-batch", it)
            }
            .flatMap { lockDocument ->
                transactionsViewMigrationOrchestrator
                    // run job/batch
                    .runMigration()
                    .then(Mono.just(lockDocument))
                    .onErrorResume { Mono.just(lockDocument) }
            }
            .flatMap { lockDocument ->
                schedulerLockService
                    // release lock (always runs)
                    .releaseJobLock(lockDocument)
                    .doOnSuccess { logger.debug("Lock released successfully") }
                    .doOnError { logger.error("Failed to release lock", it) }
                    .onErrorResume { Mono.empty() }
            }
            .onErrorResume { error ->
                logger.error("Job execution failed", error)
                Mono.empty()
            }
            .subscribe()
    }
}
