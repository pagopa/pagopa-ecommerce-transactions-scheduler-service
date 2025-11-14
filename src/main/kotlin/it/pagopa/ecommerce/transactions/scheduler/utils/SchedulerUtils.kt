package it.pagopa.ecommerce.transactions.scheduler.utils

import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import org.springframework.scheduling.support.CronExpression

object SchedulerUtils {

    /**
     * Calculate the time interval in milliseconds between two consecutive executions based on a
     * cron expression
     *
     * @param chronExpression the cron expression
     * @return the time interval in milliseconds
     */
    fun getExecutionsInterleaveTimeMillis(chronExpression: String): Long {
        val cronSequenceGenerator = CronExpression.parse(chronExpression)
        val firstExecution = cronSequenceGenerator.next(LocalDateTime.now())!!
        val secondExecution = cronSequenceGenerator.next(firstExecution)!!
        return firstExecution.until(secondExecution, ChronoUnit.MILLIS)
    }

    fun getMaxDuration(executionInterleaveMillis: Long, batchMaxDurationSeconds: Int): Duration {
        val maxDuration =
            if (batchMaxDurationSeconds > 0) {
                Duration.ofSeconds(batchMaxDurationSeconds.toLong())
            } else {
                /*
                 * Batch max duration set to batch execution interleave divided by 2.
                 * The only constraint here is that the batch max execution time is less than
                 * the batch execution interleave in order to avoid one execution to be skipped
                 * because of the previous batch execution still running
                 */
                Duration.ofMillis(executionInterleaveMillis).dividedBy(2)
            }
        return maxDuration
    }

    fun getTransactionAnalyzerTimeWindow(
        batchExecutionRate: Long,
        executionRateMultiplier: Int
    ): Pair<LocalDateTime, LocalDateTime> {

        val windowLength = batchExecutionRate * executionRateMultiplier
        val upperThreshold = LocalDateTime.now().minus(batchExecutionRate, ChronoUnit.MILLIS)
        val lowerThreshold = upperThreshold.minus(windowLength, ChronoUnit.MILLIS)
        return Pair(lowerThreshold, upperThreshold)
    }
}
