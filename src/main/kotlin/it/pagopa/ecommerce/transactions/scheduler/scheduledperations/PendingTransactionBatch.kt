package it.pagopa.ecommerce.transactions.scheduler.scheduledperations

import it.pagopa.ecommerce.transactions.scheduler.transactionanalyzer.PendingTransactionAnalyzer
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.stream.IntStream
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.util.function.Tuple2

@Component
class PendingTransactionBatch(
    @Autowired val pendingTransactionAnalyzer: PendingTransactionAnalyzer,
    @Value("\${pendingTransactions.batch.scheduledChron}") val chronExpression: String,
    @Value("\${pendingTransactions.batch.transactionsAnalyzer.executionRateMultiplier}")
    val executionRateMultiplier: Int,
    val logger: Logger = LoggerFactory.getLogger(PendingTransactionBatch::class.java),
    @Value("\${pendingTransactions.batch.maxDurationSeconds}") val batchMaxDurationSeconds: Int,
    @Value("\${pendingTransactions.batch.maxTransactionsPerPage}") val maxTransactionPerPage: Int
) {

    @Scheduled(cron = "\${pendingTransactions.batch.scheduledChron}")
    fun execute() {
        val startTime = System.currentTimeMillis()
        pendingTransactionAnalyzerPaginatedPipeline()
            .subscribe(
                { allPageResult ->
                    allPageResult.forEach { pageResult ->
                        val elapsedTime = pageResult.t1
                        val (executionResult, pageCount) = pageResult.t2
                        logger.info(
                            "Process page $pageCount ok: [$executionResult], elapsed time: [$elapsedTime] ms "
                        )
                    }
                },
                { logger.error("Error executing batch", it) },
                {
                    logger.info(
                        "Overall execution time: [${System.currentTimeMillis() - startTime}] ms"
                    )
                }
            )
    }

    fun pendingTransactionAnalyzerPaginatedPipeline():
        Mono<MutableList<Tuple2<Long, Pair<Boolean, Int>>>> {
        val executionInterleaveMillis = getExecutionsInterleaveTimeMillis(chronExpression)
        val (lowerThreshold, upperThreshold) =
            getTransactionAnalyzerTimeWindow(executionInterleaveMillis, executionRateMultiplier)

        val maxBatchExecutionTime =
            getMaxDuration(executionInterleaveMillis, batchMaxDurationSeconds)
        logger.info(
            "Executions chron expression: [$chronExpression], executions interleave time: [$executionInterleaveMillis] ms.  Max execution duration: $maxBatchExecutionTime seconds"
        )
        return pendingTransactionAnalyzer
            .getTotalTransactionCount(lowerThreshold, upperThreshold)
            .map { totalCount ->
                val pages =
                    if ((totalCount.toInt() % maxTransactionPerPage) == 0) {
                        totalCount / maxTransactionPerPage
                    } else {
                        (totalCount / maxTransactionPerPage) + 1
                    }
                logger.info(
                    "Transaction analysis offset: [$lowerThreshold - $upperThreshold]. Total transactions found: [$totalCount], max transaction per page: [$maxTransactionPerPage], total pages: [$pages]"
                )
                Pair(pages.toInt(), totalCount)
            }
            .flatMapMany { (pages, totalCount) ->
                Flux.fromStream(IntStream.range(0, pages).boxed().map { Pair(it, totalCount) })
            }
            .flatMap { (page, totalCount) ->
                pendingTransactionAnalyzer
                    .searchPendingTransactions(
                        lowerThreshold,
                        upperThreshold,
                        executionInterleaveMillis,
                        totalCount,
                        PageRequest.of(page, maxTransactionPerPage)
                    )
                    .map { Pair(it, page) }
            }
            .elapsed()
            .collectList()
            .timeout(maxBatchExecutionTime)
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
