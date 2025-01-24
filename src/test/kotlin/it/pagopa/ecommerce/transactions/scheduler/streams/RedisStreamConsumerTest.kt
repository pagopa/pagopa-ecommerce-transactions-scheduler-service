package it.pagopa.ecommerce.transactions.scheduler.streams

import com.fasterxml.jackson.databind.ObjectMapper
import it.pagopa.ecommerce.transactions.scheduler.configurations.RedisStreamEventControllerConfigs
import it.pagopa.ecommerce.transactions.scheduler.services.InboundChannelAdapterLifecycleHandlerService
import it.pagopa.ecommerce.transactions.scheduler.streams.commands.EventDispatcherReceiverCommand
import it.pagopa.generated.scheduler.server.model.DeploymentVersionDto
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Stream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.kotlin.*
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.data.redis.connection.stream.ObjectRecord
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.hash.Jackson2HashMapper
import org.springframework.data.redis.stream.StreamReceiver
import reactor.core.publisher.Flux
import reactor.test.StepVerifier

class RedisStreamConsumerTest {
    private val inboundChannelAdapterLifecycleHandlerService:
        InboundChannelAdapterLifecycleHandlerService =
        mock()
    private val deploymentVersionDto = DeploymentVersionDto.PROD
    private val redisStreamReceiver:
        StreamReceiver<String, ObjectRecord<String, LinkedHashMap<*, *>>> =
        mock()
    private val redisStreamConf =
        RedisStreamEventControllerConfigs(
            streamKey = "streamKey",
            consumerNamePrefix = "consumerNamePrefix"
        )
    private val redisStreamConsumer =
        RedisStreamConsumer(
            deploymentVersion = deploymentVersionDto,
            redisStreamConf = redisStreamConf,
            redisStreamReceiver = redisStreamReceiver,
            inboundChannelAdapterLifecycleHandlerService =
                inboundChannelAdapterLifecycleHandlerService
        )

    companion object {
        private val objectMapper = ObjectMapper()

        @JvmStatic
        fun `event dispatcher receiver command method source`(): Stream<Arguments> =
            Stream.of(
                Arguments.of(
                    EventDispatcherReceiverCommand(
                        receiverCommand = EventDispatcherReceiverCommand.ReceiverCommand.START,
                        version = DeploymentVersionDto.PROD
                    ),
                    "start"
                ),
                Arguments.of(
                    EventDispatcherReceiverCommand(
                        receiverCommand = EventDispatcherReceiverCommand.ReceiverCommand.STOP,
                        version = DeploymentVersionDto.PROD
                    ),
                    "stop"
                ),
                Arguments.of(
                    EventDispatcherReceiverCommand(
                        receiverCommand = EventDispatcherReceiverCommand.ReceiverCommand.START,
                        version = null
                    ),
                    "start"
                ),
                Arguments.of(
                    EventDispatcherReceiverCommand(
                        receiverCommand = EventDispatcherReceiverCommand.ReceiverCommand.STOP,
                        version = null
                    ),
                    "stop"
                ),
            )
    }

    @ParameterizedTest
    @MethodSource("event dispatcher receiver command method source")
    fun `Should process event dispatcher receiver command from stream`(
        receivedEvent: EventDispatcherReceiverCommand,
        expectedCommandSend: String
    ) {
        // test
        redisStreamConsumer.handleEventReceiverCommand(receivedEvent)
        // verifications
        verify(inboundChannelAdapterLifecycleHandlerService, times(1))
            .invokeCommandForAllEndpoints(expectedCommandSend)
    }

    @Test
    fun `Should ignore events that does not target current deployment`() {
        val stagingEvent =
            EventDispatcherReceiverCommand(
                receiverCommand = EventDispatcherReceiverCommand.ReceiverCommand.STOP,
                version = DeploymentVersionDto.STAGING
            )
        // test
        redisStreamConsumer.handleEventReceiverCommand(stagingEvent)
        // verifications
        verify(inboundChannelAdapterLifecycleHandlerService, times(0))
            .invokeCommandForAllEndpoints(any())
    }

    @Test
    fun `Should receive and parse event from redis stream`() {
        // pre-requisites
        val expectedCommandSend = "start"
        val serializedEvent =
            java.util.LinkedHashMap(
                Jackson2HashMapper(objectMapper, true)
                    .toHash(
                        EventDispatcherReceiverCommand(
                            receiverCommand = EventDispatcherReceiverCommand.ReceiverCommand.START,
                            version = DeploymentVersionDto.PROD
                        )
                    )
            ) as LinkedHashMap<*, *>
        val receivedEvent = ObjectRecord.create(redisStreamConf.streamKey, serializedEvent)
        given(redisStreamReceiver.receive(any())).willReturn(Flux.just(receivedEvent))
        doNothing()
            .`when`(inboundChannelAdapterLifecycleHandlerService)
            .invokeCommandForAllEndpoints(any())
        // test
        redisStreamConsumer.onApplicationEvent(
            ApplicationReadyEvent(SpringApplication(), null, null, null)
        )
        // verifications
        verify(redisStreamReceiver, timeout(5000).times(1))
            .receive(
                StreamOffset.create(redisStreamConf.streamKey, ReadOffset.from(RecordId.of(0, 0)))
            )
        verify(inboundChannelAdapterLifecycleHandlerService, timeout(5000).times(1))
            .invokeCommandForAllEndpoints(expectedCommandSend)
    }

    @Test
    fun `Should recover from errors receiving events`() {
        // pre-requisites
        val serializedEvent =
            java.util.LinkedHashMap(
                Jackson2HashMapper(objectMapper, true)
                    .toHash(
                        EventDispatcherReceiverCommand(
                            receiverCommand = EventDispatcherReceiverCommand.ReceiverCommand.START,
                            version = DeploymentVersionDto.PROD
                        )
                    )
            ) as LinkedHashMap<*, *>
        val receivedEvent = ObjectRecord.create(redisStreamConf.streamKey, serializedEvent)
        val redisConnectionErrors = 2
        val atomicInteger = AtomicInteger(0)
        given(redisStreamReceiver.receive(any())).willAnswer {
            val currentAttempt = atomicInteger.addAndGet(1)
            val mockFailure = currentAttempt <= redisConnectionErrors
            println(
                "Reconnection attempt: [$currentAttempt], total redis connection errors:[$redisConnectionErrors], mockFailure -> $mockFailure"
            )
            if (mockFailure) {
                Flux.error(RuntimeException("Redis connection error"))
            } else {
                Flux.just(receivedEvent)
            }
        }
        doNothing()
            .`when`(inboundChannelAdapterLifecycleHandlerService)
            .invokeCommandForAllEndpoints(any())
        // test
        StepVerifier.create(redisStreamConsumer.eventStreamPipelineWithRetry())
            .expectNext(receivedEvent)
            .verifyComplete()
        // verifications
        verify(redisStreamReceiver, times(redisConnectionErrors + 1))
            .receive(
                StreamOffset.create(redisStreamConf.streamKey, ReadOffset.from(RecordId.of(0, 0)))
            )
    }
}
