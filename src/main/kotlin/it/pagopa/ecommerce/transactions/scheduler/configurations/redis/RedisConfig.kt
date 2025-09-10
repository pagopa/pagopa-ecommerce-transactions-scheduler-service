package it.pagopa.ecommerce.transactions.scheduler.configurations.redis

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import it.pagopa.ecommerce.transactions.scheduler.repositories.redis.eventreceivers.ReceiversStatus
import it.pagopa.ecommerce.transactions.scheduler.streams.commands.EventDispatcherReceiverCommand
import java.time.Duration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.RedisSerializationContext
import org.springframework.data.redis.serializer.StringRedisSerializer

/** Redis templates wrapper configuration */
@Configuration
class RedisConfig {
    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    @Bean
    fun eventDispatcherCommandRedisTemplateWrapper(
        connectionFactory: ReactiveRedisConnectionFactory
    ): EventDispatcherCommandsTemplateWrapper {
        val keySerializer = StringRedisSerializer()
        val valueSerializer =
            Jackson2JsonRedisSerializer(objectMapper, EventDispatcherReceiverCommand::class.java)

        val context =
            RedisSerializationContext.newSerializationContext<
                    String, EventDispatcherReceiverCommand>(keySerializer)
                .value(valueSerializer)
                .build()

        /*
         * This redis template instance is to write events to Redis Stream through opsForStreams apis.
         * No document is written into cache.
         * Set TTL to 0 here will throw an error during writing operation to cache to enforce the fact that this
         * wrapper has to be used only to write to Redis Streams
         */
        return EventDispatcherCommandsTemplateWrapper(
            ReactiveRedisTemplate(connectionFactory, context),
            Duration.ZERO
        )
    }

    @Bean
    fun eventDispatcherReceiverStatusTemplateWrapper(
        connectionFactory: ReactiveRedisConnectionFactory
    ): EventDispatcherReceiverStatusTemplateWrapper {
        val keySerializer = StringRedisSerializer()
        val valueSerializer = Jackson2JsonRedisSerializer(objectMapper, ReceiversStatus::class.java)

        val context =
            RedisSerializationContext.newSerializationContext<String, ReceiversStatus>(
                    keySerializer
                )
                .value(valueSerializer)
                .build()
        return EventDispatcherReceiverStatusTemplateWrapper(
            ReactiveRedisTemplate(connectionFactory, context),
            Duration.ofMinutes(1)
        )
    }
}
