package it.pagopa.ecommerce.transactions.scheduler.deadletter

import com.azure.spring.messaging.checkpoint.Checkpointer
import it.pagopa.ecommerce.commons.documents.DeadLetterEvent
import it.pagopa.ecommerce.transactions.scheduler.TransactionSchedulerTestUtil
import it.pagopa.ecommerce.transactions.scheduler.configurations.QueuesConsumerConfig
import it.pagopa.ecommerce.transactions.scheduler.repositories.DeadLetterEventRepository
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.reactor.mono
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class NotificationDeadLetterConsumerTest {

    private val queueConsumerConfig = QueuesConsumerConfig()
    private val transactionInfoBuilder: TransactionInfoBuilder = mock()
    private val deadLetterEventRepository: DeadLetterEventRepository = mock()
    private val strictJsonSerializerProvider = queueConsumerConfig.strictSerializerProviderV2()
    private val queueName = "notification-dead-letter-queue"
    private val checkPointer: Checkpointer = mock()
    private val deadLetterArgumentCaptor: KArgumentCaptor<DeadLetterEvent> =
        argumentCaptor<DeadLetterEvent>()
    private val notificationDeadLetterConsumer =
        NotificationDeadLetterConsumer(
            deadLetterEventRepository = deadLetterEventRepository,
            queueName = queueName,
            transactionInfoBuilder = transactionInfoBuilder,
            strictSerializerProviderV2 = strictJsonSerializerProvider
        )

    @Test
    fun `Should dequeue event from dead letter successfully saving it into dead letter queue`() {
        val event = TransactionSchedulerTestUtil.getEventJsonString()
        val payload = event.toByteArray(StandardCharsets.UTF_8)
        given(checkPointer.success()).willReturn(Mono.empty())
        given(deadLetterEventRepository.save(deadLetterArgumentCaptor.capture())).willAnswer {
            mono { it.arguments[0] }
        }
        given(transactionInfoBuilder.getTransactionInfoByTransactionId(any())).willAnswer {
            mono { TransactionSchedulerTestUtil.buildNpgTransactionInfo(it.arguments[0] as String) }
        }
        StepVerifier.create(
                notificationDeadLetterConsumer.messageReceiver(
                    payload = payload,
                    checkPointer = checkPointer
                )
            )
            .expectNext(Unit)
            .verifyComplete()
        val capturedDeadLetterEvent = deadLetterArgumentCaptor.firstValue
        assertEquals(event, capturedDeadLetterEvent.data)
        assertEquals(queueName, capturedDeadLetterEvent.queueName)
        verify(checkPointer, times(1)).success()
        verify(deadLetterEventRepository, times(1)).save(any())
        verify(checkPointer, times(0)).failure()
    }

    @Test
    fun `Should handle error saving event to collection`() {
        val event = TransactionSchedulerTestUtil.getEventJsonString()
        val payload = event.toByteArray(StandardCharsets.UTF_8)
        given(checkPointer.success()).willReturn(Mono.empty())
        given(checkPointer.failure()).willReturn(Mono.empty())
        given(deadLetterEventRepository.save(deadLetterArgumentCaptor.capture())).willReturn {
            Mono.error(RuntimeException("Error saving event to queue"))
        }
        given(transactionInfoBuilder.getTransactionInfoByTransactionId(any())).willAnswer {
            mono { TransactionSchedulerTestUtil.buildNpgTransactionInfo(it.arguments[0] as String) }
        }
        StepVerifier.create(
                notificationDeadLetterConsumer.messageReceiver(
                    payload = payload,
                    checkPointer = checkPointer
                )
            )
            .expectNext(Unit)
            .verifyComplete()
        verify(checkPointer, times(1)).success()
        verify(deadLetterEventRepository, times(1)).save(any())
        verify(checkPointer, times(1)).failure()
    }

    @Test
    fun `Should not save event to collection when an error occurs performing success check point`() {
        val event = TransactionSchedulerTestUtil.getEventJsonString()
        val payload = event.toByteArray(StandardCharsets.UTF_8)
        given(checkPointer.success())
            .willReturn(Mono.error(RuntimeException("Error performing checkpoint")))
        given(checkPointer.failure()).willReturn(Mono.empty())
        given(deadLetterEventRepository.save(deadLetterArgumentCaptor.capture())).willAnswer {
            mono { it.arguments[0] }
        }
        given(transactionInfoBuilder.getTransactionInfoByTransactionId(any())).willAnswer {
            mono { TransactionSchedulerTestUtil.buildNpgTransactionInfo(it.arguments[0] as String) }
        }
        StepVerifier.create(
                notificationDeadLetterConsumer.messageReceiver(
                    payload = payload,
                    checkPointer = checkPointer
                )
            )
            .expectNext(Unit)
            .verifyComplete()
        verify(checkPointer, times(1)).success()
        verify(deadLetterEventRepository, times(0)).save(any())
        verify(checkPointer, times(1)).failure()
    }

    @Test
    fun `Should return error for error performing checkPointer failure`() {
        val event = TransactionSchedulerTestUtil.getEventJsonString()
        val payload = event.toByteArray(StandardCharsets.UTF_8)
        given(checkPointer.success())
            .willReturn(Mono.error(RuntimeException("Error performing checkpoint success")))
        given(checkPointer.failure())
            .willReturn(Mono.error(RuntimeException("Error performing checkpoint failure")))
        given(deadLetterEventRepository.save(deadLetterArgumentCaptor.capture())).willAnswer {
            mono { it.arguments[0] }
        }
        given(transactionInfoBuilder.getTransactionInfoByTransactionId(any())).willAnswer {
            mono { TransactionSchedulerTestUtil.buildNpgTransactionInfo(it.arguments[0] as String) }
        }
        StepVerifier.create(
                notificationDeadLetterConsumer.messageReceiver(
                    payload = payload,
                    checkPointer = checkPointer
                )
            )
            .expectError(RuntimeException::class.java)
            .verify()
        verify(checkPointer, times(1)).success()
        verify(deadLetterEventRepository, times(0)).save(any())
        verify(checkPointer, times(1)).failure()
    }
}
