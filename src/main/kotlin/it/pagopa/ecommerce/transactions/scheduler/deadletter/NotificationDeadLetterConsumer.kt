package it.pagopa.ecommerce.transactions.scheduler.deadletter

import com.azure.spring.messaging.AzureHeaders
import com.azure.spring.messaging.checkpoint.Checkpointer
import it.pagopa.ecommerce.transactions.scheduler.repositories.DeadLetterEventRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.integration.annotation.ServiceActivator
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Service

@Service
class NotificationDeadLetterConsumer(
    @Autowired val deadLetterEventRepository: DeadLetterEventRepository,
    @Value("\${deadLetterListener.notification.queueName}") val queueName: String
) {

    @ServiceActivator(inputChannel = "notificationDeadLetterChannel", outputChannel = "nullChannel")
    fun messageReceiver(
        @Payload payload: ByteArray,
        @Header(AzureHeaders.CHECKPOINTER) checkPointer: Checkpointer
    ) =
        writeEventToDeadLetterCollection(
            payload = payload,
            queueName = queueName,
            deadLetterEventRepository = deadLetterEventRepository,
            checkPointer = checkPointer
        )
}
