package it.pagopa.ecommerce.transactions.scheduler.configurations.redis

import it.pagopa.ecommerce.commons.redis.templatewrappers.RedisTemplateWrapper
import it.pagopa.ecommerce.transactions.scheduler.streams.commands.EventDispatcherReceiverCommand
import java.time.Duration
import org.springframework.data.redis.core.RedisTemplate

/** Redis command template wrapper, used to write events to Redis stream */
class EventDispatcherCommandsTemplateWrapper(
    redisTemplate: RedisTemplate<String, EventDispatcherReceiverCommand>,
    defaultEntitiesTTL: Duration
) :
    RedisTemplateWrapper<EventDispatcherReceiverCommand>(
        redisTemplate,
        "scheduler",
        defaultEntitiesTTL
    ) {
    override fun getKeyFromEntity(value: EventDispatcherReceiverCommand): String =
        value.commandId.toString()
}
