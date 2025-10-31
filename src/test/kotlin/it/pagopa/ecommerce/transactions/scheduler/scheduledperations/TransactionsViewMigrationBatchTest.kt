package it.pagopa.ecommerce.transactions.scheduler.scheduledperations

import it.pagopa.ecommerce.transactions.scheduler.services.TransactionsViewMigrationOrchestrator
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

@ExtendWith(MockitoExtension::class)
class TransactionsViewMigrationBatchTest {
    @Mock
    private lateinit var transactionsViewMigrationOrchestrator:
        TransactionsViewMigrationOrchestrator
    @InjectMocks private lateinit var transactionsViewMigrationBatch: TransactionsViewMigrationBatch

    @Test
    fun `execute should call runMigration on orchestrator`() {
        // Act
        transactionsViewMigrationBatch.execute()

        // Assert
        verify(transactionsViewMigrationOrchestrator, times(1)).runMigration()
    }
}
