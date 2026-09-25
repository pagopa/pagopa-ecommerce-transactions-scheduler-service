package it.pagopa.ecommerce.transactions.scheduler.scheduledperations

import it.pagopa.ecommerce.commons.mdcutilities.LogTracingUtils
import it.pagopa.ecommerce.transactions.scheduler.services.SchedulerLockService
import it.pagopa.ecommerce.transactions.scheduler.transactionanalyzer.PendingTransactionAnalyzer
import it.pagopa.ecommerce.transactions.scheduler.utils.SchedulerUtils
import java.time.Duration
import java.util.stream.IntStream
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.util.function.Tuple2

@Component
class PendingTransactionBatch(
    @Autowired val pendingTransactionAnalyzer: PendingTransactionAnalyzer,
    @Autowired val schedulerLockService: SchedulerLockService,
    @Value("\${pendingTransactions.batch.scheduledChron}") val chronExpression: String,
    @Value("\${pendingTransactions.batch.transactionsAnalyzer.executionRateMultiplier}")
    val executionRateMultiplier: Int,
    val logger: Logger = LoggerFactory.getLogger(PendingTransactionBatch::class.java),
    @Value("\${pendingTransactions.batch.maxDurationSeconds}") val batchMaxDurationSeconds: Int,
    @Value("\${pendingTransactions.batch.maxTransactionsPerPage}") val maxTransactionPerPage: Int,
    @Value("\${pendingTransactions.batch.pageAnalysisDelaySeconds}")
    val transactionPageAnalysisDelaySeconds: Int,
    @Value("\${pendingTransactions.batch.exclusiveLockDocument.ttlSeconds}") val lockTtlSeconds: Int
) {

    @Scheduled(cron = "\${pendingTransactions.batch.scheduledChron}")
    suspend fun execute() {
        val startTime = System.currentTimeMillis()
        val lockTtl = Duration.ofSeconds(lockTtlSeconds.toLong())
        schedulerLockService
            // acquire lock
            .acquireJobLock(jobName = "pending-transactions-batch", ttl = lockTtl)
            .flatMap { lockDocument ->
                // run job/batch
                pendingTransactionAnalyzerPaginatedPipeline()
                    .doOnSuccess { allPageResult ->
                        allPageResult.forEach { pageResult ->
                            val elapsedTime = pageResult.t1
                            val (executionResult, pageCount) = pageResult.t2
                            LogTracingUtils.loggerTracingUtils()
                                .success()
                                .details(
                                    mapOf(
                                        "page_number" to pageCount.toString(),
                                        "execution_result" to executionResult.toString(),
                                        "elapsed_time_millis" to elapsedTime.toString()
                                    )
                                )
                                .logInfo(logger, "Process page completed")
                        }
                        LogTracingUtils.loggerTracingUtils()
                            .success()
                            .details(
                                mapOf(
                                    "total_elapsed_time_millis" to
                                        (System.currentTimeMillis() - startTime).toString()
                                )
                            )
                            .logInfo(logger, "Overall processing completed")
                    }
                    .doOnError {
                        LogTracingUtils.loggerTracingUtils()
                            .failure()
                            .logErrorWithStackTrace(
                                logger,
                                it,
                                "Exception processing pending-transactions-batch"
                            )
                    }
                    .then(Mono.just(lockDocument))
                    .onErrorResume { Mono.just(lockDocument) }
            }
            .flatMap { lockDocument ->
                schedulerLockService
                    // release lock (always runs)
                    .releaseJobLock(lockDocument)
                    .doOnSuccess {
                        LogTracingUtils.loggerTracingUtils()
                            .success()
                            .logDebug(logger, "Lock released successfully")
                    }
                    .doOnError {
                        LogTracingUtils.loggerTracingUtils()
                            .failure()
                            .logErrorWithStackTrace(logger, it, "Failed to release lock")
                    }
                    .onErrorResume { Mono.empty() }
            }
            .onErrorResume { error ->
                LogTracingUtils.loggerTracingUtils()
                    .failure()
                    .logErrorWithStackTrace(
                        logger,
                        error,
                        "Job execution failed for pending-transactions-batch"
                    )
                Mono.empty()
            }
            .awaitSingleOrNull()
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
        LogTracingUtils.loggerTracingUtils()
            .success()
            .details(
                mapOf(
                    "cron_expression" to chronExpression,
                    "execution_interleave_millis" to executionInterleaveMillis.toString(),
                    "max_execution_duration_seconds" to maxBatchExecutionTime.seconds.toString()
                )
            )
            .logInfo(logger, "Pipeline execution configuration initialized")
        return pendingTransactionAnalyzer
            .getTotalTransactionCount(lowerThreshold, upperThreshold)
            .map { totalCount ->
                val pages =
                    if ((totalCount.toInt() % maxTransactionPerPage) == 0) {
                        totalCount / maxTransactionPerPage
                    } else {
                        (totalCount / maxTransactionPerPage) + 1
                    }
                LogTracingUtils.loggerTracingUtils()
                    .success()
                    .details(
                        mapOf(
                            "time_offset_lower" to lowerThreshold.toString(),
                            "time_offset_upper" to upperThreshold.toString(),
                            "total_transactions" to totalCount.toString(),
                            "max_transaction_per_page" to maxTransactionPerPage.toString(),
                            "total_pages" to pages.toString(),
                            "page_analysis_delay_seconds" to
                                transactionPageAnalysisDelaySeconds.toString()
                        )
                    )
                    .logInfo(logger, "Transaction analysis parameters calculated")
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
