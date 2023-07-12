package it.pagopa.ecommerce.transactions.scheduler.configurations;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TracingConfig {
    @Bean
    public OpenTelemetry openTelemetry() {
        return GlobalOpenTelemetry.get();
    }

    @Bean
    public Tracer tracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("pagopa-ecommerce-transactions-scheduler-service");
    }

    @Bean
    public TracingUtils tracingUtils(OpenTelemetry openTelemetry, Tracer tracer) {
        return new TracingUtils(openTelemetry, tracer);
    }
}
