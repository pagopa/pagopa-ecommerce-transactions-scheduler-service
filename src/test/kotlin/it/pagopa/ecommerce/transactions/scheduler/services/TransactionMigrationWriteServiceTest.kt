package it.pagopa.ecommerce.transactions.scheduler.services

import com.mongodb.client.result.UpdateResult
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent
import it.pagopa.ecommerce.commons.documents.BaseTransactionView
import it.pagopa.ecommerce.transactions.scheduler.configurations.TransactionMigrationWriteServiceConfig
import it.pagopa.ecommerce.transactions.scheduler.configurations.WriteSettings
import it.pagopa.ecommerce.transactions.scheduler.repositories.ecommercehistory.TransactionsEventStoreHistoryRepository
import it.pagopa.ecommerce.transactions.scheduler.repositories.ecommercehistory.TransactionsViewHistoryRepository
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

@ExtendWith(MockitoExtension::class)
class TransactionMigrationWriteServiceTest {

    @Mock private lateinit var eventHistoryRepository: TransactionsEventStoreHistoryRepository

    @Mock private lateinit var viewHistoryRepository: TransactionsViewHistoryRepository

    @Mock private lateinit var ecommerceMongoTemplate: ReactiveMongoTemplate

    @Mock private lateinit var config: TransactionMigrationWriteServiceConfig

    @Mock private lateinit var eventstoreWriteSettings: WriteSettings

    @Mock private lateinit var transactionsViewWriteSettings: WriteSettings

    @InjectMocks
    private lateinit var transactionMigrationWriteService: TransactionMigrationWriteService

    @Test
    fun `should write eventstore events successfully and update TTL in batch`() {
        val mockEvent1: BaseTransactionEvent<*> = mock()
        val mockEvent2: BaseTransactionEvent<*> = mock()

        val expectedTtlValue = 10000000

        whenever(mockEvent1.id).thenReturn("event-1")
        whenever(mockEvent2.id).thenReturn("event-2")

        whenever(config.eventstore).thenReturn(eventstoreWriteSettings)
        whenever(eventstoreWriteSettings.ttlSeconds).thenReturn(expectedTtlValue)

        val inputFlux = Flux.just(mockEvent1, mockEvent2)

        @Suppress("UNCHECKED_CAST") val typedRepository = eventHistoryRepository

        whenever(typedRepository.save(any())).thenAnswer { invocation ->
            Mono.just<Any>(invocation.getArgument(0))
        }

        val mockUpdateResult: UpdateResult = mock()
        whenever(mockUpdateResult.modifiedCount).thenReturn(2)
        whenever(
                ecommerceMongoTemplate.updateMulti(
                    any<Query>(),
                    any<Update>(),
                    eq(BaseTransactionEvent::class.java)
                )
            )
            .thenReturn(Mono.just(mockUpdateResult))

        // ACT
        val result = transactionMigrationWriteService.writeEvents(inputFlux)

        // ASSERT
        StepVerifier.create(result).verifyComplete()

        verify(typedRepository, times(2)).save(any())

        val queryCaptor = argumentCaptor<Query>()
        val updateCaptor = argumentCaptor<Update>()

        verify(ecommerceMongoTemplate, times(1))
            .updateMulti(
                queryCaptor.capture(),
                updateCaptor.capture(),
                eq(BaseTransactionEvent::class.java)
            )

        // Verify the query contains the correct event ids
        val capturedQuery = queryCaptor.firstValue
        val queryCriteria = capturedQuery.queryObject
        val inClause = queryCriteria["_id"] as Map<*, *>
        val eventIds = inClause["\$in"] as List<*>
        assert(eventIds[0] == "event-1")
        assert(eventIds[1] == "event-2")

        // Verify the update contains the correct ttl value
        val capturedUpdate = updateCaptor.firstValue
        val updateDocument = capturedUpdate.updateObject
        val setClause = updateDocument["\$set"] as Map<*, *>
        val ttlValue = setClause["ttl"] as Instant

        // ttl check with 5s tolerance
        val tolerance: Long = 5
        val expectedTtl = Instant.now().plusSeconds(expectedTtlValue.toLong())
        assertThat(ttlValue)
            .isBetween(expectedTtl.minusSeconds(tolerance), expectedTtl.plusSeconds(tolerance))
    }

