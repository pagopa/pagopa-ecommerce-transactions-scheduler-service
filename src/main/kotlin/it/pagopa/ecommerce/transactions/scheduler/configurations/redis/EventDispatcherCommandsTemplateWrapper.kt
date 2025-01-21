package it.pagopa.ecommerce.transactions.scheduler.configurations.redis

import it.pagopa.ecommerce.commons.redis.templatewrappers.RedisTemplateWrapper
import it.pagopa.ecommerce.eventdispatcher.redis.streams.commands.EventDispatcherReceiverCommand
import org.springframework.data.redis.core.RedisTemplate
import java.time.Duration

/** Redis command template wrapper, used to write events to Redis stream */
class EventDispatcherCommandsTemplateWrapper(
    redisTemplate: RedisTemplate<String, EventDispatcherReceiverCommand>,
    defaultEntitiesTTL: Duration
) :
    RedisTemplateWrapper<EventDispatcherReceiverCommand>(
        redisTemplate, "eventDispatcher", defaultEntitiesTTL
    ) {
    override fun getKeyFromEntity(value: EventDispatcherReceiverCommand): String =
        value.commandId.toString()
}
