package it.pagopa.ecommerce.transactions.scheduler.configurations

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "migration.transaction.write")
data class TransactionMigrationWriteServiceConfig(
    val eventstore: WriteSettings,
    val transactionsView: WriteSettings
)

data class WriteSettings(val ttlSeconds: Int)