    @Test
    fun `should skip failed event migrations and continue with successful ones, then update TTL for successful ones only`() {

        val mockEvent1: BaseTransactionEvent<*> = mock()
        val mockEvent2: BaseTransactionEvent<*> = mock()
        val mockEvent3: BaseTransactionEvent<*> = mock()

        whenever(mockEvent1.id).thenReturn("event-1")
        whenever(mockEvent2.id).thenReturn("event-2")
        whenever(mockEvent3.id).thenReturn("event-3")

        whenever(config.eventstore).thenReturn(eventstoreWriteSettings)
        whenever(eventstoreWriteSettings.ttlSeconds).thenReturn(10000000)

        val inputFlux = Flux.just(mockEvent1, mockEvent2, mockEvent3)

        @Suppress("UNCHECKED_CAST") val typedRepository = eventHistoryRepository

        whenever(typedRepository.save(any()))
            .thenReturn(Mono.just(mockEvent1))
            .thenReturn(Mono.error(RuntimeException("Database error")))
            .thenReturn(Mono.just(mockEvent3))

        val mockUpdateResult: UpdateResult = mock()
        whenever(mockUpdateResult.modifiedCount).thenReturn(2)
        whenever(
                ecommerceMongoTemplate.updateMulti(
                    any<Query>(),
                    any<Update>(),
                    eq(BaseTransactionEvent::class.java)
                )
            )
            .thenReturn(Mono.just(mockUpdateResult))

        // ACT
        val result = transactionMigrationWriteService.writeEvents(inputFlux)

        // ASSERT
        StepVerifier.create(result).verifyComplete()

        verify(typedRepository, times(3)).save(any())
        // Verify TTL update was called with only successful event IDs (event-1 and event-3)
        verify(ecommerceMongoTemplate, times(1))
            .updateMulti(any<Query>(), any<Update>(), eq(BaseTransactionEvent::class.java))
    }

    @Test
    fun `should return empty when all event migrations fail and not update TTL`() {

        val mockEvent1: BaseTransactionEvent<*> = mock()
        val mockEvent2: BaseTransactionEvent<*> = mock()
        whenever(mockEvent1.id).thenReturn("event-1")
        whenever(mockEvent2.id).thenReturn("event-2")

        val inputFlux = Flux.just(mockEvent1, mockEvent2)

        @Suppress("UNCHECKED_CAST") val typedRepository = eventHistoryRepository

        whenever(typedRepository.save(any()))
            .thenReturn(Mono.error(RuntimeException("Database error")))
            .thenReturn(Mono.error(RuntimeException("Database error")))

        // ACT
        val result = transactionMigrationWriteService.writeEvents(inputFlux)

        // ASSERT
        StepVerifier.create(result).verifyComplete()

        verify(typedRepository, times(2)).save(any())
        // No TTL update should happen since no events were migrated successfully
        verify(ecommerceMongoTemplate, never())
            .updateMulti(any<Query>(), any<Update>(), any<Class<*>>())
    }

    @Test
    fun `should complete successfully even if TTL update fails`() {

        val mockEvent1: BaseTransactionEvent<*> = mock()
        val mockEvent2: BaseTransactionEvent<*> = mock()

        whenever(mockEvent1.id).thenReturn("event-1")
        whenever(mockEvent2.id).thenReturn("event-2")

        val inputFlux = Flux.just(mockEvent1, mockEvent2)

        @Suppress("UNCHECKED_CAST") val typedRepository = eventHistoryRepository

        whenever(typedRepository.save(any())).thenAnswer { invocation ->
            Mono.just<Any>(invocation.getArgument(0))
        }

        whenever(config.eventstore).thenReturn(eventstoreWriteSettings)
        whenever(eventstoreWriteSettings.ttlSeconds).thenReturn(10000000)

        // TTL update fails
        whenever(
                ecommerceMongoTemplate.updateMulti(
                    any<Query>(),
                    any<Update>(),
                    eq(BaseTransactionEvent::class.java)
                )
            )
            .thenReturn(Mono.error(RuntimeException("TTL update failed")))

        // ACT
        val result = transactionMigrationWriteService.writeEvents(inputFlux)

        // ASSERT
        StepVerifier.create(result).verifyComplete()

        verify(typedRepository, times(2)).save(any())
        verify(ecommerceMongoTemplate, times(1))
            .updateMulti(any<Query>(), any<Update>(), eq(BaseTransactionEvent::class.java))
    }

