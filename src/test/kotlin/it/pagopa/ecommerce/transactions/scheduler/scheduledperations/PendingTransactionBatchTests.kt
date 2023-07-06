package it.pagopa.ecommerce.transactions.scheduler.scheduledperations

import it.pagopa.ecommerce.transactions.scheduler.transactionanalyzer.PendingTransactionAnalyzer
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.given
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.springframework.test.context.TestPropertySource
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.stream.Stream
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime
import kotlin.time.toJavaDuration

@ExtendWith(MockitoExtension::class)
@TestPropertySource(locations = ["classpath:application-tests.properties"])
@OptIn(ExperimentalTime::class)
class PendingTransactionBatchTests {

    @Mock
    private lateinit var pendingTransactionAnalyzer: PendingTransactionAnalyzer

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
                batchMaxDurationSeconds = maxDurationSeconds,
                maxTransactionPerPage = maxTransactionPerPage,
            )
    }

    @Test
    fun `Should execute successfully`() {
        // assertions
        given(pendingTransactionAnalyzer.getTotalTransactionCount(any(), any()))
            .willReturn(Mono.just(1L))
        given(
            pendingTransactionAnalyzer.searchPendingTransactions(
                any(),
                any(),
                any(),
                any(),
                any()
            )
        )
            .willReturn(Mono.just(true))
        assertDoesNotThrow { pendingTransactionBatch.execute() }
    }

    @Test
    fun `Should get batch intertime executions correctly`() {
        val intertime =
            pendingTransactionBatch.getExecutionsInterleaveTimeMillis(cronExecutionString)
        assertEquals(10000, intertime)
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
        assertEquals(2, lower.until(upper, ChronoUnit.HOURS))
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
        given(pendingTransactionAnalyzer.getTotalTransactionCount(any(), any()))
            .willReturn(Mono.just(1L))
        given(
            pendingTransactionAnalyzer.searchPendingTransactions(
                any(),
                any(),
                any(),
                any(),
                any()
            )
        )
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
                batchMaxDurationSeconds = 1,
                maxTransactionPerPage = maxTransactionPerPage
            )
        val maxExecutionTime =
            pendingTransactionBatch.getMaxDuration(
                pendingTransactionBatch.getExecutionsInterleaveTimeMillis(
                    pendingTransactionBatch.chronExpression
                ),
                pendingTransactionBatch.batchMaxDurationSeconds
            )
        val pendingTransactionBatchTaskDuration = maxExecutionTime.multipliedBy(100)
        given(pendingTransactionAnalyzer.getTotalTransactionCount(any(), any()))
            .willReturn(Mono.just(1L))
        given(
            pendingTransactionAnalyzer.searchPendingTransactions(
                any(),
                any(),
                any(),
                any(),
                any()
            )
        )
            .willReturn(Mono.just(true).delayElement(pendingTransactionBatchTaskDuration))

        val duration =
            measureTime {
                val exception =
                    assertThrows<Exception> {
                        pendingTransactionBatch
                            .pendingTransactionAnalyzerPaginatedPipeline()
                            .block()
                    }
                assertTrue(exception.cause is TimeoutException)
            }
                .toJavaDuration()
        assertTrue(duration < pendingTransactionBatchTaskDuration)
        assertEquals(pendingTransactionBatch.batchMaxDurationSeconds, duration.seconds.toInt())
    }

    @ParameterizedTest
    @MethodSource("paginationTestArguments")
    fun `Should handle pagination correctly`(transactionsCount: Int, expectedPages: Int) {
        // assertions
        given(pendingTransactionAnalyzer.getTotalTransactionCount(any(), any()))
            .willReturn(Mono.just(transactionsCount.toLong()))
        given(
            pendingTransactionAnalyzer.searchPendingTransactions(
                any(),
                any(),
                any(),
                any(),
                any()
            )
        )
            .willReturn(Mono.just(true))
        assertDoesNotThrow { pendingTransactionBatch.execute() }
        verify(pendingTransactionAnalyzer, times(expectedPages)).searchPendingTransactions(
            any(),
            any(),
            any(),
            any(),
            any()
        )
    }

    @Test
    fun `Should not perform any computation for no transaction found`() {
        // assertions
        given(pendingTransactionAnalyzer.getTotalTransactionCount(any(), any()))
            .willReturn(Mono.just(0L))
        assertDoesNotThrow { pendingTransactionBatch.execute() }
        verify(pendingTransactionAnalyzer, times(0)).searchPendingTransactions(
            any(),
            any(),
            any(),
            any(),
            any()
        )
    }

    companion object {


        private const val maxTransactionPerPage = 5

        @JvmStatic
        private fun paginationTestArguments() =
            Stream.of(
                Arguments.of(maxTransactionPerPage * 2, 2),
                Arguments.of(maxTransactionPerPage * 2 + 1, 3),
                Arguments.of(maxTransactionPerPage * 2 - 1, 2),
                Arguments.of(maxTransactionPerPage - 1, 1)
            )
    }
}
