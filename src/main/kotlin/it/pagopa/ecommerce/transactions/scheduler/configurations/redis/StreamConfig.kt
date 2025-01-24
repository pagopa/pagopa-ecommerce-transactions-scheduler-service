package it.pagopa.ecommerce.transactions.scheduler.configurations.redis

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.data.redis.connection.stream.ObjectRecord
import org.springframework.data.redis.hash.Jackson2HashMapper
import org.springframework.data.redis.serializer.RedisSerializationContext
import org.springframework.data.redis.serializer.StringRedisSerializer
import org.springframework.data.redis.stream.StreamReceiver

/**
 * Redis stream configuration class. This class contains all Redis Stream integration specific
 * configurations
 */
@Configuration
class StreamConfig {

    @Bean
    fun redisStreamReceiver(
        reactiveRedisConnectionFactory: ReactiveRedisConnectionFactory,
    ): StreamReceiver<String, ObjectRecord<String, LinkedHashMap<*, *>>>? {
        val objectMapper = jacksonObjectMapper()
        val streamReceiverOptions =
            StreamReceiver.StreamReceiverOptions.builder()
                .pollTimeout(Duration.ofSeconds(1))
                .batchSize(1) // read one item per poll
                .keySerializer(
                    RedisSerializationContext.SerializationPair.fromSerializer(
                        StringRedisSerializer()
                    )
                )
                .objectMapper(Jackson2HashMapper(objectMapper, false))
                .targetType(LinkedHashMap::class.java)
                .build()
        return StreamReceiver.create(reactiveRedisConnectionFactory, streamReceiverOptions)
    }
}
