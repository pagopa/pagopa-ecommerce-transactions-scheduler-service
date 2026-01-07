package it.pagopa.ecommerce.transactions.scheduler.services

import com.mongodb.client.result.UpdateResult
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent
import it.pagopa.ecommerce.commons.documents.BaseTransactionView
import it.pagopa.ecommerce.transactions.scheduler.configurations.TransactionMigrationWriteServiceConfig
import it.pagopa.ecommerce.transactions.scheduler.configurations.WriteSettings
import it.pagopa.ecommerce.transactions.scheduler.repositories.ecommerce.TransactionsViewBatchOperations
import it.pagopa.ecommerce.transactions.scheduler.repositories.ecommercehistory.TransactionsEventStoreHistoryRepository
import it.pagopa.ecommerce.transactions.scheduler.repositories.ecommercehistory.TransactionsViewHistoryBatchOperations
import it.pagopa.ecommerce.transactions.scheduler.repositories.ecommercehistory.TransactionsViewHistoryRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
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

    @Mock private lateinit var transactionsViewBatchOperations: TransactionsViewBatchOperations

    @Mock
    private lateinit var transactionsViewHistoryBatchOperations:
        TransactionsViewHistoryBatchOperations

    @Mock private lateinit var ecommerceMongoTemplate: ReactiveMongoTemplate

    @Mock private lateinit var config: TransactionMigrationWriteServiceConfig

    @Mock private lateinit var eventstoreWriteSettings: WriteSettings

    @Mock private lateinit var transactionsViewWriteSettings: WriteSettings

    @InjectMocks
    private lateinit var transactionMigrationWriteService: TransactionMigrationWriteService

    @Test
    fun `should copy all events successfully to history`() {
        // ARRANGE
        val mockEvent1: BaseTransactionEvent<*> = mock()
        val mockEvent2: BaseTransactionEvent<*> = mock()

        whenever(mockEvent1.id).thenReturn("event-1")
        whenever(mockEvent2.id).thenReturn("event-2")

        val inputFlux = Flux.just(mockEvent1, mockEvent2)

        whenever(eventHistoryRepository.save(any())).thenAnswer { invocation ->
            Mono.just<BaseTransactionEvent<*>>(invocation.getArgument(0))
        }

        // ACT
        val result = transactionMigrationWriteService.writeEvents(inputFlux)

        // ASSERT
        StepVerifier.create(result).expectNextCount(2).verifyComplete()

        verify(eventHistoryRepository, times(2)).save(any())
    }

    @Test
    fun `should skip failed event copy and continue with successful ones`() {
        // ARRANGE
        val mockEvent1: BaseTransactionEvent<*> = mock()
        val mockEvent2: BaseTransactionEvent<*> = mock()
        val mockEvent3: BaseTransactionEvent<*> = mock()

        whenever(mockEvent1.id).thenReturn("event-1")
        whenever(mockEvent2.id).thenReturn("event-2")
        whenever(mockEvent3.id).thenReturn("event-3")

        val inputFlux = Flux.just(mockEvent1, mockEvent2, mockEvent3)

        whenever(eventHistoryRepository.save(any()))
            .thenReturn(Mono.just(mockEvent1))
            .thenReturn(Mono.error(RuntimeException("Database error")))
            .thenReturn(Mono.just(mockEvent3))

        // ACT
        val result = transactionMigrationWriteService.writeEvents(inputFlux)

        // ASSERT
        StepVerifier.create(result).expectNextCount(2).verifyComplete()

        verify(eventHistoryRepository, times(3)).save(any())
    }

    @Test
    fun `should return empty when all event copies fail`() {
        // ARRANGE
        val mockEvent1: BaseTransactionEvent<*> = mock()
        val mockEvent2: BaseTransactionEvent<*> = mock()

        whenever(mockEvent1.id).thenReturn("event-1")
        whenever(mockEvent2.id).thenReturn("event-2")

        val inputFlux = Flux.just(mockEvent1, mockEvent2)

        whenever(eventHistoryRepository.save(any()))
            .thenReturn(Mono.error(RuntimeException("Database error")))
            .thenReturn(Mono.error(RuntimeException("Database error")))

        // ACT
        val result = transactionMigrationWriteService.writeEvents(inputFlux)

        // ASSERT
        StepVerifier.create(result).expectNextCount(0).verifyComplete()

        verify(eventHistoryRepository, times(2)).save(any())
    }

    @Test
    fun `should handle empty input Flux for events`() {
        // ARRANGE
        val inputFlux = Flux.empty<BaseTransactionEvent<*>>()

        // ACT
        val result = transactionMigrationWriteService.writeEvents(inputFlux)

        // ASSERT
        StepVerifier.create(result).verifyComplete()

        verify(eventHistoryRepository, never()).save(any())
    }

    @Test
    fun `should update TTL for all events successfully`() {
        // ARRANGE
        val mockEvent1: BaseTransactionEvent<*> = mock()
        val mockEvent2: BaseTransactionEvent<*> = mock()

        val expectedTtlSeconds = 10000000

        whenever(mockEvent1.id).thenReturn("event-1")
        whenever(mockEvent2.id).thenReturn("event-2")

        whenever(config.eventstore).thenReturn(eventstoreWriteSettings)
        whenever(eventstoreWriteSettings.ttlSeconds).thenReturn(expectedTtlSeconds)

        val inputFlux = Flux.just(mockEvent1, mockEvent2)

        val mockUpdateResult: UpdateResult = mock()
        whenever(mockUpdateResult.modifiedCount).thenReturn(1)
        whenever(
                ecommerceMongoTemplate.updateFirst(
                    any<Query>(),
                    any<Update>(),
                    eq(BaseTransactionEvent::class.java)
                )
            )
            .thenReturn(Mono.just(mockUpdateResult))

        // ACT
        val result = transactionMigrationWriteService.updateEventsTtl(inputFlux)

        // ASSERT
        StepVerifier.create(result).expectNextCount(2).verifyComplete()

        val queryCaptor = argumentCaptor<Query>()
        val updateCaptor = argumentCaptor<Update>()

        verify(ecommerceMongoTemplate, times(2))
            .updateFirst(
                queryCaptor.capture(),
                updateCaptor.capture(),
                eq(BaseTransactionEvent::class.java)
            )

        // Verify the queries contain the correct event ids
        val capturedQueries = queryCaptor.allValues
        val eventIds = capturedQueries.map { it.queryObject["_id"] }
        assertThat(eventIds).containsExactlyInAnyOrder("event-1", "event-2")

        // Verify the updates contain the correct TTL value in seconds
        val capturedUpdates = updateCaptor.allValues
        capturedUpdates.forEach { update ->
            val updateDocument = update.updateObject
            val setClause = updateDocument["\$set"] as Map<*, *>
            val ttlValue = setClause["ttl"] as Long

            assertEquals(expectedTtlSeconds.toLong(), ttlValue) {
                "Expected TTL to be $expectedTtlSeconds seconds, but got $ttlValue"
            }
        }
    }

    @Test
    fun `should skip events when TTL update fails and continue with others`() {
        // ARRANGE
        val mockEvent1: BaseTransactionEvent<*> = mock()
        val mockEvent2: BaseTransactionEvent<*> = mock()
        val mockEvent3: BaseTransactionEvent<*> = mock()

        whenever(mockEvent1.id).thenReturn("event-1")
        whenever(mockEvent2.id).thenReturn("event-2")
        whenever(mockEvent3.id).thenReturn("event-3")

        whenever(config.eventstore).thenReturn(eventstoreWriteSettings)
        whenever(eventstoreWriteSettings.ttlSeconds).thenReturn(10000000)

        val inputFlux = Flux.just(mockEvent1, mockEvent2, mockEvent3)

        val mockUpdateResult: UpdateResult = mock()
        whenever(mockUpdateResult.modifiedCount).thenReturn(1)

        whenever(
                ecommerceMongoTemplate.updateFirst(
                    any<Query>(),
                    any<Update>(),
                    eq(BaseTransactionEvent::class.java)
                )
            )
            .thenReturn(Mono.just(mockUpdateResult))
            .thenReturn(Mono.error(RuntimeException("TTL update failed")))
            .thenReturn(Mono.just(mockUpdateResult))

        // ACT
        val result = transactionMigrationWriteService.updateEventsTtl(inputFlux)

        // ASSERT
        StepVerifier.create(result).expectNextCount(2).verifyComplete()

        verify(ecommerceMongoTemplate, times(3))
            .updateFirst(any<Query>(), any<Update>(), eq(BaseTransactionEvent::class.java))
    }

    @Test
    fun `should skip event when TTL update returns zero modified count`() {
        // ARRANGE
        val mockEvent1: BaseTransactionEvent<*> = mock()
        val mockEvent2: BaseTransactionEvent<*> = mock()

        whenever(mockEvent1.id).thenReturn("event-1")
        whenever(mockEvent2.id).thenReturn("event-2")

        whenever(config.eventstore).thenReturn(eventstoreWriteSettings)
        whenever(eventstoreWriteSettings.ttlSeconds).thenReturn(10000000)

        val inputFlux = Flux.just(mockEvent1, mockEvent2)

        val mockUpdateResultSuccess: UpdateResult = mock()
        val mockUpdateResultFailure: UpdateResult = mock()
        whenever(mockUpdateResultSuccess.modifiedCount).thenReturn(1)
        whenever(mockUpdateResultFailure.modifiedCount).thenReturn(0)

        whenever(
                ecommerceMongoTemplate.updateFirst(
                    any<Query>(),
                    any<Update>(),
                    eq(BaseTransactionEvent::class.java)
                )
            )
            .thenReturn(Mono.just(mockUpdateResultSuccess))
            .thenReturn(Mono.just(mockUpdateResultFailure))

        // ACT
        val result = transactionMigrationWriteService.updateEventsTtl(inputFlux)

        // ASSERT
        StepVerifier.create(result).expectNextCount(1).verifyComplete()

        verify(ecommerceMongoTemplate, times(2))
            .updateFirst(any<Query>(), any<Update>(), eq(BaseTransactionEvent::class.java))
    }

    @Test
    fun `should return empty when all eventstore TTL updates fail`() {
        // ARRANGE
        val mockEvent1: BaseTransactionEvent<*> = mock()
        val mockEvent2: BaseTransactionEvent<*> = mock()

        whenever(mockEvent1.id).thenReturn("event-1")
        whenever(mockEvent2.id).thenReturn("event-2")

        whenever(config.eventstore).thenReturn(eventstoreWriteSettings)
        whenever(eventstoreWriteSettings.ttlSeconds).thenReturn(10000000)

        val inputFlux = Flux.just(mockEvent1, mockEvent2)

        whenever(
                ecommerceMongoTemplate.updateFirst(
                    any<Query>(),
                    any<Update>(),
                    eq(BaseTransactionEvent::class.java)
                )
            )
            .thenReturn(Mono.error(RuntimeException("TTL update failed")))

        // ACT
        val result = transactionMigrationWriteService.updateEventsTtl(inputFlux)

        // ASSERT
        StepVerifier.create(result).expectNextCount(0).verifyComplete()

        verify(ecommerceMongoTemplate, times(2))
            .updateFirst(any<Query>(), any<Update>(), eq(BaseTransactionEvent::class.java))
    }

    @Test
    fun `should handle empty input Flux for eventstore TTL updates`() {
        // ARRANGE
        val inputFlux = Flux.empty<BaseTransactionEvent<*>>()

        // ACT
        val result = transactionMigrationWriteService.updateEventsTtl(inputFlux)

        // ASSERT
        StepVerifier.create(result).verifyComplete()

        verify(ecommerceMongoTemplate, never())
            .updateFirst(any<Query>(), any<Update>(), any<Class<*>>())
    }

    @Test
    fun `should copy all views successfully to history`() {
        // ARRANGE
        val mockView1: BaseTransactionView = mock()
        val mockView2: BaseTransactionView = mock()

        whenever(mockView1.transactionId).thenReturn("view-1")
        whenever(mockView2.transactionId).thenReturn("view-2")

        val inputFlux = Flux.just(mockView1, mockView2)

        whenever(viewHistoryRepository.save(any())).thenAnswer { invocation ->
            Mono.just<BaseTransactionView>(invocation.getArgument(0))
        }

        // ACT
        val result = transactionMigrationWriteService.writeTransactionViews(inputFlux)

        // ASSERT
        StepVerifier.create(result).expectNextCount(2).verifyComplete()

        verify(viewHistoryRepository, times(2)).save(any())
    }

    @Test
    fun `should skip failed view copy and continue with successful ones`() {
        // ARRANGE
        val mockView1: BaseTransactionView = mock()
        val mockView2: BaseTransactionView = mock()
        val mockView3: BaseTransactionView = mock()

        whenever(mockView1.transactionId).thenReturn("view-1")
        whenever(mockView2.transactionId).thenReturn("view-2")
        whenever(mockView3.transactionId).thenReturn("view-3")

        val inputFlux = Flux.just(mockView1, mockView2, mockView3)

        whenever(viewHistoryRepository.save(any()))
            .thenReturn(Mono.just(mockView1))
            .thenReturn(Mono.error(RuntimeException("Database error")))
            .thenReturn(Mono.just(mockView3))

        // ACT
        val result = transactionMigrationWriteService.writeTransactionViews(inputFlux)

        // ASSERT
        StepVerifier.create(result).expectNextCount(2).verifyComplete()

        verify(viewHistoryRepository, times(3)).save(any())
    }

    @Test
    fun `should return empty when all view copies fail`() {
        // ARRANGE
        val mockView1: BaseTransactionView = mock()
        val mockView2: BaseTransactionView = mock()

        whenever(mockView1.transactionId).thenReturn("view-1")
        whenever(mockView2.transactionId).thenReturn("view-2")

        val inputFlux = Flux.just(mockView1, mockView2)

        whenever(viewHistoryRepository.save(any()))
            .thenReturn(Mono.error(RuntimeException("Database error")))

        // ACT
        val result = transactionMigrationWriteService.writeTransactionViews(inputFlux)

        // ASSERT
        StepVerifier.create(result).expectNextCount(0).verifyComplete()

        verify(viewHistoryRepository, times(2)).save(any())
    }

    @Test
    fun `should handle empty input Flux for views`() {
        // ARRANGE
        val inputFlux = Flux.empty<BaseTransactionView>()

        // ACT
        val result = transactionMigrationWriteService.writeTransactionViews(inputFlux)

        // ASSERT
        StepVerifier.create(result).verifyComplete()

        verify(viewHistoryRepository, never()).save(any())
    }

    @Test
    fun `should update TTL for all views successfully`() {
        // ARRANGE
        val mockView1: BaseTransactionView = mock()
        val mockView2: BaseTransactionView = mock()

        val expectedTtlSeconds = 10000000

        whenever(mockView1.transactionId).thenReturn("view-1")
        whenever(mockView2.transactionId).thenReturn("view-2")

        whenever(config.transactionsView).thenReturn(transactionsViewWriteSettings)
        whenever(transactionsViewWriteSettings.ttlSeconds).thenReturn(expectedTtlSeconds)

        val inputFlux = Flux.just(mockView1, mockView2)

        val mockUpdateResult: UpdateResult = mock()
        whenever(mockUpdateResult.modifiedCount).thenReturn(1)
        whenever(
                ecommerceMongoTemplate.updateFirst(
                    any<Query>(),
                    any<Update>(),
                    eq(BaseTransactionView::class.java)
                )
            )
            .thenReturn(Mono.just(mockUpdateResult))

        // ACT
        val result = transactionMigrationWriteService.updateViewsTtl(inputFlux)

        // ASSERT
        StepVerifier.create(result).expectNextCount(2).verifyComplete()

        val queryCaptor = argumentCaptor<Query>()
        val updateCaptor = argumentCaptor<Update>()

        verify(ecommerceMongoTemplate, times(2))
            .updateFirst(
                queryCaptor.capture(),
                updateCaptor.capture(),
                eq(BaseTransactionView::class.java)
            )

        // Verify the queries contain the correct view ids
        val capturedQueries = queryCaptor.allValues
        val viewIds = capturedQueries.map { it.queryObject["_id"] }
        assertThat(viewIds).containsExactlyInAnyOrder("view-1", "view-2")

        // Verify the updates contain the correct TTL value in seconds
        val capturedUpdates = updateCaptor.allValues
        capturedUpdates.forEach { update ->
            val updateDocument = update.updateObject
            val setClause = updateDocument["\$set"] as Map<*, *>
            val ttlValue = setClause["ttl"] as Long

            assertEquals(expectedTtlSeconds.toLong(), ttlValue) {
                "Expected TTL to be $expectedTtlSeconds seconds, but got $ttlValue"
            }
        }
    }

    @Test
    fun `should skip views when TTL update fails and continue with others`() {
        // ARRANGE
        val mockView1: BaseTransactionView = mock()
        val mockView2: BaseTransactionView = mock()
        val mockView3: BaseTransactionView = mock()

        whenever(mockView1.transactionId).thenReturn("view-1")
        whenever(mockView2.transactionId).thenReturn("view-2")
        whenever(mockView3.transactionId).thenReturn("view-3")

        whenever(config.transactionsView).thenReturn(transactionsViewWriteSettings)
        whenever(transactionsViewWriteSettings.ttlSeconds).thenReturn(10000000)

        val inputFlux = Flux.just(mockView1, mockView2, mockView3)

        val mockUpdateResult: UpdateResult = mock()
        whenever(mockUpdateResult.modifiedCount).thenReturn(1)

        whenever(
                ecommerceMongoTemplate.updateFirst(
                    any<Query>(),
                    any<Update>(),
                    eq(BaseTransactionView::class.java)
                )
            )
            .thenReturn(Mono.just(mockUpdateResult))
            .thenReturn(Mono.error(RuntimeException("TTL update failed")))
            .thenReturn(Mono.just(mockUpdateResult))

        // ACT
        val result = transactionMigrationWriteService.updateViewsTtl(inputFlux)

        // ASSERT
        StepVerifier.create(result).expectNextCount(2).verifyComplete()

        verify(ecommerceMongoTemplate, times(3))
            .updateFirst(any<Query>(), any<Update>(), eq(BaseTransactionView::class.java))
    }

    @Test
    fun `should skip view when TTL update returns zero modified count`() {
        // ARRANGE
        val mockView1: BaseTransactionView = mock()
        val mockView2: BaseTransactionView = mock()

        whenever(mockView1.transactionId).thenReturn("view-1")
        whenever(mockView2.transactionId).thenReturn("view-2")

        whenever(config.transactionsView).thenReturn(transactionsViewWriteSettings)
        whenever(transactionsViewWriteSettings.ttlSeconds).thenReturn(10000000)

        val inputFlux = Flux.just(mockView1, mockView2)

        val mockUpdateResultSuccess: UpdateResult = mock()
        val mockUpdateResultFailure: UpdateResult = mock()
        whenever(mockUpdateResultSuccess.modifiedCount).thenReturn(1)
        whenever(mockUpdateResultFailure.modifiedCount).thenReturn(0)

        whenever(
                ecommerceMongoTemplate.updateFirst(
                    any<Query>(),
                    any<Update>(),
                    eq(BaseTransactionView::class.java)
                )
            )
            .thenReturn(Mono.just(mockUpdateResultSuccess))
            .thenReturn(Mono.just(mockUpdateResultFailure))

        // ACT
        val result = transactionMigrationWriteService.updateViewsTtl(inputFlux)

        // ASSERT
        StepVerifier.create(result).expectNextCount(1).verifyComplete()

        verify(ecommerceMongoTemplate, times(2))
            .updateFirst(any<Query>(), any<Update>(), eq(BaseTransactionView::class.java))
    }

    @Test
    fun `should return empty when all TTL updates fail`() {
        // ARRANGE
        val mockView1: BaseTransactionView = mock()
        val mockView2: BaseTransactionView = mock()

        whenever(mockView1.transactionId).thenReturn("view-1")
        whenever(mockView2.transactionId).thenReturn("view-2")

        whenever(config.transactionsView).thenReturn(transactionsViewWriteSettings)
        whenever(transactionsViewWriteSettings.ttlSeconds).thenReturn(10000000)

        val inputFlux = Flux.just(mockView1, mockView2)

        whenever(
                ecommerceMongoTemplate.updateFirst(
                    any<Query>(),
                    any<Update>(),
                    eq(BaseTransactionView::class.java)
                )
            )
            .thenReturn(Mono.error(RuntimeException("TTL update failed")))

        // ACT
        val result = transactionMigrationWriteService.updateViewsTtl(inputFlux)

        // ASSERT
        StepVerifier.create(result).verifyComplete()

        verify(ecommerceMongoTemplate, times(2))
            .updateFirst(any<Query>(), any<Update>(), eq(BaseTransactionView::class.java))
    }

    @Test
    fun `should handle empty input Flux for TTL updates`() {
        // ARRANGE
        val inputFlux = Flux.empty<BaseTransactionView>()

        // ACT
        val result = transactionMigrationWriteService.updateViewsTtl(inputFlux)

        // ASSERT
        StepVerifier.create(result).verifyComplete()

        verify(ecommerceMongoTemplate, never())
            .updateFirst(any<Query>(), any<Update>(), any<Class<*>>())
    }

    @Test
    fun `should copy events and update TTL in pipeline`() {
        // ARRANGE
        val mockEvent1: BaseTransactionEvent<*> = mock()
        val mockEvent2: BaseTransactionEvent<*> = mock()

        whenever(mockEvent1.id).thenReturn("event-1")
        whenever(mockEvent2.id).thenReturn("event-2")

        whenever(config.eventstore).thenReturn(eventstoreWriteSettings)
        whenever(eventstoreWriteSettings.ttlSeconds).thenReturn(10000000)

        val inputFlux = Flux.just(mockEvent1, mockEvent2)

        whenever(eventHistoryRepository.save(any())).thenAnswer { invocation ->
            Mono.just<BaseTransactionEvent<*>>(invocation.getArgument(0))
        }

        val mockUpdateResult: UpdateResult = mock()
        whenever(mockUpdateResult.modifiedCount).thenReturn(1)
        whenever(
                ecommerceMongoTemplate.updateFirst(
                    any<Query>(),
                    any<Update>(),
                    eq(BaseTransactionEvent::class.java)
                )
            )
            .thenReturn(Mono.just(mockUpdateResult))

        // ACT
        val result =
            transactionMigrationWriteService.updateEventsTtl(
                transactionMigrationWriteService.writeEvents(inputFlux)
            )

        // ASSERT
        StepVerifier.create(result).expectNextCount(2).verifyComplete()

        verify(eventHistoryRepository, times(2)).save(any())
        verify(ecommerceMongoTemplate, times(2))
            .updateFirst(any<Query>(), any<Update>(), eq(BaseTransactionEvent::class.java))
    }

    @Test
    fun `should copy views and update TTL in pipeline`() {
        // ARRANGE
        val mockView1: BaseTransactionView = mock()
        val mockView2: BaseTransactionView = mock()

        whenever(mockView1.transactionId).thenReturn("view-1")
        whenever(mockView2.transactionId).thenReturn("view-2")

        whenever(config.transactionsView).thenReturn(transactionsViewWriteSettings)
        whenever(transactionsViewWriteSettings.ttlSeconds).thenReturn(10000000)

        val inputFlux = Flux.just(mockView1, mockView2)

        whenever(viewHistoryRepository.save(any())).thenAnswer { invocation ->
            Mono.just<BaseTransactionView>(invocation.getArgument(0))
        }

        val mockUpdateResult: UpdateResult = mock()
        whenever(mockUpdateResult.modifiedCount).thenReturn(1)
        whenever(
                ecommerceMongoTemplate.updateFirst(
                    any<Query>(),
                    any<Update>(),
                    eq(BaseTransactionView::class.java)
                )
            )
            .thenReturn(Mono.just(mockUpdateResult))

        // ACT
        val result =
            transactionMigrationWriteService.updateViewsTtl(
                transactionMigrationWriteService.writeTransactionViews(inputFlux)
            )

        // ASSERT
        StepVerifier.create(result).expectNextCount(2).verifyComplete()

        verify(viewHistoryRepository, times(2)).save(any())
        verify(ecommerceMongoTemplate, times(2))
            .updateFirst(any<Query>(), any<Update>(), eq(BaseTransactionView::class.java))
    }

    @Test
    fun `should handle partial failures in copy and TTL update pipeline for events`() {
        // ARRANGE
        val mockEvent1: BaseTransactionEvent<*> = mock()
        val mockEvent2: BaseTransactionEvent<*> = mock()
        val mockEvent3: BaseTransactionEvent<*> = mock()

        whenever(mockEvent1.id).thenReturn("event-1")
        whenever(mockEvent2.id).thenReturn("event-2")
        whenever(mockEvent3.id).thenReturn("event-3")

        whenever(config.eventstore).thenReturn(eventstoreWriteSettings)
        whenever(eventstoreWriteSettings.ttlSeconds).thenReturn(10000000)

        val inputFlux = Flux.just(mockEvent1, mockEvent2, mockEvent3)

        // Event 2 fails to copy
        whenever(eventHistoryRepository.save(any()))
            .thenReturn(Mono.just(mockEvent1))
            .thenReturn(Mono.error(RuntimeException("Copy failed")))
            .thenReturn(Mono.just(mockEvent3))

        val mockUpdateResult: UpdateResult = mock()
        whenever(mockUpdateResult.modifiedCount).thenReturn(1)

        // Event 3's TTL update fails
        whenever(
                ecommerceMongoTemplate.updateFirst(
                    any<Query>(),
                    any<Update>(),
                    eq(BaseTransactionEvent::class.java)
                )
            )
            .thenReturn(Mono.just(mockUpdateResult))
            .thenReturn(Mono.error(RuntimeException("TTL update failed")))

        // ACT
        val result =
            transactionMigrationWriteService.updateEventsTtl(
                transactionMigrationWriteService.writeEvents(inputFlux)
            )

        // ASSERT
        StepVerifier.create(result)
            .expectNextCount(1) // Only event-1 succeeds both operations
            .verifyComplete()

        verify(eventHistoryRepository, times(3)).save(any())
        verify(ecommerceMongoTemplate, times(2))
            .updateFirst(any<Query>(), any<Update>(), eq(BaseTransactionEvent::class.java))
    }
}
