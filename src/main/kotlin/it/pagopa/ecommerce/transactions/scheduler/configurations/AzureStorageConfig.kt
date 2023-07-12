package it.pagopa.ecommerce.transactions.scheduler.configurations

import com.azure.storage.queue.QueueClientBuilder
import it.pagopa.ecommerce.commons.client.QueueAsyncClient
import it.pagopa.ecommerce.commons.queues.StrictJsonSerializerProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AzureStorageConfig {
    @Bean
    fun expiredEventQueueAsyncClient(
        @Value("\${azurestorage.transient.connectionstring}") storageConnectionString: String,
        @Value("\${azurestorage.queues.transactionexpired.name}") queueEventInitName: String,
    ): QueueAsyncClient {
        val queueAsyncClient =
            QueueClientBuilder()
                .connectionString(storageConnectionString)
                .queueName(queueEventInitName)
                .buildAsyncClient()
        queueAsyncClient.createIfNotExists().block()
        return QueueAsyncClient(queueAsyncClient, StrictJsonSerializerProvider().createInstance())
    }
}
