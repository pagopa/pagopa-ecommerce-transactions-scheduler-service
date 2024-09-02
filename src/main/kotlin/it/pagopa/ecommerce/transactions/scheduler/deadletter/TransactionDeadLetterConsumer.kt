package it.pagopa.ecommerce.transactions.scheduler.deadletter

import com.azure.spring.messaging.AzureHeaders
import com.azure.spring.messaging.checkpoint.Checkpointer
import it.pagopa.ecommerce.commons.queues.StrictJsonSerializerProvider
import it.pagopa.ecommerce.transactions.scheduler.repositories.DeadLetterEventRepository
import it.pagopa.ecommerce.transactions.scheduler.services.TransactionInfoService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.integration.annotation.ServiceActivator
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Service

@Service
class TransactionDeadLetterConsumer(
    @Autowired val transactionInfoService: TransactionInfoService,
    @Autowired val deadLetterEventRepository: DeadLetterEventRepository,
    @Autowired val strictSerializerProviderV2: StrictJsonSerializerProvider,
    @Value("\${deadLetterListener.transaction.queueName}") val queueName: String
) {

    @ServiceActivator(inputChannel = "transactionDeadLetterChannel", outputChannel = "nullChannel")
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
