package it.pagopa.ecommerce.transactions.scheduler.timertriggers

import it.pagopa.ecommerce.transactions.scheduler.timertriggers.handlers.PendingTransactionBatch
import it.pagopa.ecommerce.transactions.scheduler.transactionanalyzer.PendingTransactionAnalyzer
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.springframework.test.context.TestPropertySource
import reactor.core.publisher.Mono
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

@ExtendWith(MockitoExtension::class)
@TestPropertySource(locations = ["classpath:application-tests.properties"])
class PendingTransactionBatchTests {

    @Mock
    private lateinit var pendingTransactionAnalyzer: PendingTransactionAnalyzer

    private lateinit var pendingTransactionBatch: PendingTransactionBatch

    private val cronExecutionString = "*/10 * * * * *"

    private val executionRateMultiplier = 2

    @BeforeEach
    fun init() {
        pendingTransactionBatch =
            PendingTransactionBatch(
                pendingTransactionAnalyzer,
                cronExecutionString,
                executionRateMultiplier
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
    fun `Should propagate processing exception`() {
        // assertions
        BDDMockito.given(pendingTransactionAnalyzer.searchPendingTransactions(any(), any(), any()))
            .willReturn(Mono.error(RuntimeException("Generic error")))
        val exception = assertThrows<RuntimeException> { pendingTransactionBatch.execute() }
        Assertions.assertEquals("Generic error", exception.message)
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
}
