package it.pagopa.ecommerce.transactions.scheduler.configurations.redis

import it.pagopa.ecommerce.commons.redis.reactivetemplatewrappers.ReactiveRedisTemplateWrapper
import it.pagopa.ecommerce.transactions.scheduler.repositories.redis.eventreceivers.ReceiversStatus
import java.time.Duration
import org.springframework.data.redis.core.ReactiveRedisTemplate

/** Redis template wrapper used to handle event receiver statuses */
class EventDispatcherReceiverStatusTemplateWrapper(
    redisTemplate: ReactiveRedisTemplate<String, ReceiversStatus>,
    defaultEntitiesTTL: Duration
) :
    ReactiveRedisTemplateWrapper<ReceiversStatus>(
        redisTemplate,
        "scheduler-receiver-status",
        defaultEntitiesTTL
    ) {
    override fun getKeyFromEntity(value: ReceiversStatus): String = value.consumerInstanceId
}
