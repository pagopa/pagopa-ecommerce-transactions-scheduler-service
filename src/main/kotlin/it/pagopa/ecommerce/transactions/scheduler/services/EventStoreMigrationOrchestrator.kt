package it.pagopa.ecommerce.transactions.scheduler.services

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent
import it.pagopa.ecommerce.transactions.scheduler.utils.CommonTracingUtils
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.kotlin.core.util.function.component1
import reactor.kotlin.core.util.function.component2
import reactor.util.function.Tuple2

@Service
class EventStoreMigrationOrchestrator(
    @param:Autowired private val transactionMigrationQueryService: TransactionMigrationQueryService,
    @param:Autowired private val transactionMigrationWriteService: TransactionMigrationWriteService,
    @param:Autowired private val commonTracingUtils: CommonTracingUtils
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val ECOMMERCE_MIGRATION_SPAN_NAME = "eCommerceMigration"
    private val ECOMMERCE_MIGRATION_SOURCE_KEY =
        AttributeKey.stringKey("eCommerce.migration.source")
    private val ECOMMERCE_MIGRATION_EVENT_ID_KEY =
        AttributeKey.stringKey("eCommerce.migration.eventId")
    private val ECOMMERCE_MIGRATION_TRANSACTION_ID_KEY =
        AttributeKey.stringKey("eCommerce.migration.transactionId")

    fun createMigrationPipeline(): Mono<Tuple2<Long, List<BaseTransactionEvent<*>>>> {
        logger.info("eventstore migration process started")
        return transactionMigrationQueryService
            .findEligibleEvents()
            .doOnNext { tx -> logger.debug("Processing event: ${tx.id}") }
            .transform { tx -> transactionMigrationWriteService.writeEvents(tx) }
            .transform { tx -> transactionMigrationWriteService.updateEventsTtl(tx) }
            .map { tx ->
                commonTracingUtils.addSpan(ECOMMERCE_MIGRATION_SPAN_NAME, getSpanAttributes(tx))
                tx
            }
            .collectList()
            .elapsed()
            .doOnSuccess { (elapsedMs, processedEvents) ->
                logger.info(
                    "eventstore migration process completed. Processed ${processedEvents.size} items in $elapsedMs ms"
                )
            }
            .onErrorResume { error ->
                logger.error("eventstore migration process failed", error)
                Mono.empty()
            }
    }

    fun runMigration() {
        this.createMigrationPipeline().subscribe()
    }

    private fun getSpanAttributes(event: BaseTransactionEvent<*>) =
        Attributes.of(
            ECOMMERCE_MIGRATION_EVENT_ID_KEY,
            event.id,
            ECOMMERCE_MIGRATION_TRANSACTION_ID_KEY,
            event.transactionId,
            ECOMMERCE_MIGRATION_SOURCE_KEY,
            "eventstore"
        )
}
