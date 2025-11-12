package it.pagopa.ecommerce.transactions.scheduler.configurations.redis

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import it.pagopa.ecommerce.commons.redis.reactivetemplatewrappers.ReactiveExclusiveLockDocumentWrapper
import it.pagopa.ecommerce.commons.repositories.ExclusiveLockDocument
import it.pagopa.ecommerce.transactions.scheduler.repositories.redis.eventreceivers.ReceiversStatus
import it.pagopa.ecommerce.transactions.scheduler.services.SchedulerLockService
import it.pagopa.ecommerce.transactions.scheduler.streams.commands.EventDispatcherReceiverCommand
import java.time.Duration
import org.springframework.beans.factory.annotation.Value
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

    @Bean(name = ["pendingBatchLockDocumentWrapper"])
    fun pendingBatchLockDocumentWrapper(
        reactiveRedisConnectionFactory: ReactiveRedisConnectionFactory,
        @Value("\${pendingBatch.exclusiveLockDocument.ttlSeconds}") pendingBatchLockTtlSeconds: Int
    ): ReactiveExclusiveLockDocumentWrapper {
        return createLockDocumentWrapper(reactiveRedisConnectionFactory, pendingBatchLockTtlSeconds)
    }

    @Bean(name = ["migrationBatchLockDocumentWrapper"])
    fun migrationBatchLockDocumentWrapper(
        reactiveRedisConnectionFactory: ReactiveRedisConnectionFactory,
        @Value("\${migrationBatch.exclusiveLockDocument.ttlSeconds}")
        migrationBatchLockTtlSeconds: Int
    ): ReactiveExclusiveLockDocumentWrapper {
        return createLockDocumentWrapper(
            reactiveRedisConnectionFactory,
            migrationBatchLockTtlSeconds
        )
    }

    @Bean(name = ["pendingBatchLockService"])
    fun pendingBatchLockService(
        pendingBatchLockDocumentWrapper: ReactiveExclusiveLockDocumentWrapper
    ): SchedulerLockService {
        return SchedulerLockService(pendingBatchLockDocumentWrapper)
    }

    @Bean(name = ["migrationBatchLockService"])
    fun migrationBatchLockService(
        migrationBatchLockDocumentWrapper: ReactiveExclusiveLockDocumentWrapper
    ): SchedulerLockService {
        return SchedulerLockService(migrationBatchLockDocumentWrapper)
    }

    private fun createLockDocumentWrapper(
        reactiveRedisConnectionFactory: ReactiveRedisConnectionFactory,
        ttlSeconds: Int
    ): ReactiveExclusiveLockDocumentWrapper {
        // serializer
        val keySer = StringRedisSerializer()
        val valueSer = Jackson2JsonRedisSerializer(ExclusiveLockDocument::class.java)

        // serialization context
        val ctx =
            RedisSerializationContext.newSerializationContext<String, ExclusiveLockDocument>(keySer)
                .key(keySer)
                .value(valueSer)
                .hashKey(keySer)
                .hashValue(valueSer)
                .build()

        // reactive template
        val reactiveTemplate = ReactiveRedisTemplate(reactiveRedisConnectionFactory, ctx)

        return ReactiveExclusiveLockDocumentWrapper(
            reactiveTemplate,
            "exclusiveLocks",
            Duration.ofSeconds(ttlSeconds.toLong())
        )
    }
}
