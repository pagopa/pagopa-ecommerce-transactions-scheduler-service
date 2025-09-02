package it.pagopa.ecommerce.transactions.scheduler.configurations.redis

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import it.pagopa.ecommerce.transactions.scheduler.repositories.redis.eventreceivers.ReceiversStatus
import it.pagopa.ecommerce.transactions.scheduler.streams.commands.EventDispatcherReceiverCommand
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
    ): ReactiveRedisTemplate<String, EventDispatcherReceiverCommand> {
        val keySerializer = StringRedisSerializer()
        val valueSerializer =
            Jackson2JsonRedisSerializer(objectMapper, EventDispatcherReceiverCommand::class.java)

        val context =
            RedisSerializationContext.newSerializationContext<
                    String, EventDispatcherReceiverCommand>(keySerializer)
                .value(valueSerializer)
                .build()

        return ReactiveRedisTemplate(connectionFactory, context)
    }

    @Bean
    fun eventDispatcherReceiverStatusTemplateWrapper(
        connectionFactory: ReactiveRedisConnectionFactory
    ): ReactiveRedisTemplate<String, ReceiversStatus> {
        val keySerializer = StringRedisSerializer()
        val valueSerializer = Jackson2JsonRedisSerializer(objectMapper, ReceiversStatus::class.java)

        val context =
            RedisSerializationContext.newSerializationContext<String, ReceiversStatus>(
                    keySerializer
                )
                .value(valueSerializer)
                .build()

        return ReactiveRedisTemplate(connectionFactory, context)
    }
}
