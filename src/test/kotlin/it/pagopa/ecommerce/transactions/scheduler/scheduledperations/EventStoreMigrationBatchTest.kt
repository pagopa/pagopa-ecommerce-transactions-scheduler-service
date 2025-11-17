package it.pagopa.ecommerce.transactions.scheduler.scheduledperations

import it.pagopa.ecommerce.commons.repositories.ExclusiveLockDocument
import it.pagopa.ecommerce.transactions.scheduler.services.EventStoreMigrationOrchestrator
import it.pagopa.ecommerce.transactions.scheduler.services.SchedulerLockService
import java.time.Duration
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import reactor.core.publisher.Mono

class EventstoreMigrationBatchTest {
    private val eventstoreMigrationOrchestrator: EventStoreMigrationOrchestrator = mock()
    private val schedulerLockService: SchedulerLockService = mock()
    private val lockTtlSeconds = 60

    private val eventstoreMigrationBatch =
        EventstoreMigrationBatch(
            eventstoreMigrationOrchestrator,
            schedulerLockService,
            lockTtlSeconds
        )

    @Test
    fun `should acquire lock, run migration, and release lock on success`() {
        // Arrange
        val jobName = "eventstore-migration-batch"
        val lockDocument = ExclusiveLockDocument(jobName, "test-owner")

        whenever(schedulerLockService.acquireJobLock(any(), any()))
            .thenReturn(Mono.just(lockDocument))
        whenever(eventstoreMigrationOrchestrator.runMigration()).thenReturn(Mono.empty())
        whenever(schedulerLockService.releaseJobLock(any())).thenReturn(Mono.just(true))

        // Act
        eventstoreMigrationBatch.execute()

        // Assert
        val order = inOrder(schedulerLockService, eventstoreMigrationOrchestrator)

        order
            .verify(schedulerLockService, times(1))
            .acquireJobLock(eq(jobName), eq(Duration.ofSeconds(lockTtlSeconds.toLong())))
        order.verify(eventstoreMigrationOrchestrator, times(1)).runMigration()
        order.verify(schedulerLockService, times(1)).releaseJobLock(lockDocument)
    }

    @Test
    fun `should release lock even if migration fails`() {
        // Arrange
        val jobName = "eventstore-migration-batch"
        val lockDocument = ExclusiveLockDocument(jobName, "test-owner")
        val migrationException = RuntimeException("Migration failed!")

        whenever(schedulerLockService.acquireJobLock(any(), any()))
            .thenReturn(Mono.just(lockDocument))
        whenever(eventstoreMigrationOrchestrator.runMigration())
            .thenReturn(Mono.error(migrationException))
        whenever(schedulerLockService.releaseJobLock(any())).thenReturn(Mono.just(true))

        // Act
        eventstoreMigrationBatch.execute()

        // Assert
        val order = inOrder(schedulerLockService, eventstoreMigrationOrchestrator)

        order
            .verify(schedulerLockService, times(1))
            .acquireJobLock(eq(jobName), eq(Duration.ofSeconds(lockTtlSeconds.toLong())))
        order.verify(eventstoreMigrationOrchestrator, times(1)).runMigration()
        order.verify(schedulerLockService, times(1)).releaseJobLock(lockDocument)
    }

    @Test
    fun `should not run migration or release lock if lock is not acquired`() {
        // Arrange
        val jobName = "eventstore-migration-batch"
        whenever(schedulerLockService.acquireJobLock(any(), any())).thenReturn(Mono.empty())

        // Act
        eventstoreMigrationBatch.execute()

        // Assert
        verify(schedulerLockService, times(1))
            .acquireJobLock(eq(jobName), eq(Duration.ofSeconds(lockTtlSeconds.toLong())))
        verify(eventstoreMigrationOrchestrator, never()).runMigration()
        verify(schedulerLockService, never()).releaseJobLock(any())
    }

    @Test
    fun `should not run migration if lock acquisition fails with an error`() {
        // Arrange
        val jobName = "eventstore-migration-batch"
        val acquisitionException = RuntimeException("Failed to acquire lock")
        whenever(schedulerLockService.acquireJobLock(any(), any()))
            .thenReturn(Mono.error(acquisitionException))

        // Act
        eventstoreMigrationBatch.execute()

        // Assert
        verify(schedulerLockService, times(1))
            .acquireJobLock(eq(jobName), eq(Duration.ofSeconds(lockTtlSeconds.toLong())))
        verify(eventstoreMigrationOrchestrator, never()).runMigration()
        verify(schedulerLockService, never()).releaseJobLock(any())
    }

    @Test
    fun `should run migration even if releasing the lock fails`() {
        // Arrange
        val jobName = "eventstore-migration-batch"
        val lockDocument = ExclusiveLockDocument(jobName, "test-owner")
        val releaseException = RuntimeException("Failed to release lock")

        whenever(schedulerLockService.acquireJobLock(any(), any()))
            .thenReturn(Mono.just(lockDocument))
        whenever(eventstoreMigrationOrchestrator.runMigration()).thenReturn(Mono.empty())
        whenever(schedulerLockService.releaseJobLock(any()))
            .thenReturn(Mono.error(releaseException))

        // Act
        eventstoreMigrationBatch.execute()

        // Assert
        val order = inOrder(schedulerLockService, eventstoreMigrationOrchestrator)

        order
            .verify(schedulerLockService, times(1))
            .acquireJobLock(eq(jobName), eq(Duration.ofSeconds(lockTtlSeconds.toLong())))
        order.verify(eventstoreMigrationOrchestrator, times(1)).runMigration()
        order.verify(schedulerLockService, times(1)).releaseJobLock(lockDocument)
    }
}
