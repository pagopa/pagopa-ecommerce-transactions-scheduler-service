package it.pagopa.ecommerce.transactions.scheduler.configurations

import it.pagopa.ecommerce.transactions.scheduler.utils.TimeBasedRate
import java.time.LocalTime
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "migration.transaction.query")
data class TransactionMigrationQueryServiceConfig(
    val eventstore: QuerySettings,
    val transactionsView: QuerySettings
) {
    val eventStoreTimeBasedRate = TimeBasedRate.fromQuerySettings(eventstore)
    val transactionsViewTimeBasedRate = TimeBasedRate.fromQuerySettings(transactionsView)
}

data class QuerySettings(
    val cutoffMonthOffset: Int,
    val lowRate: Int,
    val highRate: Int,
    val rampUpDurationSeconds: Int,
    val burstStartWindow: LocalTime,
    val burstEndWindow: LocalTime
)
