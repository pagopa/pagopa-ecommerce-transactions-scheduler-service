package it.pagopa.ecommerce.transactions.scheduler.repositories

import com.mongodb.MongoBulkWriteException
import com.mongodb.ServerAddress
import com.mongodb.bulk.BulkWriteError
import com.mongodb.bulk.BulkWriteResult
import it.pagopa.ecommerce.commons.documents.BaseTransactionView
import it.pagopa.ecommerce.transactions.scheduler.repositories.ecommercehistory.TransactionsViewHistoryBulkOperations
import org.bson.BsonDocument
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.springframework.data.mongodb.core.BulkOperations
import org.springframework.data.mongodb.core.ReactiveBulkOperations
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

@ExtendWith(MockitoExtension::class)
class TransactionsViewHistoryBulkOperationsTest {

    @Mock lateinit var reactiveMongoTemplate: ReactiveMongoTemplate

    @Mock lateinit var bulkOps: ReactiveBulkOperations

    private lateinit var service: TransactionsViewHistoryBulkOperations

    @BeforeEach
    fun setUp() {
        service = TransactionsViewHistoryBulkOperations(reactiveMongoTemplate)
    }

    @Test
    fun `bulkUpsert should return all items on successful upsert`() {
        // GIVEN
        val item1 = mock<BaseTransactionView>()
        val item2 = mock<BaseTransactionView>()
        val items = Flux.just(item1, item2)

        val bulkResult = BulkWriteResult.acknowledged(2, 0, 0, 0, emptyList(), emptyList())

        given(
                reactiveMongoTemplate.bulkOps(
                    any(BulkOperations.BulkMode::class.java),
                    any(Class::class.java)
                )
            )
            .willReturn(bulkOps)

        given(bulkOps.replaceOne(anyOrNull(), anyOrNull(), anyOrNull())).willReturn(bulkOps)

        given(bulkOps.execute()).willReturn(Mono.just(bulkResult))

        // WHEN
        val resultFlux = service.bulkUpsert(items)

        // THEN
        StepVerifier.create(resultFlux).expectNext(item1).expectNext(item2).verifyComplete()

        // Verify calls
        Mockito.verify(bulkOps, Mockito.times(2)).replaceOne(anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `bulkUpsert should return only survivors when partial failure occurs`() {
        // GIVEN
        val item1 = mock<BaseTransactionView>()
        val item2 = mock<BaseTransactionView>()
        val items = Flux.just(item1, item2)

        val writeError = BulkWriteError(11000, "Duplicate Key", BsonDocument(), 1)

        val mongoEx =
            MongoBulkWriteException(
                BulkWriteResult.unacknowledged(),
                listOf(writeError),
                null,
                ServerAddress("localhost"),
                emptySet()
            )

        given(
                reactiveMongoTemplate.bulkOps(
                    any(BulkOperations.BulkMode::class.java),
                    any(Class::class.java)
                )
            )
            .willReturn(bulkOps)

        given(bulkOps.replaceOne(anyOrNull(), anyOrNull(), anyOrNull())).willReturn(bulkOps)

        given(bulkOps.execute()).willReturn(Mono.error(mongoEx))

        // WHEN
        val resultFlux = service.bulkUpsert(items)

        // THEN
        StepVerifier.create(resultFlux).expectNext(item1).verifyComplete()
    }

    @Test
    fun `bulkUpsert should return empty on total system failure`() {
        // GIVEN
        val item1 = mock<BaseTransactionView>()
        val items = Flux.just(item1)

        val unexpectedEx = RuntimeException("Database is down")

        given(
                reactiveMongoTemplate.bulkOps(
                    any(BulkOperations.BulkMode::class.java),
                    any(Class::class.java)
                )
            )
            .willReturn(bulkOps)

        given(bulkOps.replaceOne(anyOrNull(), anyOrNull(), anyOrNull())).willReturn(bulkOps)

        given(bulkOps.execute()).willReturn(Mono.error(unexpectedEx))

        // WHEN
        val resultFlux = service.bulkUpsert(items)

        // THEN
        StepVerifier.create(resultFlux).verifyComplete()
    }

    @Test
    fun `bulkUpsert should handle empty input flux`() {
        // WHEN
        val resultFlux = service.bulkUpsert(Flux.empty())

        // THEN
        StepVerifier.create(resultFlux).verifyComplete()

        // Verify
        Mockito.verify(reactiveMongoTemplate, Mockito.never())
            .bulkOps(any(BulkOperations.BulkMode::class.java), any(Class::class.java))
    }
}
