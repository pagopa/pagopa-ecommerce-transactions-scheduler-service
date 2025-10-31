package it.pagopa.ecommerce.transactions.scheduler.deadletter

import com.azure.spring.messaging.AzureHeaders
import com.azure.spring.messaging.checkpoint.Checkpointer
import it.pagopa.ecommerce.commons.queues.StrictJsonSerializerProvider
import it.pagopa.ecommerce.transactions.scheduler.repositories.ecommerce.DeadLetterEventRepository
import it.pagopa.ecommerce.transactions.scheduler.services.TransactionInfoService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.integration.annotation.ServiceActivator
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Service

@Service
class NotificationDeadLetterConsumer(
    @Autowired val transactionInfoService: TransactionInfoService,
    @Autowired val deadLetterEventRepository: DeadLetterEventRepository,
    @Autowired val strictSerializerProviderV2: StrictJsonSerializerProvider,
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
            checkPointer = checkPointer,
            transactionInfoService = transactionInfoService,
            strictSerializerProviderV2 = strictSerializerProviderV2
        )
}
