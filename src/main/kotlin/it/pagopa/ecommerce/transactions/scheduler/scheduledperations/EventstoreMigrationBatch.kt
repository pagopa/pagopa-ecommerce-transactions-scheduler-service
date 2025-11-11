package it.pagopa.ecommerce.transactions.scheduler.scheduledperations

import it.pagopa.ecommerce.transactions.scheduler.services.EventStoreMigrationOrchestrator
import it.pagopa.ecommerce.transactions.scheduler.services.SchedulerLockService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

@Component
class EventstoreMigrationBatch(
    @param:Autowired private val eventstoreMigrationOrchestrator: EventStoreMigrationOrchestrator,
    @param:Autowired private val schedulerLockService: SchedulerLockService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${migration.transaction.batch.eventstore.cronExpression}")
    fun execute() {
        schedulerLockService
            // acquire lock
            .acquireJobLock(jobName = "eventstore-migration-batch")
            .doOnError { logger.error("Lock not acquired for eventstore-migration-batch", it) }
            .flatMap { lockDocument ->
                eventstoreMigrationOrchestrator
                    // run job/batch
                    .runMigration()
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
