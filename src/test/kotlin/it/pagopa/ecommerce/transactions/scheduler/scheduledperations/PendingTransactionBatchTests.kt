package it.pagopa.ecommerce.transactions.scheduler.scheduledperations

import it.pagopa.ecommerce.transactions.scheduler.transactionanalyzer.PendingTransactionAnalyzer
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime
import kotlin.time.toJavaDuration
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.given
import org.springframework.test.context.TestPropertySource
import reactor.core.publisher.Mono

@ExtendWith(MockitoExtension::class)
@TestPropertySource(locations = ["classpath:application-tests.properties"])
@OptIn(ExperimentalTime::class)
class PendingTransactionBatchTests {

    @Mock private lateinit var pendingTransactionAnalyzer: PendingTransactionAnalyzer

    private lateinit var pendingTransactionBatch: PendingTransactionBatch

    private val cronExecutionString = "*/10 * * * * *"

    private val executionRateMultiplier = 2

    private val maxDurationSeconds = 5

    @BeforeEach
    fun init() {
        pendingTransactionBatch =
            PendingTransactionBatch(
                pendingTransactionAnalyzer = pendingTransactionAnalyzer,
                chronExpression = cronExecutionString,
                executionRateMultiplier = executionRateMultiplier,
                batchMaxDurationSeconds = maxDurationSeconds
            )
    }

    @Test
    fun `Should execute successfully`() {
        // assertions
        BDDMockito.given(pendingTransactionAnalyzer.searchPendingTransactions(any(), any(), any()))
            .willReturn(Mono.just(true))
        assertDoesNotThrow { pendingTransactionBatch.execute() }
    }

    @Test
    fun `Should get batch intertime executions correctly`() {
        val intertime =
            pendingTransactionBatch.getExecutionsInterleaveTimeMillis(cronExecutionString)
        Assertions.assertEquals(10000, intertime)
    }

    @Test
    fun `Should get transactions to analyze time window correctly`() {
        val (lower, upper) =
            pendingTransactionBatch.getTransactionAnalyzerTimeWindow(
                TimeUnit.HOURS.toMillis(1),
                executionRateMultiplier
            )
        // assert that the time difference between lower and upper time window is 2 hours = 2 times
        // the execution window
        Assertions.assertEquals(2, lower.until(upper, ChronoUnit.HOURS))
    }

    @Test
    fun `Should get batch max duration for max duration configured`() {
        val interTimeExecutionDuration = Duration.ofMinutes(10)
        val maxBatchDuration = Duration.ofMinutes(5)
        val calculatedMaxDuration =
            pendingTransactionBatch.getMaxDuration(
                interTimeExecutionDuration.toMillis(),
                maxBatchDuration.toSeconds().toInt()
            )
        // assertions
        Assertions.assertEquals(maxBatchDuration, calculatedMaxDuration)
    }

    @Test
    fun `Should get batch max duration for max duration not configured as half execution intertime`() {
        val interTimeExecutionDuration = Duration.ofMinutes(10)
        val calculatedMaxDuration =
            pendingTransactionBatch.getMaxDuration(Duration.ofMinutes(10).toMillis(), -1)
        // assertions
        Assertions.assertEquals(
            interTimeExecutionDuration.toMillis() / 2,
            calculatedMaxDuration.toMillis()
        )
    }

    @Test
    fun `Should handle batch execution error without throwing exception`() {
        // assertions
        BDDMockito.given(pendingTransactionAnalyzer.searchPendingTransactions(any(), any(), any()))
            .willReturn(Mono.error(RuntimeException("Error executing batch")))
        assertDoesNotThrow { pendingTransactionBatch.execute() }
    }

    @Test
    fun `Should handle batch execution that takes longer than max duration configured`() {
        // assertions
        val pendingTransactionBatch =
            PendingTransactionBatch(
                pendingTransactionAnalyzer = pendingTransactionAnalyzer,
                chronExpression = cronExecutionString,
                executionRateMultiplier = executionRateMultiplier,
                batchMaxDurationSeconds = 1
            )
        val maxExecutionTime =
            pendingTransactionBatch.getMaxDuration(
                pendingTransactionBatch.getExecutionsInterleaveTimeMillis(
                    pendingTransactionBatch.chronExpression
                ),
                pendingTransactionBatch.batchMaxDurationSeconds
            )
        val pendingTransactionBatchTaskDuration = maxExecutionTime.multipliedBy(100)
        given { pendingTransactionAnalyzer.searchPendingTransactions(any(), any(), any()) }
            .willReturn(Mono.just(true).delayElement(pendingTransactionBatchTaskDuration))

        val duration =
            measureTime {
                    val exception =
                        assertThrows<Exception> {
                            pendingTransactionBatch.pendingTransactionAnalyzerPipeline().block()
                        }
                    assertTrue(exception.cause is TimeoutException)
                }
                .toJavaDuration()
        assertTrue(duration < pendingTransactionBatchTaskDuration)
        assertEquals(pendingTransactionBatch.batchMaxDurationSeconds, duration.seconds.toInt())
    }
}
