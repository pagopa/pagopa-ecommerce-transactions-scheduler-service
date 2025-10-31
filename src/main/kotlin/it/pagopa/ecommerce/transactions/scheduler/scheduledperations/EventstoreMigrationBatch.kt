package it.pagopa.ecommerce.transactions.scheduler.scheduledperations

import it.pagopa.ecommerce.transactions.scheduler.services.EventStoreMigrationOrchestrator
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class EventstoreMigrationBatch(
    @param:Autowired private val eventstoreMigrationOrchestrator: EventStoreMigrationOrchestrator
) {

    @Scheduled(cron = "\${migration.transaction.batch.eventstore.cronExpression}")
    fun execute() {
        eventstoreMigrationOrchestrator.runMigration()
    }
}
