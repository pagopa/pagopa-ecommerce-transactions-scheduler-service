package it.pagopa.ecommerce.transactions.scheduler.utils

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context

class CommonTracingUtils(private val tracer: Tracer) {

    fun addSpan(spanName: String, attributes: Attributes) {
        val span: Span =
            tracer
                .spanBuilder(spanName)
                .setParent(Context.current().with(Span.current()))
                .startSpan()
        span.setAllAttributes(attributes)
        span.end()
    }
}
