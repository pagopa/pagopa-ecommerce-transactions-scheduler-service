package it.pagopa.ecommerce.transactions.scheduler.services

import it.pagopa.ecommerce.commons.redis.reactivetemplatewrappers.ReactiveExclusiveLockDocumentWrapper
import it.pagopa.ecommerce.commons.repositories.ExclusiveLockDocument
import it.pagopa.ecommerce.transactions.scheduler.exceptions.LockNotAcquiredException
import java.time.Duration
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class SchedulerLockServiceTest {

    private val reactiveExclusiveLockDocumentWrapper: ReactiveExclusiveLockDocumentWrapper = mock()
    private val schedulerLockService = SchedulerLockService(reactiveExclusiveLockDocumentWrapper)

    @Test
    fun `should acquire lock successfully`() {
        val jobName = "test-job"
        val ttl = Duration.ofMinutes(5)
        val lockDocument = ExclusiveLockDocument(jobName, SchedulerLockService.OWNER)

        whenever(reactiveExclusiveLockDocumentWrapper.saveIfAbsent(any(), any()))
            .thenReturn(Mono.just(true))

        StepVerifier.create(schedulerLockService.acquireJobLock(jobName, ttl))
            .expectNextMatches { doc ->
                doc.id() == jobName && doc.holderName() == SchedulerLockService.OWNER
            }
            .verifyComplete()

        verify(reactiveExclusiveLockDocumentWrapper)
            .saveIfAbsent(
                argThat { doc ->
                    doc.id() == jobName && doc.holderName() == SchedulerLockService.OWNER
                },
                eq(ttl)
            )
    }

    @Test
    fun `should throw exception when lock is not acquired`() {
        val jobName = "test-job"
        val ttl = Duration.ofMinutes(5)

        whenever(reactiveExclusiveLockDocumentWrapper.saveIfAbsent(any(), any()))
            .thenReturn(Mono.just(false))

        StepVerifier.create(schedulerLockService.acquireJobLock(jobName, ttl))
            .expectErrorMatches { error ->
                error is LockNotAcquiredException && error.message!!.contains("test-job")
            }
            .verify()

        verify(reactiveExclusiveLockDocumentWrapper).saveIfAbsent(any(), any())
    }

    @Test
    fun `should handle error when acquiring lock`() {
        val jobName = "test-job"
        val ttl = Duration.ofMinutes(5)
        val testException = RuntimeException("Redis connection error")

        whenever(reactiveExclusiveLockDocumentWrapper.saveIfAbsent(any(), any()))
            .thenReturn(Mono.error(testException))

        StepVerifier.create(schedulerLockService.acquireJobLock(jobName, ttl)).verifyError()

        verify(reactiveExclusiveLockDocumentWrapper).saveIfAbsent(any(), any())
    }

    @Test
    fun `should release lock successfully`() {
        val jobName = "test-job"
        val lockDocument = ExclusiveLockDocument(jobName, SchedulerLockService.OWNER)

        whenever(reactiveExclusiveLockDocumentWrapper.deleteById(jobName))
            .thenReturn(Mono.just(true))

        StepVerifier.create(schedulerLockService.releaseJobLock(lockDocument))
            .expectNext(true)
            .verifyComplete()

        verify(reactiveExclusiveLockDocumentWrapper).deleteById(jobName)
    }

    @Test
    fun `should handle error when releasing lock`() {
        val jobName = "test-job"
        val lockDocument = ExclusiveLockDocument(jobName, SchedulerLockService.OWNER)
        val testException = RuntimeException("Redis connection error")

        whenever(reactiveExclusiveLockDocumentWrapper.deleteById(jobName))
            .thenReturn(Mono.error(testException))

        StepVerifier.create(schedulerLockService.releaseJobLock(lockDocument)).verifyError()

        verify(reactiveExclusiveLockDocumentWrapper).deleteById(jobName)
    }

    @Test
    fun `should return false when lock is not released`() {
        val jobName = "test-job"
        val lockDocument = ExclusiveLockDocument(jobName, SchedulerLockService.OWNER)

        whenever(reactiveExclusiveLockDocumentWrapper.deleteById(jobName))
            .thenReturn(Mono.just(false))

        StepVerifier.create(schedulerLockService.releaseJobLock(lockDocument))
            .expectNext(false)
            .verifyComplete()

        verify(reactiveExclusiveLockDocumentWrapper).deleteById(jobName)
    }

    @Test
    fun `should use correct owner in lock document`() {
        val jobName = "test-job"
        val ttl = Duration.ofMinutes(5)

        whenever(reactiveExclusiveLockDocumentWrapper.saveIfAbsent(any(), any()))
            .thenReturn(Mono.just(true))

        StepVerifier.create(schedulerLockService.acquireJobLock(jobName, ttl))
            .expectNextMatches { doc -> doc.holderName() == "transactions-scheduler-service" }
            .verifyComplete()

        verify(reactiveExclusiveLockDocumentWrapper)
            .saveIfAbsent(
                argThat { doc -> doc.holderName() == "transactions-scheduler-service" },
                eq(ttl)
            )
    }
}
