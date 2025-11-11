package it.pagopa.ecommerce.transactions.scheduler.services

import it.pagopa.ecommerce.commons.documents.BaseTransactionView
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
class TransactionsViewMigrationOrchestratorTest {
    @Mock private lateinit var transactionMigrationQueryService: TransactionMigrationQueryService
    @Mock private lateinit var transactionMigrationWriteService: TransactionMigrationWriteService
    @Mock private lateinit var commonTracingUtils: CommonTracingUtils
    @InjectMocks
    private lateinit var transactionsViewMigrationOrchestrator:
        TransactionsViewMigrationOrchestrator

    @Test
    fun `should migrate transactions successfully`() {
        // ARRANGE
        val transactionView1 = mock(BaseTransactionView::class.java)
        val transactionView2 = mock(BaseTransactionView::class.java)
        val transactionView3 = mock(BaseTransactionView::class.java)
        val transactionsViewFlux = Flux.just(transactionView1, transactionView2, transactionView3)

        whenever(transactionMigrationQueryService.findEligibleTransactions())
            .thenReturn(transactionsViewFlux)
        whenever(transactionMigrationWriteService.writeTransactionViews(any())).thenAnswer {
            it.arguments[0]
        }
        whenever(transactionMigrationWriteService.updateViewsTtl(any())).thenAnswer {
            it.arguments[0]
        }
        doNothing().`when`(commonTracingUtils).addSpan(anyOrNull(), anyOrNull())

        // ACT
        StepVerifier.create(transactionsViewMigrationOrchestrator.createMigrationPipeline())
            .expectNextCount(1)
            .verifyComplete()

        // ASSERT
        verify(transactionMigrationQueryService, times(1)).findEligibleTransactions()
        verify(transactionMigrationWriteService, times(1)).writeTransactionViews(any())
        verify(transactionMigrationWriteService, times(1)).updateViewsTtl(any())
        verify(commonTracingUtils, times(1)).addSpan(anyOrNull(), anyOrNull())
    }

    @Test
    fun `should handle error correctly`() {
        // ARRANGE

        whenever(transactionMigrationQueryService.findEligibleTransactions())
            .thenReturn(Flux.error { RuntimeException("Test error") })
        whenever(transactionMigrationWriteService.writeTransactionViews(any())).thenAnswer {
            it.arguments[0]
        }
        whenever(transactionMigrationWriteService.updateViewsTtl(any())).thenAnswer {
            it.arguments[0]
        }

        // ACT
        StepVerifier.create(transactionsViewMigrationOrchestrator.createMigrationPipeline())
            .expectNextCount(0)
            .verifyComplete()

        // ASSERT
        verify(transactionMigrationQueryService, times(1)).findEligibleTransactions()
        verify(transactionMigrationWriteService, times(1)).writeTransactionViews(any())
        verify(transactionMigrationWriteService, times(1)).updateViewsTtl(any())
    }
}
