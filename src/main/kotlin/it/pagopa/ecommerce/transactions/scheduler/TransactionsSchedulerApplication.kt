package it.pagopa.ecommerce.transactions.scheduler

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.integration.config.EnableIntegration
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication @EnableIntegration @EnableScheduling class TransactionsSchedulerApplication

fun main(args: Array<String>) {
    runApplication<TransactionsSchedulerApplication>(*args)
}
