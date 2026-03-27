package it.pagopa.ecommerce.transactions.scheduler.configurations

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.LocalTime

@ConfigurationProperties(prefix = "migration.transaction.query")
data class TransactionMigrationQueryServiceConfig(
    val eventstore: QuerySettings,
    val transactionsView: QuerySettings
)

data class QuerySettings(
    val cutoffMonthOffset: Int,
    val lowRate: Int,
    val highRate: Int,
    val rampUpDurationSeconds: Int,
    val burstStartWindow: LocalTime,
    val burstEndWindow: LocalTime
)
