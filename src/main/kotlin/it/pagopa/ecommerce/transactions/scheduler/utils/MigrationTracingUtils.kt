package it.pagopa.ecommerce.transactions.scheduler.utils

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes

class MigrationTracingUtils() {
    companion object {
        val ECOMMERCE_MIGRATION_SPAN_NAME = "eCommerceMigration"
        val ECOMMERCE_MIGRATION_SOURCE_KEY = AttributeKey.stringKey("eCommerce.migration.source")
        val ECOMMERCE_MIGRATION_ITERATION_ELAPSED_KEY =
            AttributeKey.longKey("eCommerce.migration.iterationElapsedTime")
        val ECOMMERCE_MIGRATION_ITERATION_TOTAL_ITEMS_KEY =
            AttributeKey.longKey("eCommerce.migration.iterationTotalItems")
    }

    fun getIterationSpanAttributes(elapsed: Long, eventsCount: Long): Attributes =
        Attributes.of(
            ECOMMERCE_MIGRATION_ITERATION_TOTAL_ITEMS_KEY,
            eventsCount,
            ECOMMERCE_MIGRATION_ITERATION_ELAPSED_KEY,
            elapsed,
            ECOMMERCE_MIGRATION_SOURCE_KEY,
            "eventstore"
        )
}
