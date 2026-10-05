package it.pagopa.ecommerce.transactions.scheduler.configurations

import it.pagopa.ecommerce.commons.mdcutilities.LogTracingUtils
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import reactor.core.publisher.Hooks
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier

class MicrometerMdcConfigurationTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun setUp() {
            MicrometerMdcConfiguration().initMdcMicrometerRegistry()
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            Hooks.disableAutomaticContextPropagation()
        }
    }

    @Test
    fun `Should propagate context bound keys from reactor context to MDC across threads`() {
        val transactionIdKey = LogTracingUtils.AttributeKeys.CTX_TRANSACTION_ID
        val eventActionKey = LogTracingUtils.AttributeKeys.EVENT_ACTION
        StepVerifier.create(
                Mono.just(1)
                    .publishOn(Schedulers.parallel())
                    .map { "${MDC.get(transactionIdKey.key)}-${MDC.get(eventActionKey.key)}" }
                    .contextWrite { context ->
                        LogTracingUtils.enrichContextForEvent(
                            mapOf(transactionIdKey to "transactionId", eventActionKey to "ACTION"),
                            context
                        )
                    }
            )
            .expectNext("transactionId-ACTION")
            .verifyComplete()
        assertNull(MDC.get(transactionIdKey.key))
    }

    @Test
    fun `Should not propagate keys that are not context bound`() {
        val userIdKey = LogTracingUtils.AttributeKeys.CTX_USER_ID
        StepVerifier.create(
                Mono.just(1)
                    .publishOn(Schedulers.parallel())
                    .map { MDC.get(userIdKey.key) ?: "absent" }
                    .contextWrite { context ->
                        LogTracingUtils.enrichContextForEvent(mapOf(userIdKey to "userId"), context)
                    }
            )
            .expectNext("absent")
            .verifyComplete()
    }
}
