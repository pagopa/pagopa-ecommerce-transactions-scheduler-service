package it.pagopa.ecommerce.transactions.scheduler.utils

import com.mongodb.MongoBulkWriteException
import com.mongodb.bulk.BulkWriteResult
import org.slf4j.LoggerFactory
import org.springframework.data.mongodb.core.ReactiveBulkOperations
import reactor.core.publisher.Mono

class MigrationUtils {
    companion object {
        private val logger = LoggerFactory.getLogger(MigrationUtils::class.java)

        fun <T> executeBestEffortBulkPipeline(
            bulkOps: ReactiveBulkOperations,
            items: List<T>,
            operationName: String = "Bulk operation",
            successValidator: (BulkWriteResult, List<T>) -> Unit = { _, _ -> }
        ): Mono<List<T>> {
            return bulkOps
                .execute()
                .map { result ->
                    // CASE A: 100% Success
                    successValidator(result, items)
                    items
                }
                .onErrorResume { ex -> handleError(ex, items, operationName) }
        }

        private fun <T> handleError(
            ex: Throwable,
            items: List<T>,
            operationName: String
        ): Mono<List<T>> {
            val mongoEx = extractMongoException(ex)

            if (mongoEx != null) {
                // Failed items
                val failedIndexes = mongoEx.writeErrors.map { it.index }.toSet()

                // Filter out failed items
                val survivors = items.filterIndexed { index, _ -> !failedIndexes.contains(index) }
                logger.warn(
                    "$operationName partial failure. ${failedIndexes.size} failed, ${survivors.size} succeeded."
                )
                return Mono.just(survivors)
            } else {
                // CASE C: Total System Failure (Network down, DB down, etc)
                logger.error("$operationName failed completely", mongoEx)
                return Mono.empty()
            }
        }

        private fun extractMongoException(ex: Throwable): MongoBulkWriteException? {
            return when {
                ex is MongoBulkWriteException -> ex
                ex.cause is MongoBulkWriteException -> ex.cause as MongoBulkWriteException
                else -> null
            }
        }
    }
}
