package it.pagopa.ecommerce.transactions.scheduler.configurations

import com.azure.storage.queue.QueueAsyncClient
import com.azure.storage.queue.QueueClientBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AzureStorageConfig {
    @Bean
    fun expiredEventQueueAsyncClient(
        @Value("\${azurestorage.connectionstring}") storageConnectionString: String,
        @Value("\${azurestorage.queues.transactionexpired.name}") queueEventInitName: String,
    ): QueueAsyncClient {
        val queueAsyncClient =
            QueueClientBuilder()
                .connectionString(storageConnectionString)
                .queueName(queueEventInitName)
                .buildAsyncClient()
        queueAsyncClient.createIfNotExists().block()
        return queueAsyncClient
    }
}
