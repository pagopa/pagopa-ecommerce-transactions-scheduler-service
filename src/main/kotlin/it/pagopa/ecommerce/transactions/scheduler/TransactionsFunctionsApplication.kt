package it.pagopa.ecommerce.transactions.scheduler

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.integration.config.EnableIntegration

@SpringBootApplication
@EnableIntegration
class TransactionsFunctionsApplication

fun main(args: Array<String>) {
    runApplication<TransactionsFunctionsApplication>(*args)
}
