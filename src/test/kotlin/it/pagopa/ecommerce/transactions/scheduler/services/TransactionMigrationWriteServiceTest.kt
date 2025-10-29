package it.pagopa.ecommerce.transactions.scheduler.services

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent
import it.pagopa.ecommerce.commons.documents.BaseTransactionView
import it.pagopa.ecommerce.transactions.scheduler.repositories.ecommercehistory.TransactionsEventStoreHistoryRepository
import it.pagopa.ecommerce.transactions.scheduler.repositories.ecommercehistory.TransactionsViewHistoryRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

@ExtendWith(MockitoExtension::class)
class TransactionMigrationWriteServiceTest {

    @Mock private lateinit var eventHistoryRepository: TransactionsEventStoreHistoryRepository

    @Mock private lateinit var viewHistoryRepository: TransactionsViewHistoryRepository

    @InjectMocks
    private lateinit var transactionMigrationWriteService: TransactionMigrationWriteService

    @Test
    fun `should write events successfully and return migrated events`() {
        val mockEvent1: BaseTransactionEvent<*> = mock()
        val mockEvent2: BaseTransactionEvent<*> = mock()

        whenever(mockEvent1.id).thenReturn("event-1")
        whenever(mockEvent2.id).thenReturn("event-2")

        val inputFlux = Flux.just(mockEvent1, mockEvent2)

        @Suppress("UNCHECKED_CAST")
        val typedRepository = eventHistoryRepository

        whenever(typedRepository.save(any())).thenAnswer { invocation ->
            Mono.just<Any>(invocation.getArgument(0))
        }

        // ACT
        val resultFlux = transactionMigrationWriteService.writeEvents(inputFlux)

        // ASSERT
        StepVerifier.create(resultFlux)
            .expectNext(mockEvent1)
            .expectNext(mockEvent2)
            .verifyComplete()

        verify(typedRepository, times(2)).save(any())
    }

    @Test
    fun `should skip failed event migrations and continue with successful ones`() {
        val mockEvent1: BaseTransactionEvent<*> = mock()
        val mockEvent2: BaseTransactionEvent<*> = mock()
        val mockEvent3: BaseTransactionEvent<*> = mock()

        whenever(mockEvent1.id).thenReturn("event-1")
        whenever(mockEvent2.id).thenReturn("event-2")
        whenever(mockEvent3.id).thenReturn("event-3")

        val inputFlux = Flux.just(mockEvent1, mockEvent2, mockEvent3)

        @Suppress("UNCHECKED_CAST")
        val typedRepository = eventHistoryRepository

        whenever(typedRepository.save(any()))
            .thenReturn(Mono.just(mockEvent1))
            .thenReturn(Mono.error(RuntimeException("Database error")))
            .thenReturn(Mono.just(mockEvent3))

        // ACT
        val resultFlux = transactionMigrationWriteService.writeEvents(inputFlux)

        // ASSERT
        StepVerifier.create(resultFlux)
            .expectNext(mockEvent1)
            .expectNext(mockEvent3)
            .verifyComplete()

        verify(typedRepository, times(3)).save(any())
    }

    @Test
    fun `should return empty Flux when all event migrations fail`() {
        val mockEvent1: BaseTransactionEvent<*> = mock()
        val mockEvent2: BaseTransactionEvent<*> = mock()
        whenever(mockEvent1.id).thenReturn("event-1")
        whenever(mockEvent2.id).thenReturn("event-2")

        val inputFlux = Flux.just(mockEvent1, mockEvent2)


        @Suppress("UNCHECKED_CAST")
        val typedRepository = eventHistoryRepository
        whenever(typedRepository.save(any()))
            .thenReturn(Mono.error(RuntimeException("Database error")))
            .thenReturn(Mono.error(RuntimeException("Database error")))

        // ACT
        val resultFlux = transactionMigrationWriteService.writeEvents(inputFlux)

        // ASSERT
        StepVerifier.create(resultFlux).verifyComplete()

        verify(typedRepository, times(2)).save(any())
    }

    @Test
    fun `should write transaction views successfully and return migrated views`() {
        val mockView1: BaseTransactionView = mock()
        val mockView2: BaseTransactionView = mock()

        whenever(mockView1.transactionId).thenReturn("view-1")
        whenever(mockView2.transactionId).thenReturn("view-2")

        val inputFlux = Flux.just(mockView1, mockView2)

        whenever(viewHistoryRepository.save(any())).thenAnswer { invocation ->
            Mono.just<Any>(invocation.getArgument(0))
        }

        // ACT
        val resultFlux = transactionMigrationWriteService.writeTransactionViews(inputFlux)

        // ASSERT
        StepVerifier.create(resultFlux).expectNext(mockView1).expectNext(mockView2).verifyComplete()

        verify(viewHistoryRepository, times(2)).save(any())
    }

    @Test
    fun `should skip failed view migrations and continue with successful ones`() {
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
        val resultFlux = transactionMigrationWriteService.writeTransactionViews(inputFlux)

        // ASSERT
        StepVerifier.create(resultFlux).expectNext(mockView1).expectNext(mockView3).verifyComplete()

        verify(viewHistoryRepository, times(3)).save(any())
    }

    @Test
    fun `should return empty Flux when all view migrations fail`() {
        val mockView: BaseTransactionView = mock()
        whenever(mockView.transactionId).thenReturn("view-1")

        val inputFlux = Flux.just(mockView)

        whenever(viewHistoryRepository.save(any()))
            .thenReturn(Mono.error(RuntimeException("Database error")))

        // ACT
        val resultFlux = transactionMigrationWriteService.writeTransactionViews(inputFlux)

        // ASSERT
        StepVerifier.create(resultFlux).verifyComplete()

        verify(viewHistoryRepository, times(1)).save(any())
    }

    @Test
    fun `should handle empty input Flux for events`() {
        val inputFlux = Flux.empty<BaseTransactionEvent<*>>()

        @Suppress("UNCHECKED_CAST")
        val typedRepository = eventHistoryRepository

        // ACT
        val resultFlux = transactionMigrationWriteService.writeEvents(inputFlux)

        // ASSERT
        StepVerifier.create(resultFlux).verifyComplete()

        verify(typedRepository, never()).save(any())
    }

    @Test
    fun `should handle empty input Flux for views`() {
        val inputFlux = Flux.empty<BaseTransactionView>()

        // ACT
        val resultFlux = transactionMigrationWriteService.writeTransactionViews(inputFlux)

        // ASSERT
        StepVerifier.create(resultFlux).verifyComplete()

        verify(viewHistoryRepository, never()).save(any())
    }
}
