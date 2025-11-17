package it.pagopa.ecommerce.transactions.scheduler.scheduledperations

import it.pagopa.ecommerce.transactions.scheduler.services.EventStoreMigrationOrchestrator
import it.pagopa.ecommerce.transactions.scheduler.services.SchedulerLockService
import java.time.Duration
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
    fun execute() {
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
                    .doOnSuccess { logger.debug("Lock released successfully") }
                    .doOnError { logger.error("Failed to release lock", it) }
                    .onErrorResume { Mono.empty() }
            }
            .onErrorResume { error ->
                logger.error("Job execution failed for eventstore-migration-batch", error)
                Mono.empty()
            }
            .subscribe()
    }
}
