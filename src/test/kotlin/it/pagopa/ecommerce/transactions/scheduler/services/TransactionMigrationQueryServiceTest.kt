import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent
import it.pagopa.ecommerce.transactions.scheduler.configurations.QuerySettings
import it.pagopa.ecommerce.transactions.scheduler.configurations.TransactionMigrationQueryServiceConfig
import it.pagopa.ecommerce.transactions.scheduler.repositories.ecommerce.TransactionsEventStoreRepository
import it.pagopa.ecommerce.transactions.scheduler.repositories.ecommerce.TransactionsViewRepository
import it.pagopa.ecommerce.transactions.scheduler.services.TransactionMigrationQueryService
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import reactor.core.publisher.Flux
import reactor.test.StepVerifier

@ExtendWith(MockitoExtension::class)
class TransactionMigrationQueryServiceTest {
    @Mock private lateinit var transactionsEventStoreRepository: TransactionsEventStoreRepository<*>
    @Mock private lateinit var transactionViewRepository: TransactionsViewRepository
    @Mock
    private lateinit var transactionMigrationQueryServiceConfig:
        TransactionMigrationQueryServiceConfig
    @Mock private lateinit var eventstoreQuerySettings: QuerySettings
    @InjectMocks
    private lateinit var transactionMigrationQueryService: TransactionMigrationQueryService

    private val dateCaptor: KArgumentCaptor<LocalDate> = argumentCaptor()
    private val pageableCaptor: KArgumentCaptor<Pageable> = argumentCaptor()

    private val cutoffMonths = 9
    private val maxResults = 100

    @BeforeEach
    fun setup() {
        whenever(transactionMigrationQueryServiceConfig.eventstore)
            .thenReturn(eventstoreQuerySettings)
        whenever(eventstoreQuerySettings.cutoffMonthOffset).thenReturn(cutoffMonths)
        whenever(eventstoreQuerySettings.maxResults).thenReturn(maxResults)
    }

    @Test
    fun `should find eligible events correctly`() {
        // ARRANGE
        val mockEvent: BaseTransactionEvent<*> = mock(BaseTransactionEvent::class.java)
        val mockFlux: Flux<BaseTransactionEvent<*>> = Flux.just(mockEvent)

        val expectedCutoffDate = LocalDate.now().minusMonths(cutoffMonths.toLong())
        val expectedPageable: Pageable = PageRequest.of(0, maxResults)

        whenever(
                transactionsEventStoreRepository.findByTtlIsNullAndCreationDateLessThan(
                    any(),
                    any()
                )
            )
            .thenReturn(mockFlux)

        // ACT
        val resultFlux = transactionMigrationQueryService.findEligibleEvents()

        // ASSERT
        StepVerifier.create(resultFlux).expectNext(mockEvent).verifyComplete()

        verify(transactionsEventStoreRepository, times(1))
            .findByTtlIsNullAndCreationDateLessThan(dateCaptor.capture(), pageableCaptor.capture())

        assertEquals(expectedCutoffDate, dateCaptor.firstValue)
        assertEquals(expectedPageable, pageableCaptor.firstValue)
    }

    @Test
    fun `should return empty Flux when repository finds no events`() {
        // ARRANGE
        whenever(
                transactionsEventStoreRepository.findByTtlIsNullAndCreationDateLessThan(
                    any(),
                    any()
                )
            )
            .thenReturn(Flux.empty())

        // ACT
        val resultFlux = transactionMigrationQueryService.findEligibleEvents()

        // ASSERT
        StepVerifier.create(resultFlux).verifyComplete()

        verify(transactionsEventStoreRepository, times(1))
            .findByTtlIsNullAndCreationDateLessThan(any(), any())
    }
}
