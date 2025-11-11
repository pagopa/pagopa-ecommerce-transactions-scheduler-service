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
    @Value("\${pendingTransactions.batch.maxTransactionsPerPage}") val maxTransactionPerPage: Int,
    @Value("\${pendingTransactions.batch.pageAnalysisDelaySeconds}")
    val transactionPageAnalysisDelaySeconds: Int
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
        val executionInterleaveMillis =
            SchedulerUtils.getExecutionsInterleaveTimeMillis(chronExpression)
        val (lowerThreshold, upperThreshold) =
            SchedulerUtils.getTransactionAnalyzerTimeWindow(
                executionInterleaveMillis,
                executionRateMultiplier
            )

        val maxBatchExecutionTime =
            SchedulerUtils.getMaxDuration(executionInterleaveMillis, batchMaxDurationSeconds)
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
                    "Transaction analysis offset: [$lowerThreshold - $upperThreshold]. Total transactions found: [$totalCount], max transaction per page: [$maxTransactionPerPage], total pages: [$pages]. Delay between page analysis: [$transactionPageAnalysisDelaySeconds] seconds"
                )
                Pair(pages.toInt(), totalCount)
            }
            .flatMapMany { (pages, totalCount) ->
                Flux.fromStream(IntStream.range(0, pages).boxed().map { Pair(it, totalCount) })
            }
            .delayElements(Duration.ofSeconds(transactionPageAnalysisDelaySeconds.toLong()))
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
}
