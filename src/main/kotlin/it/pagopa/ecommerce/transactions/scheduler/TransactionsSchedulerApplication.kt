package it.pagopa.ecommerce.transactions.scheduler

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.data.mongo.MongoReactiveDataAutoConfiguration
import org.springframework.boot.autoconfigure.mongo.MongoReactiveAutoConfiguration
import org.springframework.boot.runApplication
import org.springframework.integration.config.EnableIntegration
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(
    exclude = [MongoReactiveAutoConfiguration::class, MongoReactiveDataAutoConfiguration::class]
)
@EnableIntegration
@EnableScheduling
class TransactionsSchedulerApplication

fun main(args: Array<String>) {
    runApplication<TransactionsSchedulerApplication>(*args)
}
