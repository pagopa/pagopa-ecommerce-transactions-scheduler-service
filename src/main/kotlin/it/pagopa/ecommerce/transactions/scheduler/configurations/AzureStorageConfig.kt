package it.pagopa.ecommerce.transactions.scheduler.configurations

import com.azure.core.util.serializer.JsonSerializer
import com.azure.storage.queue.QueueClientBuilder
import it.pagopa.ecommerce.commons.client.QueueAsyncClient
import it.pagopa.ecommerce.commons.queues.QueueEvent
import it.pagopa.ecommerce.commons.queues.StrictJsonSerializerProvider
import it.pagopa.ecommerce.commons.queues.mixin.serialization.v1.QueueEventMixInEventCodeFieldDiscriminator
import it.pagopa.ecommerce.commons.queues.mixin.serialization.v2.QueueEventMixInClassFieldDiscriminator
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AzureStorageConfig {

    @Bean
    fun jsonSerializerV1(): JsonSerializer {
        return StrictJsonSerializerProvider()
            .addMixIn(
                QueueEvent::class.java,
                QueueEventMixInEventCodeFieldDiscriminator::class.java
            )
            .createInstance()
    }

    @Bean
    fun jsonSerializerV2(): JsonSerializer {
        return StrictJsonSerializerProvider()
            .addMixIn(QueueEvent::class.java, QueueEventMixInClassFieldDiscriminator::class.java)
            .createInstance()
    }

    @Bean("expiredEventQueueAsyncClientV1")
    fun expiredEventQueueAsyncClientV1(
        @Value("\${azurestorage.transient.connectionstring}") storageConnectionString: String,
        @Value("\${azurestorage.queues.transactionexpired.name}") queueEventInitName: String,
        jsonSerializerV1: JsonSerializer
    ): QueueAsyncClient {
        val queueAsyncClient =
            QueueClientBuilder()
                .connectionString(storageConnectionString)
                .queueName(queueEventInitName)
                .buildAsyncClient()
        queueAsyncClient.createIfNotExists().block()
        return QueueAsyncClient(queueAsyncClient, jsonSerializerV1)
    }

    @Bean("expiredEventQueueAsyncClientV2")
    fun expiredEventQueueAsyncClientV2(
        @Value("\${azurestorage.transient.connectionstring}") storageConnectionString: String,
        @Value("\${azurestorage.queues.transactionexpired.name}") queueEventInitName: String,
        jsonSerializerV2: JsonSerializer
    ): QueueAsyncClient {
        val queueAsyncClient =
            QueueClientBuilder()
                .connectionString(storageConnectionString)
                .queueName(queueEventInitName)
                .buildAsyncClient()
        queueAsyncClient.createIfNotExists().block()
        return QueueAsyncClient(queueAsyncClient, jsonSerializerV2)
    }
}
