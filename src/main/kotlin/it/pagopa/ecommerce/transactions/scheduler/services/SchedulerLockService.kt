package it.pagopa.ecommerce.transactions.scheduler.services

import it.pagopa.ecommerce.commons.redis.reactivetemplatewrappers.ReactiveExclusiveLockDocumentWrapper
import it.pagopa.ecommerce.commons.repositories.ExclusiveLockDocument
import it.pagopa.ecommerce.transactions.scheduler.exceptions.LockNotAcquiredException
import java.time.Duration
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono

class SchedulerLockService(
    private val reactiveExclusiveLockDocumentWrapper: ReactiveExclusiveLockDocumentWrapper
) {
    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    companion object {
        const val OWNER = "transactions-scheduler-service"
    }

    /**
     * Acquire exclusive lock for a job. Returns the lock document if successful, throws exception
     * if lock cannot be acquired
     *
     * @param jobName the name of the job to lock
     * @param ttl the time-to-live duration for the lock
     * @return Mono with the ExclusiveLockDocument if lock acquired successfully
     * @throws LockNotAcquiredException if lock cannot be acquired
     */
    fun acquireJobLock(jobName: String, ttl: Duration): Mono<ExclusiveLockDocument> {
        val lockDocument = ExclusiveLockDocument(jobName, OWNER)
        logger.info("Trying to acquire lock for job: {} with TTL: {}", jobName, ttl)
        return reactiveExclusiveLockDocumentWrapper.saveIfAbsent(lockDocument, ttl).flatMap {
            lockAcquired ->
            if (lockAcquired) {
                logger.info("Lock acquired for job: {}", jobName)
                Mono.just(lockDocument)
            } else {
                logger.warn("Lock not acquired for job: {}, another instance is running", jobName)
                Mono.error(LockNotAcquiredException(jobName, lockDocument))
            }
        }
    }

    /**
     * Release exclusive lock for a job
     *
     * @param lockDocument the lock document to release
     * @return Mono<Boolean> indicating if the lock was released successfully
     */
    fun releaseJobLock(lockDocument: ExclusiveLockDocument): Mono<Boolean> {
        logger.info("Releasing lock for job: {}", lockDocument.id())
        return reactiveExclusiveLockDocumentWrapper
            .deleteById(lockDocument.id())
            .doOnNext { deleted ->
                logger.info("Lock with id: [{}], deleted: [{}]", lockDocument.id(), deleted)
            }
            .doOnError { error ->
                logger.error("Error releasing lock with id: [{}]", lockDocument.id(), error)
            }
    }
}