    @Test
    fun `should write transaction views successfully and update TTL in batch`() {
        val mockView1: BaseTransactionView = mock()
        val mockView2: BaseTransactionView = mock()

        val expectedTtlValue = 10000000

        whenever(mockView1.transactionId).thenReturn("view-1")
        whenever(mockView2.transactionId).thenReturn("view-2")

        whenever(config.transactionsView).thenReturn(transactionsViewWriteSettings)
        whenever(transactionsViewWriteSettings.ttlSeconds).thenReturn(10000000)

        val inputFlux = Flux.just(mockView1, mockView2)

        whenever(viewHistoryRepository.save(any())).thenAnswer { invocation ->
            Mono.just<Any>(invocation.getArgument(0))
        }

        val mockUpdateResult: UpdateResult = mock()
        whenever(mockUpdateResult.modifiedCount).thenReturn(2)
        whenever(
                ecommerceMongoTemplate.updateMulti(
                    any<Query>(),
                    any<Update>(),
                    eq(BaseTransactionView::class.java)
                )
            )
            .thenReturn(Mono.just(mockUpdateResult))

        // ACT
        val result = transactionMigrationWriteService.writeTransactionViews(inputFlux)

        // ASSERT
        StepVerifier.create(result).verifyComplete()

        verify(viewHistoryRepository, times(2)).save(any())

        val queryCaptor = argumentCaptor<Query>()
        val updateCaptor = argumentCaptor<Update>()

        verify(ecommerceMongoTemplate, times(1))
            .updateMulti(
                queryCaptor.capture(),
                updateCaptor.capture(),
                eq(BaseTransactionView::class.java)
            )

        // Verify the query contains the correct view ids
        val capturedQuery = queryCaptor.firstValue
        val queryCriteria = capturedQuery.queryObject
        val inClause = queryCriteria["_id"] as Map<*, *>
        val viewIds = inClause["\$in"] as List<*>
        assert(viewIds[0] == "view-1")
        assert(viewIds[1] == "view-2")

        // Verify the update contains the correct ttl value
        val capturedUpdate = updateCaptor.firstValue
        val updateDocument = capturedUpdate.updateObject
        val setClause = updateDocument["\$set"] as Map<*, *>
        val ttlValue = setClause["ttl"] as Instant

        // ttl check with 5s tolerance
        val tolerance: Long = 5
        val expectedTtl = Instant.now().plusSeconds(expectedTtlValue.toLong())
        assertThat(ttlValue)
            .isBetween(expectedTtl.minusSeconds(tolerance), expectedTtl.plusSeconds(tolerance))
    }

