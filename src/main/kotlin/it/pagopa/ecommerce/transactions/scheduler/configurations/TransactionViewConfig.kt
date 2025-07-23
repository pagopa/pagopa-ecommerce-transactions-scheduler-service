package it.pagopa.ecommerce.transactions.scheduler.configurations

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Configuration for transaction view collection management. Controls whether the service updates
 * the MongoDB transaction-view collection.
 */
@Configuration
class TransactionViewConfig {

    /**
     * Configuration bean for transaction view update feature flag
     *
     * @param transactionViewUpdateEnabled
     * - boolean flag to enable/disable transaction view updates
     * @return the configuration value
     */
    @Bean
    fun transactionViewUpdateEnabled(
        @Value("\${transactionview.update.enabled}") transactionViewUpdateEnabled: Boolean
    ): Boolean = transactionViewUpdateEnabled
}
