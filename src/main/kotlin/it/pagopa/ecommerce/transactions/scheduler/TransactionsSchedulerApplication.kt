package it.pagopa.ecommerce.transactions.scheduler

import it.pagopa.ecommerce.transactions.scheduler.configurations.TransactionMigrationQueryServiceConfig
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.data.mongo.MongoReactiveDataAutoConfiguration
import org.springframework.boot.autoconfigure.mongo.MongoReactiveAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.integration.config.EnableIntegration
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(
    exclude = [MongoReactiveAutoConfiguration::class, MongoReactiveDataAutoConfiguration::class]
)
@EnableConfigurationProperties(TransactionMigrationQueryServiceConfig::class)
@EnableIntegration
@EnableScheduling
class TransactionsSchedulerApplication

fun main(args: Array<String>) {
    runApplication<TransactionsSchedulerApplication>(*args)
}
