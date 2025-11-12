package it.pagopa.ecommerce.transactions.scheduler.scheduledperations

import it.pagopa.ecommerce.commons.repositories.ExclusiveLockDocument
import it.pagopa.ecommerce.transactions.scheduler.services.SchedulerLockService
import it.pagopa.ecommerce.transactions.scheduler.services.TransactionsViewMigrationOrchestrator
import java.time.Duration
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import reactor.core.publisher.Mono
import reactor.util.function.Tuples

class TransactionsViewMigrationBatchTest {
    private val transactionsViewMigrationOrchestrator: TransactionsViewMigrationOrchestrator =
        mock()
    private val schedulerLockService: SchedulerLockService = mock()
    private val lockTtlSeconds = 60

    private val transactionsViewMigrationBatch =
        TransactionsViewMigrationBatch(
            transactionsViewMigrationOrchestrator,
            schedulerLockService,
            lockTtlSeconds
        )

    @Test
    fun `execute should acquire lock, run migration, and release lock`() {
        // Arrange
        val lockDocument = ExclusiveLockDocument("transactions-view-migration-batch", "test-owner")
        whenever(schedulerLockService.acquireJobLock(any(), any()))
            .thenReturn(Mono.just(lockDocument))
        whenever(transactionsViewMigrationOrchestrator.runMigration())
            .thenReturn(Mono.just(Tuples.of(1000L, 10L)))
        whenever(schedulerLockService.releaseJobLock(any())).thenReturn(Mono.just(true))

        // Act
        transactionsViewMigrationBatch.execute()

        // Assert
        verify(schedulerLockService, times(1))
            .acquireJobLock(
                eq("transactions-view-migration-batch"),
                eq(Duration.ofSeconds(lockTtlSeconds.toLong()))
            )
        verify(transactionsViewMigrationOrchestrator, times(1)).runMigration()
        verify(schedulerLockService, times(1)).releaseJobLock(lockDocument)
    }
}