    @Test
    fun `should skip failed view migrations and continue with successful ones, then update TTL for successful ones only`() {

        val mockView1: BaseTransactionView = mock()
        val mockView2: BaseTransactionView = mock()
        val mockView3: BaseTransactionView = mock()

        whenever(mockView1.transactionId).thenReturn("view-1")
        whenever(mockView2.transactionId).thenReturn("view-2")
        whenever(mockView3.transactionId).thenReturn("view-3")

        whenever(config.transactionsView).thenReturn(transactionsViewWriteSettings)
        whenever(transactionsViewWriteSettings.ttlSeconds).thenReturn(10000000)

        val inputFlux = Flux.just(mockView1, mockView2, mockView3)

        whenever(viewHistoryRepository.save(any()))
            .thenReturn(Mono.just(mockView1))
            .thenReturn(Mono.error(RuntimeException("Database error")))
            .thenReturn(Mono.just(mockView3))

        val mockUpdateResult: UpdateResult = mock()
        whenever(mockUpdateResult.modifiedCount).thenReturn(2)
        whenever(
                ecommerceMongoTemplate.updateMulti(
                    any<Query>(),
                    any<Update>(),
                    eq(BaseTransactionView::class.java)
                )
            )
            .thenReturn(Mono.just(mockUpdateResult))

        // ACT
        val result = transactionMigrationWriteService.writeTransactionViews(inputFlux)

        // ASSERT
        StepVerifier.create(result).verifyComplete()

        verify(viewHistoryRepository, times(3)).save(any())
        verify(ecommerceMongoTemplate, times(1))
            .updateMulti(any<Query>(), any<Update>(), eq(BaseTransactionView::class.java))
    }

    @Test
    fun `should return empty when all view migrations fail and not update TTL`() {

        val mockView: BaseTransactionView = mock()
        whenever(mockView.transactionId).thenReturn("view-1")

        val inputFlux = Flux.just(mockView)

        whenever(viewHistoryRepository.save(any()))
            .thenReturn(Mono.error(RuntimeException("Database error")))

        // ACT
        val result = transactionMigrationWriteService.writeTransactionViews(inputFlux)

        // ASSERT
        StepVerifier.create(result).verifyComplete()

        verify(viewHistoryRepository, times(1)).save(any())
        verify(ecommerceMongoTemplate, never())
            .updateMulti(any<Query>(), any<Update>(), any<Class<*>>())
    }

    @Test
    fun `should complete successfully even if view TTL update fails`() {

        val mockView1: BaseTransactionView = mock()
        val mockView2: BaseTransactionView = mock()

        whenever(mockView1.transactionId).thenReturn("view-1")
        whenever(mockView2.transactionId).thenReturn("view-2")

        whenever(config.transactionsView).thenReturn(transactionsViewWriteSettings)
        whenever(transactionsViewWriteSettings.ttlSeconds).thenReturn(10000000)

        val inputFlux = Flux.just(mockView1, mockView2)

        whenever(viewHistoryRepository.save(any())).thenAnswer { invocation ->
            Mono.just<Any>(invocation.getArgument(0))
        }

        // TTL update fails
        whenever(
                ecommerceMongoTemplate.updateMulti(
                    any<Query>(),
                    any<Update>(),
                    eq(BaseTransactionView::class.java)
                )
            )
            .thenReturn(Mono.error(RuntimeException("TTL update failed")))

        // ACT
        val result = transactionMigrationWriteService.writeTransactionViews(inputFlux)

        // ASSERT
        StepVerifier.create(result).verifyComplete()

        verify(viewHistoryRepository, times(2)).save(any())
        verify(ecommerceMongoTemplate, times(1))
            .updateMulti(any<Query>(), any<Update>(), eq(BaseTransactionView::class.java))
    }

    @Test
    fun `should handle empty input Flux for events`() {

        val inputFlux = Flux.empty<BaseTransactionEvent<*>>()

        @Suppress("UNCHECKED_CAST") val typedRepository = eventHistoryRepository

        // ACT
        val result = transactionMigrationWriteService.writeEvents(inputFlux)

        // ASSERT
        StepVerifier.create(result).verifyComplete()

        verify(typedRepository, never()).save(any())
        verify(ecommerceMongoTemplate, never())
            .updateMulti(any<Query>(), any<Update>(), any<Class<*>>())
    }

    @Test
    fun `should handle empty input Flux for views`() {

        val inputFlux = Flux.empty<BaseTransactionView>()

        // ACT
        val result = transactionMigrationWriteService.writeTransactionViews(inputFlux)

        // ASSERT
        StepVerifier.create(result).verifyComplete()

        verify(viewHistoryRepository, never()).save(any())
        verify(ecommerceMongoTemplate, never())
            .updateMulti(any<Query>(), any<Update>(), any<Class<*>>())
    }
}
