package it.pagopa.ecommerce.transactions.scheduler.scheduledperations

import it.pagopa.ecommerce.transactions.scheduler.services.SchedulerLockService
import it.pagopa.ecommerce.transactions.scheduler.services.TransactionsViewMigrationOrchestrator
import java.time.Duration
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

@Component
class TransactionsViewMigrationBatch(
    @param:Autowired
    private val transactionsViewMigrationOrchestrator: TransactionsViewMigrationOrchestrator,
    @param:Autowired private val schedulerLockService: SchedulerLockService,
    @param:Value(
        "\${migration.transaction.batch.transactionsView.exclusiveLockDocument.ttlSeconds}"
    )
    private val lockTtlSeconds: Int
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${migration.transaction.batch.transactionsView.cronExpression}")
    suspend fun execute() {
        val lockTtl = Duration.ofSeconds(lockTtlSeconds.toLong())
        schedulerLockService
            // acquire lock
            .acquireJobLock(jobName = "transactions-view-migration-batch", ttl = lockTtl)
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
            // abort execution if execution take longer than job task lock duration
            .timeout(lockTtl)
            .onErrorResume { error ->
                logger.error("Job execution failed for transactions-view-migration-batch", error)
                Mono.empty()
            }
            .awaitSingleOrNull()
    }
}
