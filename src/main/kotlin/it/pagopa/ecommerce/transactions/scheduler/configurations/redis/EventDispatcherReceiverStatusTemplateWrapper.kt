package it.pagopa.ecommerce.transactions.scheduler.configurations.redis

import it.pagopa.ecommerce.commons.redis.templatewrappers.RedisTemplateWrapper
import it.pagopa.ecommerce.transactions.scheduler.repositories.redis.eventreceivers.ReceiversStatus
import java.time.Duration
import org.springframework.data.redis.core.RedisTemplate

/** Redis template wrapper used to handle event receiver statuses */
class EventDispatcherReceiverStatusTemplateWrapper(
    redisTemplate: RedisTemplate<String, ReceiversStatus>,
    defaultEntitiesTTL: Duration
) :
    RedisTemplateWrapper<ReceiversStatus>(
        redisTemplate,
        "scheduler-receiver-status",
        defaultEntitiesTTL
    ) {
    override fun getKeyFromEntity(value: ReceiversStatus): String = value.consumerInstanceId
}
