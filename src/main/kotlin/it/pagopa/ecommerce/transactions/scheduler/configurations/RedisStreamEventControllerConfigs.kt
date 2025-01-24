package it.pagopa.ecommerce.transactions.scheduler.configurations

import java.util.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration

@Configuration
class RedisStreamEventControllerConfigs(
    @Value("\${redisStream.eventController.streamKey}") val streamKey: String,
    @Value("\${redisStream.eventController.consumerNamePrefix}") consumerNamePrefix: String,
) {
    private val uniqueConsumerId = UUID.randomUUID().toString()
    val consumerName = "$consumerNamePrefix-$uniqueConsumerId"
}
