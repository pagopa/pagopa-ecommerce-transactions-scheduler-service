package it.pagopa.ecommerce.transactions.scheduler.utils

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes

class MigrationTracingUtils() {
    companion object {
        val ECOMMERCE_MIGRATION_SPAN_NAME = "eCommerceMigration"
        val ECOMMERCE_MIGRATION_SOURCE_KEY = AttributeKey.stringKey("eCommerce.migration.source")
        val ECOMMERCE_MIGRATION_ITERATION_ELAPSED_TIME_MS_KEY =
            AttributeKey.longKey("eCommerce.migration.iterationElapsedTimeMs")
        val ECOMMERCE_MIGRATION_ITERATION_TOTAL_ITEMS_KEY =
            AttributeKey.longKey("eCommerce.migration.iterationTotalItems")

        fun getIterationSpanAttributes(
            elapsed: Long,
            eventsCount: Long,
            source: String
        ): Attributes =
            Attributes.of(
                ECOMMERCE_MIGRATION_ITERATION_TOTAL_ITEMS_KEY,
                eventsCount,
                ECOMMERCE_MIGRATION_ITERATION_ELAPSED_TIME_MS_KEY,
                elapsed,
                ECOMMERCE_MIGRATION_SOURCE_KEY,
                source
            )
    }
}
