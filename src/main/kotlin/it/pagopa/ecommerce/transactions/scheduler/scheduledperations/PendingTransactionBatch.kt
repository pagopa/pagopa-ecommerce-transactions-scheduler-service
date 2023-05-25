package it.pagopa.ecommerce.transactions.scheduler.scheduledperations

import it.pagopa.ecommerce.transactions.scheduler.transactionanalyzer.PendingTransactionAnalyzer
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Component

@Component
class PendingTransactionBatch(
    @Autowired val pendingTransactionAnalyzer: PendingTransactionAnalyzer,
    @Value("\${pendingTransactions.batch.scheduledChron}") val chronExpression: String,
    @Value("\${pendingTransactions.batch.transactionsAnalyzer.executionRateMultiplier}")
    val executionRateMultiplier: Int,
    val logger: Logger = LoggerFactory.getLogger(PendingTransactionBatch::class.java),
    @Value("\${pendingTransactions.batch.maxDurationSeconds}") val batchMaxDurationSeconds: Int
) {

    @Scheduled(cron = "\${pendingTransactions.batch.scheduledChron}")
    fun execute(): Boolean {

        val executionInterleaveMillis = getExecutionsInterleaveTimeMillis(chronExpression)

        val (lowerThreshold, upperThreshold) =
            getTransactionAnalyzerTimeWindow(executionInterleaveMillis, executionRateMultiplier)

        val maxBatchExecutionTime =
            getMaxDuration(executionInterleaveMillis, batchMaxDurationSeconds)
        logger.info(
            "Executions chron expression: [$chronExpression], executions interleave time: [$executionInterleaveMillis] ms. Transaction analysis offset: [$lowerThreshold - $upperThreshold]. Max execution duration: $maxBatchExecutionTime"
        )

        var isOk = false
        runCatching {
                pendingTransactionAnalyzer
                    .searchPendingTransactions(
                        lowerThreshold,
                        upperThreshold,
                        executionInterleaveMillis
                    )
                    .elapsed()
                    .block(maxBatchExecutionTime)!!
            }
            .onSuccess {
                logger.info(
                    "Batch pending transaction analysis end. Is process ok: [${it.t2}] Elapsed time: [${it.t1}] ms "
                )
                isOk = true
            }
            .onFailure {
                logger.error("Error executing batch", it)
                // throw it
            }
        return isOk
    }

    fun getMaxDuration(executionInterleaveMillis: Long, batchMaxDurationSeconds: Int): Duration {
        val maxDuration =
            if (batchMaxDurationSeconds > 0) {
                Duration.ofSeconds(batchMaxDurationSeconds.toLong())
            } else {
                Duration.ofMillis(executionInterleaveMillis).dividedBy(2)
            }
        return maxDuration
    }

    fun getExecutionsInterleaveTimeMillis(chronExpression: String): Long {
        val cronSequenceGenerator = CronExpression.parse(chronExpression)
        val firstExecution = cronSequenceGenerator.next(LocalDateTime.now())!!
        val secondExecution = cronSequenceGenerator.next(firstExecution)!!
        return firstExecution.until(secondExecution, ChronoUnit.MILLIS)
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
