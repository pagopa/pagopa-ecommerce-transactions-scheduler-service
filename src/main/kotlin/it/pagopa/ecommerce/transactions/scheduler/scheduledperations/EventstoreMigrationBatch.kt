package it.pagopa.ecommerce.transactions.scheduler.scheduledperations

import it.pagopa.ecommerce.commons.mdcutilities.LogTracingUtils
import it.pagopa.ecommerce.transactions.scheduler.services.EventStoreMigrationOrchestrator
import it.pagopa.ecommerce.transactions.scheduler.services.SchedulerLockService
import java.time.Duration
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

@Component
class EventstoreMigrationBatch(
    @param:Autowired private val eventstoreMigrationOrchestrator: EventStoreMigrationOrchestrator,
    @param:Autowired private val schedulerLockService: SchedulerLockService,
    @param:Value("\${migration.transaction.batch.eventstore.exclusiveLockDocument.ttlSeconds}")
    private val lockTtlSeconds: Int
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${migration.transaction.batch.eventstore.cronExpression}")
    suspend fun execute() {
        val lockTtl = Duration.ofSeconds(lockTtlSeconds.toLong())
        schedulerLockService
            // acquire lock
            .acquireJobLock(jobName = "eventstore-migration-batch", ttl = lockTtl)
            .flatMap { lockDocument ->
                eventstoreMigrationOrchestrator
                    // run job/batch
                    .runMigration()
                    .then(Mono.just(lockDocument))
                    .onErrorResume { Mono.just(lockDocument) }
            }
            .flatMap { lockDocument ->
                schedulerLockService
                    // release lock (always runs)
                    .releaseJobLock(lockDocument)
                    .doOnSuccess {
                        LogTracingUtils.loggerTracingUtils()
                            .success()
                            .logDebug(logger, "Lock released successfully")
                    }
                    .doOnError {
                        LogTracingUtils.loggerTracingUtils()
                            .failure()
                            .logErrorWithStackTrace(logger, it, "Failed to release lock")
                    }
                    .onErrorResume { Mono.empty() }
            }
            // abort execution if execution take longer than job task lock duration
            .timeout(lockTtl)
            .onErrorResume { error ->
                LogTracingUtils.loggerTracingUtils()
                    .failure()
                    .logErrorWithStackTrace(
                        logger,
                        error,
                        "Job execution failed for eventstore-migration-batch"
                    )
                Mono.empty()
            }
            .awaitSingleOrNull()
    }
}
