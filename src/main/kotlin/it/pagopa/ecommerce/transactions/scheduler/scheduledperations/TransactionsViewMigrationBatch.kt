package it.pagopa.ecommerce.transactions.scheduler.scheduledperations

import it.pagopa.ecommerce.transactions.scheduler.services.TransactionsViewMigrationOrchestrator
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class TransactionsViewMigrationBatch(
    @param:Autowired private val transactionsViewMigrationOrchestrator: TransactionsViewMigrationOrchestrator
) {

    @Scheduled(cron = "\${migration.transaction.batch.transactionsView.cronExpression}")
    fun execute(){
        transactionsViewMigrationOrchestrator.runMigration()
    }

}