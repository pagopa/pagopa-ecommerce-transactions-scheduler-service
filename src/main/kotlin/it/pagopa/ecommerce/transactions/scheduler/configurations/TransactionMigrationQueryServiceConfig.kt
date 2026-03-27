package it.pagopa.ecommerce.transactions.scheduler.configurations

import java.time.LocalTime
import org.springframework.boot.context.properties.ConfigurationProperties

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
