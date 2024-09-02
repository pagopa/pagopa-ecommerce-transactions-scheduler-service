package it.pagopa.ecommerce.transactions.scheduler.deadletter

import com.azure.spring.messaging.checkpoint.Checkpointer
import it.pagopa.ecommerce.commons.documents.v2.TransactionClosureData
import it.pagopa.ecommerce.commons.documents.v2.TransactionEvent
import it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptData
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils
import it.pagopa.ecommerce.transactions.scheduler.repositories.TransactionsEventStoreRepository
import java.time.ZonedDateTime
import java.util.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class TransactionInfoBuilderTest {

    private val transactionEventRepository: TransactionsEventStoreRepository<Any> = mock()
    private val checkPointer: Checkpointer = mock()
    private val transactionInfoBuilder =
        TransactionInfoBuilder(transactionsEventStoreRepository = transactionEventRepository)

    @Test
    fun `Should process correctly the event into a transaction info`() {

        val transactionView =
            TransactionTestUtils.transactionDocument(
                TransactionStatusDto.NOTIFIED_OK,
                ZonedDateTime.now()
            )

        val transactionUserReceiptData =
            TransactionTestUtils.transactionUserReceiptData(TransactionUserReceiptData.Outcome.OK)
        val transactionActivatedEvent = TransactionTestUtils.transactionActivateEvent()
        val authorizationRequestedEvent =
            TransactionTestUtils.transactionAuthorizationRequestedEvent()
        val closureSentEvent =
            TransactionTestUtils.transactionClosedEvent(TransactionClosureData.Outcome.KO)
        val addUserReceiptEvent =
            TransactionTestUtils.transactionUserReceiptRequestedEvent(transactionUserReceiptData)
        val userReceiptAddErrorEvent =
            TransactionTestUtils.transactionUserReceiptAddErrorEvent(addUserReceiptEvent.data)
        val userReceiptAddedEvent =
            TransactionTestUtils.transactionUserReceiptAddedEvent(userReceiptAddErrorEvent.data)
        val events =
            listOf(
                transactionActivatedEvent,
                authorizationRequestedEvent,
                closureSentEvent,
                addUserReceiptEvent,
                userReceiptAddErrorEvent,
                userReceiptAddedEvent
            )
                as List<TransactionEvent<Any>>

        given(checkPointer.success()).willReturn(Mono.empty())
        given(
                transactionEventRepository.findByTransactionIdOrderByCreationDateAsc(
                    transactionView.transactionId
                )
            )
            .willReturn(Flux.fromIterable(events))

        val baseTransaction = TransactionTestUtils.reduceEvents(*events.toTypedArray())

        val expected = baseTransactionToTransactionInfoDto(baseTransaction)

        StepVerifier.create(
                transactionInfoBuilder.getTransactionInfoByTransactionId(
                    transactionView.transactionId
                )
            )
            .expectNext(expected)
            .verifyComplete()
    }
}
