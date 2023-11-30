package it.pagopa.ecommerce.transactions.scheduler.configurations

import com.azure.spring.integration.storage.queue.inbound.StorageQueueMessageSource
import com.azure.spring.messaging.storage.queue.core.StorageQueueTemplate
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.integration.annotation.InboundChannelAdapter
import org.springframework.integration.annotation.Poller

@Configuration
class QueuesConsumerConfig {

    @Bean
    @InboundChannelAdapter(
        channel = "transactionDeadLetterChannel",
        poller = [Poller(
            fixedDelay = "\${deadLetterListener.transaction.fixedDelay}",
            maxMessagesPerPoll = "\${deadLetterListener.transaction.maxMessagePerPoll}"
        )]
    )
    fun storageQueueTransactionDeadLetter(
        storageQueueTemplate: StorageQueueTemplate,
        @Value("\${deadLetterListener.transaction.fixedDelay.queueName}") queueNameClosureEvents: String
    ): StorageQueueMessageSource {
        return StorageQueueMessageSource(queueNameClosureEvents, storageQueueTemplate)
    }

    @Bean
    @InboundChannelAdapter(
        channel = "notificationDeadLetterChannel",
        poller = [Poller(
            fixedDelay = "\${deadLetterListener.notification.fixedDelay}",
            maxMessagesPerPoll = "\${deadLetterListener.notification.maxMessagePerPoll}"
        )]
    )
    fun storageQueueNotificationDeadLetter(
        storageQueueTemplate: StorageQueueTemplate,
        @Value("\${deadLetterListener.notification.fixedDelay.queueName}") queueNameClosureEvents: String
    ): StorageQueueMessageSource {
        return StorageQueueMessageSource(queueNameClosureEvents, storageQueueTemplate)
    }
}