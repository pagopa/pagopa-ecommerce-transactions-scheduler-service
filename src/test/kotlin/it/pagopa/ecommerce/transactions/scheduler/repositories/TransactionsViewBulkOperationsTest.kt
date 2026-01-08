package it.pagopa.ecommerce.transactions.scheduler.repositories

import com.mongodb.MongoBulkWriteException
import com.mongodb.ServerAddress
import com.mongodb.bulk.BulkWriteError
import com.mongodb.bulk.BulkWriteResult
import it.pagopa.ecommerce.commons.documents.BaseTransactionView
import it.pagopa.ecommerce.transactions.scheduler.repositories.ecommerce.TransactionsViewBulkOperations
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
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.mongodb.core.BulkOperations
import org.springframework.data.mongodb.core.ReactiveBulkOperations
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier


@ExtendWith(MockitoExtension::class)
class TransactionsViewBulkOperationsTest {

    @Mock
    lateinit var reactiveMongoTemplate: ReactiveMongoTemplate

    @Mock
    lateinit var bulkOps: ReactiveBulkOperations

    private lateinit var service: TransactionsViewBulkOperations

    @BeforeEach
    fun setUp() {
        service = TransactionsViewBulkOperations(reactiveMongoTemplate)
    }

    @Test
    fun `bulkUpdateTtl should return all items on successful update`() {
        // GIVEN
        val ttlDate = 123456789L
        val item1 = mock<BaseTransactionView>()
        val item2 =  mock<BaseTransactionView>()
        val items = Flux.just(item1, item2)

        val bulkResult = BulkWriteResult.acknowledged(2, 0, 0, 0, emptyList(), emptyList())

        given(reactiveMongoTemplate.bulkOps(any(BulkOperations.BulkMode::class.java), any(Class::class.java)))
            .willReturn(bulkOps)

        given(bulkOps.updateOne(anyOrNull(), anyOrNull()))
            .willReturn(bulkOps)

        given(bulkOps.execute())
            .willReturn(Mono.just(bulkResult))

        // WHEN
        val resultFlux = service.bulkUpdateTtl(items, ttlDate)

        // THEN
        StepVerifier.create(resultFlux)
            .expectNext(item1)
            .expectNext(item2)
            .verifyComplete()

        // Verify calls
        Mockito.verify(bulkOps, Mockito.times(2)).updateOne(anyOrNull(), anyOrNull())
        Mockito.verify(bulkOps, Mockito.times(1)).execute()
    }

    @Test
    fun `bulkUpdateTtl should return only survivors when partial failure occurs`() {
        // GIVEN
        val ttlDate = 123456789L
        val item1 = mock<BaseTransactionView>()
        val item2 =  mock<BaseTransactionView>()
        val items = Flux.just(item1, item2)

        val writeError = BulkWriteError(11000, "Duplicate Key", BsonDocument(), 1)
        val mongoEx = MongoBulkWriteException(
            BulkWriteResult.unacknowledged(),
            listOf(writeError),
            null,
            ServerAddress("localhost"),
            emptySet()
        )
        val springEx = DataIntegrityViolationException("Bulk failed", mongoEx)

        given(reactiveMongoTemplate.bulkOps(any(BulkOperations.BulkMode::class.java), any(Class::class.java)))
            .willReturn(bulkOps)
        given(bulkOps.updateOne(anyOrNull(), anyOrNull()))
            .willReturn(bulkOps)
        given(bulkOps.execute())
            .willReturn(Mono.error(springEx))

        // WHEN
        val resultFlux = service.bulkUpdateTtl(items, ttlDate)

        // THEN
        StepVerifier.create(resultFlux)
            .expectNext(item1) // Only item 1 should survive
            .verifyComplete()
    }

    @Test
    fun `bulkUpdateTtl should return empty on total failure`() {
        // GIVEN
        val item1 = mock<BaseTransactionView>()
        val items = Flux.just(item1)

        val unknownEx = DataIntegrityViolationException("Generic DB error")

        given(reactiveMongoTemplate.bulkOps(any(BulkOperations.BulkMode::class.java), any(Class::class.java)))
            .willReturn(bulkOps)
        given(bulkOps.updateOne(anyOrNull(), anyOrNull()))
            .willReturn(bulkOps)
        given(bulkOps.execute())
            .willReturn(Mono.error(unknownEx))

        // WHEN & THEN
        StepVerifier.create(service.bulkUpdateTtl(items, 100L))
            .verifyComplete()
    }
}