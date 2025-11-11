package it.pagopa.ecommerce.transactions.scheduler.services

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent
import it.pagopa.ecommerce.transactions.scheduler.utils.CommonTracingUtils
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import reactor.core.publisher.Flux
import reactor.test.StepVerifier

@ExtendWith(MockitoExtension::class)
class EventstoreMigrationOrchestratorTest {
    @Mock private lateinit var transactionMigrationQueryService: TransactionMigrationQueryService
    @Mock private lateinit var transactionMigrationWriteService: TransactionMigrationWriteService
    @Mock private lateinit var commonTracingUtils: CommonTracingUtils
    @InjectMocks
    private lateinit var eventstoreMigrationOrchestrator: EventStoreMigrationOrchestrator

    @Test
    fun `should migrate transactions successfully`() {
        // ARRANGE
        val event1 = mock(BaseTransactionEvent::class.java)
        val event2 = mock(BaseTransactionEvent::class.java)
        val event3 = mock(BaseTransactionEvent::class.java)
        val eventsFlux = Flux.just(event1, event2, event3)

        whenever(transactionMigrationQueryService.findEligibleEvents()).thenReturn(eventsFlux)
        whenever(transactionMigrationWriteService.writeEvents(any())).thenAnswer { it.arguments[0] }
        whenever(transactionMigrationWriteService.updateEventsTtl(any())).thenAnswer {
            it.arguments[0]
        }
        doNothing().`when`(commonTracingUtils).addSpan(anyOrNull(), anyOrNull())

        // ACT
        StepVerifier.create(eventstoreMigrationOrchestrator.createMigrationPipeline())
            .expectNextCount(1)
            .verifyComplete()

        // ASSERT
        verify(transactionMigrationQueryService, times(1)).findEligibleEvents()
        verify(transactionMigrationWriteService, times(1)).writeEvents(any())
        verify(transactionMigrationWriteService, times(1)).updateEventsTtl(any())
        verify(commonTracingUtils, times(1)).addSpan(anyOrNull(), anyOrNull())
    }

    @Test
    fun `should handle error correctly`() {
        // ARRANGE

        whenever(transactionMigrationQueryService.findEligibleEvents())
            .thenReturn(Flux.error { RuntimeException("Test error") })
        whenever(transactionMigrationWriteService.writeEvents(any())).thenAnswer { it.arguments[0] }
        whenever(transactionMigrationWriteService.updateEventsTtl(any())).thenAnswer {
            it.arguments[0]
        }

        // ACT
        StepVerifier.create(eventstoreMigrationOrchestrator.createMigrationPipeline())
            .expectNextCount(0)
            .verifyComplete()

        // ASSERT
        verify(transactionMigrationQueryService, times(1)).findEligibleEvents()
        verify(transactionMigrationWriteService, times(1)).writeEvents(any())
        verify(transactionMigrationWriteService, times(1)).updateEventsTtl(any())
    }
}
