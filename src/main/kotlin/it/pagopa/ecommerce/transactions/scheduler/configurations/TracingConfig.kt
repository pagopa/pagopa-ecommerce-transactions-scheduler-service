package it.pagopa.ecommerce.transactions.scheduler.configurations

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Tracer
import it.pagopa.ecommerce.commons.queues.TracingUtils
import it.pagopa.ecommerce.commons.utils.OpenTelemetryUtils
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
public class TracingConfig {
    @Bean fun openTelemetry(): OpenTelemetry = GlobalOpenTelemetry.get()

    @Bean
    fun tracer(openTelemetry: OpenTelemetry): Tracer =
        openTelemetry.getTracer("pagopa-ecommerce-transactions-scheduler-service")

    @Bean
    fun tracingUtils(openTelemetry: OpenTelemetry, tracer: Tracer): TracingUtils =
        TracingUtils(openTelemetry, tracer)

    @Bean fun commonTracingUtils(tracer: Tracer): OpenTelemetryUtils = OpenTelemetryUtils(tracer)
}
