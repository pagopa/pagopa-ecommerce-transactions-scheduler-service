package it.pagopa.ecommerce.transactions.scheduler.deadletter

import com.azure.spring.messaging.checkpoint.Checkpointer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.opentelemetry.api.trace.Tracer
import it.pagopa.ecommerce.commons.client.NpgClient
import it.pagopa.ecommerce.commons.documents.v2.*
import it.pagopa.ecommerce.commons.documents.v2.activation.NpgTransactionGatewayActivationData
import it.pagopa.ecommerce.commons.documents.v2.authorization.NpgTransactionGatewayAuthorizationRequestedData
import it.pagopa.ecommerce.commons.documents.v2.info.NpgTransactionInfoDetailsData
import it.pagopa.ecommerce.commons.documents.v2.info.RedirectTransactionInfoDetailsData
import it.pagopa.ecommerce.commons.documents.v2.info.TransactionInfoDetailsData
import it.pagopa.ecommerce.commons.documents.v2.refund.NpgGatewayRefundData
import it.pagopa.ecommerce.commons.exceptions.NpgResponseException
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OperationResultDto
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OrderResponseDto
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
import it.pagopa.ecommerce.commons.utils.NpgApiKeyConfiguration
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils.AUTHORIZATION_REQUEST_ID
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils.TRANSACTION_ID
import it.pagopa.ecommerce.transactions.scheduler.client.PaymentGatewayClient
import it.pagopa.ecommerce.transactions.scheduler.repositories.TransactionsEventStoreRepository
import it.pagopa.ecommerce.transactions.scheduler.services.TransactionInfoService
import it.pagopa.ecommerce.transactions.scheduler.utils.OrderResponseBuilder.Companion.buildOrderResponseDtoForNgpOrderAuthorized
import it.pagopa.ecommerce.transactions.scheduler.utils.OrderResponseBuilder.Companion.buildOrderResponseDtoForNgpOrderNotAuthorized
import it.pagopa.ecommerce.transactions.scheduler.utils.OrderResponseBuilder.Companion.buildOrderResponseDtoForNpgOrderRefunded
import it.pagopa.ecommerce.transactions.scheduler.utils.OrderResponseBuilder.Companion.buildOrderResponseDtoForNpgOrderRefundedFaulty
import it.pagopa.ecommerce.transactions.scheduler.utils.OrderResponseBuilder.Companion.buildOrderResponseDtoForNpgOrderVoid
import it.pagopa.ecommerce.transactions.scheduler.utils.OrderResponseBuilder.Companion.buildOrderResponseDtoInvalidOrder
import it.pagopa.ecommerce.transactions.scheduler.utils.OrderResponseBuilder.Companion.buildOrderResponseDtoNullOperation
import java.time.ZonedDateTime
import java.util.*
import java.util.stream.Stream
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.kotlin.*
import org.springframework.http.HttpStatus
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class TransactionInfoServiceTest {

    companion object {
        private val correlationId = UUID.randomUUID()

        @JvmStatic
        private fun retriverArgsTest() =
            Stream.of(
                Arguments.of(
                    TransactionAuthorizationRequestData.PaymentGateway.NPG,
                    buildOrderResponseDtoForNgpOrderNotAuthorized(),
                    NpgTransactionInfoDetailsData(
                        OperationResultDto.FAILED,
                        "operationId",
                        correlationId
                    )
                ),
                Arguments.of(
                    TransactionAuthorizationRequestData.PaymentGateway.NPG,
                    buildOrderResponseDtoForNgpOrderAuthorized(),
                    NpgTransactionInfoDetailsData(
                        OperationResultDto.EXECUTED,
                        "operationId",
                        correlationId
                    )
                ),
                Arguments.of(
                    TransactionAuthorizationRequestData.PaymentGateway.NPG,
                    buildOrderResponseDtoForNpgOrderRefunded(),
                    NpgTransactionInfoDetailsData(
                        OperationResultDto.VOIDED,
                        "operationId",
                        correlationId
                    )
                ),
                Arguments.of(
                    TransactionAuthorizationRequestData.PaymentGateway.REDIRECT,
                    buildOrderResponseDtoForNpgOrderRefunded(),
                    RedirectTransactionInfoDetailsData("")
                ),
            )
    }

    private val transactionEventRepository: TransactionsEventStoreRepository<Any> = mock()
    private val npgApiKeyConfiguration =
        NpgApiKeyConfiguration.Builder()
            .setDefaultApiKey("defaultApiKey")
            .withMethodPspMapping(
                NpgClient.PaymentMethod.CARDS,
                """
      {
        "pspId": "pspKey1"
      }
    """,
                setOf("pspId"),
                jacksonObjectMapper()
            )
            .build()
    private val paymentServiceApi =
        PaymentGatewayClient()
            .npgApiWebClient(
                npgClientUrl = "http://localhost:8080",
                npgWebClientConnectionTimeout = 10000,
                npgWebClientReadTimeout = 10000
            )
    private val tracer: Tracer = mock()
    private val npgClient: NpgClient = spy(NpgClient(paymentServiceApi, tracer, ObjectMapper()))

    private val checkPointer: Checkpointer = mock()
    private val transactionInfoService =
        TransactionInfoService(
            transactionsEventStoreRepository = transactionEventRepository,
            npgClient = npgClient,
            npgApiKeyConfiguration = npgApiKeyConfiguration
        )

    @BeforeEach fun init() {}

    @Test
    fun `Should process correctly the event into a transaction info`() {

        val transactionView =
            TransactionTestUtils.transactionDocument(
                TransactionStatusDto.NOTIFIED_OK,
                ZonedDateTime.now()
            )

        val transactionUserReceiptData =
            TransactionTestUtils.transactionUserReceiptData(TransactionUserReceiptData.Outcome.OK)
        val transactionActivatedEvent =
            TransactionTestUtils.transactionActivateEvent(
                NpgTransactionGatewayActivationData("orderId", correlationId.toString())
            )
        val authorizationRequestedEvent =
            TransactionTestUtils.transactionAuthorizationRequestedEvent(
                TransactionAuthorizationRequestData.PaymentGateway.NPG
            )
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

        val expected =
            transactionInfoService.baseTransactionToTransactionInfoDto(
                baseTransaction,
                NpgTransactionInfoDetailsData(
                    OperationResultDto.VOIDED,
                    "operationId",
                    correlationId
                )
            )
        given(npgClient.getOrder(any(), any(), any()))
            .willReturn(buildOrderResponseDtoForNpgOrderRefunded())
        StepVerifier.create(
                transactionInfoService.getTransactionInfoByTransactionId(
                    transactionView.transactionId
                )
            )
            .expectNext(expected)
            .verifyComplete()
    }

    @Test
    fun `Should process the events when they contain refund requests`() {
        val orderId = "orderId"
        val refundOperationId = "refundOperationId"

        val transactionView =
            TransactionTestUtils.transactionDocument(
                TransactionStatusDto.REFUNDED,
                ZonedDateTime.now()
            )
        val transactionActivatedEvent =
            TransactionTestUtils.transactionActivateEvent(
                NpgTransactionGatewayActivationData(orderId, correlationId.toString())
            )
        val transactionAuthorizationRequestedEvent =
            TransactionTestUtils.transactionAuthorizationRequestedEvent(
                TransactionAuthorizationRequestData.PaymentGateway.NPG
            )

        val transactionExpiredEvent =
            TransactionTestUtils.transactionExpiredEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent
                )
            )
        val transactionRefundRequestedEvent =
            TransactionTestUtils.transactionRefundRequestedEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent,
                    transactionExpiredEvent
                ),
                null // N.B.: Is null when getting error while retrieving authorization data from
                // gateway
                )
        val transactionRefundErrorEvent =
            TransactionTestUtils.transactionRefundErrorEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent,
                    transactionExpiredEvent,
                    transactionRefundRequestedEvent
                )
            )
        val transactionRefundRetryEvent =
            TransactionTestUtils.transactionRefundRetriedEvent(
                1,
                TransactionTestUtils.npgTransactionGatewayAuthorizationData(
                    OperationResultDto.EXECUTED
                )
            )
        val transactionRefundedEvent =
            TransactionTestUtils.transactionRefundedEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent,
                    transactionExpiredEvent,
                    transactionRefundRequestedEvent,
                    transactionRefundErrorEvent,
                    transactionRefundRetryEvent
                ),
                NpgGatewayRefundData(refundOperationId)
            )

        val events =
            listOf(
                transactionActivatedEvent,
                transactionAuthorizationRequestedEvent,
                transactionExpiredEvent,
                transactionRefundRequestedEvent,
                transactionRefundErrorEvent,
                transactionRefundRetryEvent,
                transactionRefundedEvent
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

        val expected =
            transactionInfoService.baseTransactionToTransactionInfoDto(
                baseTransaction,
                NpgTransactionInfoDetailsData(
                    OperationResultDto.VOIDED,
                    "operationId",
                    Companion.correlationId
                )
            )
        given(npgClient.getOrder(any(), any(), any()))
            .willReturn(buildOrderResponseDtoForNpgOrderRefunded())
        StepVerifier.create(
                transactionInfoService.getTransactionInfoByTransactionId(
                    transactionView.transactionId
                )
            )
            .expectNext(expected)
            .verifyComplete()
    }

    @ParameterizedTest
    @MethodSource("retriverArgsTest")
    fun `Should retrieve gateway info and put it into TransactionInfo object`(
        gateway: TransactionAuthorizationRequestData.PaymentGateway,
        orderResponseMock: Mono<OrderResponseDto>,
        detailsExpected: TransactionInfoDetailsData
    ) {
        val orderId = "orderId"
        val refundOperationId = "refundOperationId"

        val transactionView =
            TransactionTestUtils.transactionDocument(
                TransactionStatusDto.REFUNDED,
                ZonedDateTime.now()
            )
        val transactionActivatedEvent =
            TransactionTestUtils.transactionActivateEvent(
                NpgTransactionGatewayActivationData(orderId, correlationId.toString())
            )
        val transactionAuthorizationRequestedEvent =
            TransactionTestUtils.transactionAuthorizationRequestedEvent(gateway)

        val transactionExpiredEvent =
            TransactionTestUtils.transactionExpiredEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent
                )
            )
        val transactionRefundRequestedEvent =
            TransactionTestUtils.transactionRefundRequestedEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent,
                    transactionExpiredEvent
                ),
                null // N.B.: Is null when getting error while retrieving authorization data from
                // gateway
                )
        val transactionRefundErrorEvent =
            TransactionTestUtils.transactionRefundErrorEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent,
                    transactionExpiredEvent,
                    transactionRefundRequestedEvent
                )
            )
        val transactionRefundRetryEvent =
            TransactionTestUtils.transactionRefundRetriedEvent(
                1,
                TransactionTestUtils.npgTransactionGatewayAuthorizationData(
                    OperationResultDto.EXECUTED
                )
            )
        val transactionRefundedEvent =
            TransactionTestUtils.transactionRefundedEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent,
                    transactionExpiredEvent,
                    transactionRefundRequestedEvent,
                    transactionRefundErrorEvent,
                    transactionRefundRetryEvent
                ),
                NpgGatewayRefundData(refundOperationId)
            )

        val events =
            listOf(
                transactionActivatedEvent,
                transactionAuthorizationRequestedEvent,
                transactionExpiredEvent,
                transactionRefundRequestedEvent,
                transactionRefundErrorEvent,
                transactionRefundRetryEvent,
                transactionRefundedEvent
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

        given(npgClient.getOrder(any(), any(), any())).willReturn(orderResponseMock)
        StepVerifier.create(transactionInfoService.getTransactionInfoDetails(baseTransaction))
            .expectNext(detailsExpected)
            .verifyComplete()
    }

    @Test
    fun `Should throw error for gateway param null`() {
        given(npgClient.getOrder(any(), any(), any()))
            .willReturn(buildOrderResponseDtoForNpgOrderRefunded())
        val orderId = "orderId"
        val refundOperationId = "refundOperationId"

        val transactionView =
            TransactionTestUtils.transactionDocument(
                TransactionStatusDto.REFUNDED,
                ZonedDateTime.now()
            )
        val transactionActivatedEvent =
            TransactionTestUtils.transactionActivateEvent(
                NpgTransactionGatewayActivationData(orderId, correlationId.toString())
            )
        val transactionAuthorizationRequestedEvent =
            TransactionTestUtils.transactionAuthorizationRequestedEvent()

        val transactionExpiredEvent =
            TransactionTestUtils.transactionExpiredEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent
                )
            )
        val transactionRefundRequestedEvent =
            TransactionTestUtils.transactionRefundRequestedEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent,
                    transactionExpiredEvent
                ),
                null // N.B.: Is null when getting error while retrieving authorization data from
                // gateway
                )
        val transactionRefundErrorEvent =
            TransactionTestUtils.transactionRefundErrorEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent,
                    transactionExpiredEvent,
                    transactionRefundRequestedEvent
                )
            )
        val transactionRefundRetryEvent =
            TransactionTestUtils.transactionRefundRetriedEvent(
                1,
                TransactionTestUtils.npgTransactionGatewayAuthorizationData(
                    OperationResultDto.EXECUTED
                )
            )
        val transactionRefundedEvent =
            TransactionTestUtils.transactionRefundedEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent,
                    transactionExpiredEvent,
                    transactionRefundRequestedEvent,
                    transactionRefundErrorEvent,
                    transactionRefundRetryEvent
                ),
                NpgGatewayRefundData(refundOperationId)
            )

        val events =
            listOf(
                transactionActivatedEvent,
                transactionAuthorizationRequestedEvent,
                transactionExpiredEvent,
                transactionRefundRequestedEvent,
                transactionRefundErrorEvent,
                transactionRefundRetryEvent,
                transactionRefundedEvent
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
        StepVerifier.create(transactionInfoService.getTransactionInfoDetails(baseTransaction))
            .expectError(NullPointerException::class.java)
            .verify()
    }

    @Test
    fun `Should throw error for invalid order`() {
        given(npgClient.getOrder(any(), any(), any()))
            .willReturn(buildOrderResponseDtoInvalidOrder())
        val orderId = "orderId"
        val refundOperationId = "refundOperationId"

        val transactionView =
            TransactionTestUtils.transactionDocument(
                TransactionStatusDto.REFUNDED,
                ZonedDateTime.now()
            )
        val transactionActivatedEvent =
            TransactionTestUtils.transactionActivateEvent(
                NpgTransactionGatewayActivationData(orderId, correlationId.toString())
            )
        val transactionAuthorizationRequestedEvent =
            TransactionTestUtils.transactionAuthorizationRequestedEvent(
                TransactionAuthorizationRequestData.PaymentGateway.NPG
            )

        val transactionExpiredEvent =
            TransactionTestUtils.transactionExpiredEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent
                )
            )
        val transactionRefundRequestedEvent =
            TransactionTestUtils.transactionRefundRequestedEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent,
                    transactionExpiredEvent
                ),
                null // N.B.: Is null when getting error while retrieving authorization data from
                // gateway
                )
        val transactionRefundErrorEvent =
            TransactionTestUtils.transactionRefundErrorEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent,
                    transactionExpiredEvent,
                    transactionRefundRequestedEvent
                )
            )
        val transactionRefundRetryEvent =
            TransactionTestUtils.transactionRefundRetriedEvent(
                1,
                TransactionTestUtils.npgTransactionGatewayAuthorizationData(
                    OperationResultDto.EXECUTED
                )
            )
        val transactionRefundedEvent =
            TransactionTestUtils.transactionRefundedEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent,
                    transactionExpiredEvent,
                    transactionRefundRequestedEvent,
                    transactionRefundErrorEvent,
                    transactionRefundRetryEvent
                ),
                NpgGatewayRefundData(refundOperationId)
            )

        val events =
            listOf(
                transactionActivatedEvent,
                transactionAuthorizationRequestedEvent,
                transactionExpiredEvent,
                transactionRefundRequestedEvent,
                transactionRefundErrorEvent,
                transactionRefundRetryEvent,
                transactionRefundedEvent
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

        StepVerifier.create(transactionInfoService.getTransactionInfoDetails(baseTransaction))
            .expectErrorMatches {
                it is RuntimeException &&
                    it.message ==
                        "Invalid operation retrived from gateway for transaction: ${TRANSACTION_ID}"
            }
            .verify()
    }

    @Test
    fun `Should throw error for invalid order refound`() {

        given(npgClient.getOrder(any(), any(), any()))
            .willReturn(buildOrderResponseDtoForNpgOrderRefundedFaulty())
        val orderId = "orderId"
        val refundOperationId = "refundOperationId"

        val transactionView =
            TransactionTestUtils.transactionDocument(
                TransactionStatusDto.REFUNDED,
                ZonedDateTime.now()
            )
        val transactionActivatedEvent =
            TransactionTestUtils.transactionActivateEvent(
                NpgTransactionGatewayActivationData(orderId, correlationId.toString())
            )
        val transactionAuthorizationRequestedEvent =
            TransactionTestUtils.transactionAuthorizationRequestedEvent(
                TransactionAuthorizationRequestData.PaymentGateway.NPG
            )

        val transactionExpiredEvent =
            TransactionTestUtils.transactionExpiredEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent
                )
            )
        val transactionRefundRequestedEvent =
            TransactionTestUtils.transactionRefundRequestedEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent,
                    transactionExpiredEvent
                ),
                null // N.B.: Is null when getting error while retrieving authorization data from
                // gateway
                )
        val transactionRefundErrorEvent =
            TransactionTestUtils.transactionRefundErrorEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent,
                    transactionExpiredEvent,
                    transactionRefundRequestedEvent
                )
            )
        val transactionRefundRetryEvent =
            TransactionTestUtils.transactionRefundRetriedEvent(
                1,
                TransactionTestUtils.npgTransactionGatewayAuthorizationData(
                    OperationResultDto.EXECUTED
                )
            )
        val transactionRefundedEvent =
            TransactionTestUtils.transactionRefundedEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent,
                    transactionExpiredEvent,
                    transactionRefundRequestedEvent,
                    transactionRefundErrorEvent,
                    transactionRefundRetryEvent
                ),
                NpgGatewayRefundData(refundOperationId)
            )

        val events =
            listOf(
                transactionActivatedEvent,
                transactionAuthorizationRequestedEvent,
                transactionExpiredEvent,
                transactionRefundRequestedEvent,
                transactionRefundErrorEvent,
                transactionRefundRetryEvent,
                transactionRefundedEvent
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

        StepVerifier.create(transactionInfoService.getTransactionInfoDetails(baseTransaction))
            .expectErrorMatches {
                it is RuntimeException &&
                    it.message ==
                        "Invalid operation retrived from gateway for transaction: ${TRANSACTION_ID}"
            }
            .verify()
    }

    @Test
    fun `Should throw error for invalid order authorized`() {
        given(npgClient.getOrder(any(), any(), any()))
            .willReturn(buildOrderResponseDtoForNpgOrderVoid())
        val orderId = "orderId"
        val refundOperationId = "refundOperationId"

        val transactionView =
            TransactionTestUtils.transactionDocument(
                TransactionStatusDto.REFUNDED,
                ZonedDateTime.now()
            )
        val transactionActivatedEvent =
            TransactionTestUtils.transactionActivateEvent(
                NpgTransactionGatewayActivationData(orderId, correlationId.toString())
            )
        val transactionAuthorizationRequestedEvent =
            TransactionTestUtils.transactionAuthorizationRequestedEvent(
                TransactionAuthorizationRequestData.PaymentGateway.NPG
            )

        val transactionExpiredEvent =
            TransactionTestUtils.transactionExpiredEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent
                )
            )
        val transactionRefundRequestedEvent =
            TransactionTestUtils.transactionRefundRequestedEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent,
                    transactionExpiredEvent
                ),
                null // N.B.: Is null when getting error while retrieving authorization data from
                // gateway
                )
        val transactionRefundErrorEvent =
            TransactionTestUtils.transactionRefundErrorEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent,
                    transactionExpiredEvent,
                    transactionRefundRequestedEvent
                )
            )
        val transactionRefundRetryEvent =
            TransactionTestUtils.transactionRefundRetriedEvent(
                1,
                TransactionTestUtils.npgTransactionGatewayAuthorizationData(
                    OperationResultDto.EXECUTED
                )
            )
        val transactionRefundedEvent =
            TransactionTestUtils.transactionRefundedEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent,
                    transactionExpiredEvent,
                    transactionRefundRequestedEvent,
                    transactionRefundErrorEvent,
                    transactionRefundRetryEvent
                ),
                NpgGatewayRefundData(refundOperationId)
            )

        val events =
            listOf(
                transactionActivatedEvent,
                transactionAuthorizationRequestedEvent,
                transactionExpiredEvent,
                transactionRefundRequestedEvent,
                transactionRefundErrorEvent,
                transactionRefundRetryEvent,
                transactionRefundedEvent
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

        StepVerifier.create(transactionInfoService.getTransactionInfoDetails(baseTransaction))
            .expectErrorMatches {
                it is RuntimeException &&
                    it.message ==
                        "Invalid operation retrived from gateway for transaction: ${TRANSACTION_ID}"
            }
            .verify()
    }

    @Test
    fun `Should throw error for null operations`() {
        given(npgClient.getOrder(any(), any(), any()))
            .willReturn(buildOrderResponseDtoNullOperation())
        val orderId = "orderId"
        val refundOperationId = "refundOperationId"

        val transactionView =
            TransactionTestUtils.transactionDocument(
                TransactionStatusDto.REFUNDED,
                ZonedDateTime.now()
            )
        val transactionActivatedEvent =
            TransactionTestUtils.transactionActivateEvent(
                NpgTransactionGatewayActivationData(orderId, correlationId.toString())
            )
        val transactionAuthorizationRequestedEvent =
            TransactionTestUtils.transactionAuthorizationRequestedEvent(
                TransactionAuthorizationRequestData.PaymentGateway.NPG
            )

        val transactionExpiredEvent =
            TransactionTestUtils.transactionExpiredEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent
                )
            )
        val transactionRefundRequestedEvent =
            TransactionTestUtils.transactionRefundRequestedEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent,
                    transactionExpiredEvent
                ),
                null // N.B.: Is null when getting error while retrieving authorization data from
                // gateway
                )
        val transactionRefundErrorEvent =
            TransactionTestUtils.transactionRefundErrorEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent,
                    transactionExpiredEvent,
                    transactionRefundRequestedEvent
                )
            )
        val transactionRefundRetryEvent =
            TransactionTestUtils.transactionRefundRetriedEvent(
                1,
                TransactionTestUtils.npgTransactionGatewayAuthorizationData(
                    OperationResultDto.EXECUTED
                )
            )
        val transactionRefundedEvent =
            TransactionTestUtils.transactionRefundedEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent,
                    transactionExpiredEvent,
                    transactionRefundRequestedEvent,
                    transactionRefundErrorEvent,
                    transactionRefundRetryEvent
                ),
                NpgGatewayRefundData(refundOperationId)
            )

        val events =
            listOf(
                transactionActivatedEvent,
                transactionAuthorizationRequestedEvent,
                transactionExpiredEvent,
                transactionRefundRequestedEvent,
                transactionRefundErrorEvent,
                transactionRefundRetryEvent,
                transactionRefundedEvent
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

        StepVerifier.create(transactionInfoService.getTransactionInfoDetails(baseTransaction))
            .expectErrorMatches {
                it is RuntimeException &&
                    it.message ==
                        "No operations found for transaction with id: ${TransactionTestUtils.TRANSACTION_ID}"
            }
            .verify()
    }

    @Test
    fun `Should throw error for wrong pspId`() {
        given(npgClient.getOrder(any(), any(), any()))
            .willReturn(buildOrderResponseDtoForNpgOrderRefunded())
        val orderId = "orderId"
        val refundOperationId = "refundOperationId"

        val transactionView =
            TransactionTestUtils.transactionDocument(
                TransactionStatusDto.REFUNDED,
                ZonedDateTime.now()
            )
        val transactionActivatedEvent =
            TransactionTestUtils.transactionActivateEvent(
                NpgTransactionGatewayActivationData(orderId, correlationId.toString())
            )
        val transactionAuthorizationRequestedEvent =
            TransactionAuthorizationRequestedEvent(
                TRANSACTION_ID,
                TransactionAuthorizationRequestData(
                    100,
                    10,
                    "paymentInstrumentId",
                    "pspId2",
                    "CP",
                    "brokerName",
                    "pspChannelCode",
                    "CARDS",
                    "pspBusinessName",
                    false,
                    AUTHORIZATION_REQUEST_ID,
                    TransactionAuthorizationRequestData.PaymentGateway.NPG,
                    "paymentMethodDescription",
                    NpgTransactionGatewayAuthorizationRequestedData()
                )
            )

        val transactionExpiredEvent =
            TransactionTestUtils.transactionExpiredEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent
                )
            )
        val transactionRefundRequestedEvent =
            TransactionTestUtils.transactionRefundRequestedEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent,
                    transactionExpiredEvent
                ),
                null // N.B.: Is null when getting error while retrieving authorization data from
                // gateway
                )
        val transactionRefundErrorEvent =
            TransactionTestUtils.transactionRefundErrorEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent,
                    transactionExpiredEvent,
                    transactionRefundRequestedEvent
                )
            )
        val transactionRefundRetryEvent =
            TransactionTestUtils.transactionRefundRetriedEvent(
                1,
                TransactionTestUtils.npgTransactionGatewayAuthorizationData(
                    OperationResultDto.EXECUTED
                )
            )
        val transactionRefundedEvent =
            TransactionTestUtils.transactionRefundedEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent,
                    transactionExpiredEvent,
                    transactionRefundRequestedEvent,
                    transactionRefundErrorEvent,
                    transactionRefundRetryEvent
                ),
                NpgGatewayRefundData(refundOperationId)
            )

        val events =
            listOf(
                transactionActivatedEvent,
                transactionAuthorizationRequestedEvent,
                transactionExpiredEvent,
                transactionRefundRequestedEvent,
                transactionRefundErrorEvent,
                transactionRefundRetryEvent,
                transactionRefundedEvent
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

        StepVerifier.create(transactionInfoService.getTransactionInfoDetails(baseTransaction))
            .expectErrorMatches {
                it is RuntimeException &&
                    it.message ==
                        "Cannot retrieve api key for payment method: [CARDS]. Cause: Requested API key for PSP: [pspId2]. Available PSPs: [pspId]"
            }
            .verify()
    }

    @Test
    fun `Should throw error when getOrder call fails - is5xxServerError is true`() {

        given(npgClient.getOrder(any(), any(), any()))
            .willReturn(
                Mono.error(
                    NpgResponseException(
                        "error",
                        emptyList(),
                        Optional.of(HttpStatus.INTERNAL_SERVER_ERROR),
                        RuntimeException()
                    )
                )
            )
        val orderId = "orderId"
        val refundOperationId = "refundOperationId"

        val transactionView =
            TransactionTestUtils.transactionDocument(
                TransactionStatusDto.REFUNDED,
                ZonedDateTime.now()
            )
        val transactionActivatedEvent =
            TransactionTestUtils.transactionActivateEvent(
                NpgTransactionGatewayActivationData(orderId, correlationId.toString())
            )
        val transactionAuthorizationRequestedEvent =
            TransactionTestUtils.transactionAuthorizationRequestedEvent(
                TransactionAuthorizationRequestData.PaymentGateway.NPG
            )

        val transactionExpiredEvent =
            TransactionTestUtils.transactionExpiredEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent
                )
            )
        val transactionRefundRequestedEvent =
            TransactionTestUtils.transactionRefundRequestedEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent,
                    transactionExpiredEvent
                ),
                null // N.B.: Is null when getting error while retrieving authorization data from
                // gateway
                )
        val transactionRefundErrorEvent =
            TransactionTestUtils.transactionRefundErrorEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent,
                    transactionExpiredEvent,
                    transactionRefundRequestedEvent
                )
            )
        val transactionRefundRetryEvent =
            TransactionTestUtils.transactionRefundRetriedEvent(
                1,
                TransactionTestUtils.npgTransactionGatewayAuthorizationData(
                    OperationResultDto.EXECUTED
                )
            )
        val transactionRefundedEvent =
            TransactionTestUtils.transactionRefundedEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent,
                    transactionExpiredEvent,
                    transactionRefundRequestedEvent,
                    transactionRefundErrorEvent,
                    transactionRefundRetryEvent
                ),
                NpgGatewayRefundData(refundOperationId)
            )

        val events =
            listOf(
                transactionActivatedEvent,
                transactionAuthorizationRequestedEvent,
                transactionExpiredEvent,
                transactionRefundRequestedEvent,
                transactionRefundErrorEvent,
                transactionRefundRetryEvent,
                transactionRefundedEvent
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

        StepVerifier.create(transactionInfoService.getTransactionInfoDetails(baseTransaction))
            .expectErrorMatches {
                it is RuntimeException &&
                    it.message ==
                        "Bad gateway : Received HTTP error code from NPG: 500 INTERNAL_SERVER_ERROR"
            }
            .verify()
    }

    @Test
    fun `Should throw error when getOrder call fails - is5xxServerError is false`() {

        given(npgClient.getOrder(any(), any(), any()))
            .willReturn(
                Mono.error(
                    NpgResponseException(
                        "error",
                        emptyList(),
                        Optional.of(HttpStatus.BAD_REQUEST),
                        RuntimeException()
                    )
                )
            )
        val orderId = "orderId"
        val refundOperationId = "refundOperationId"

        val transactionView =
            TransactionTestUtils.transactionDocument(
                TransactionStatusDto.REFUNDED,
                ZonedDateTime.now()
            )
        val transactionActivatedEvent =
            TransactionTestUtils.transactionActivateEvent(
                NpgTransactionGatewayActivationData(orderId, correlationId.toString())
            )
        val transactionAuthorizationRequestedEvent =
            TransactionTestUtils.transactionAuthorizationRequestedEvent(
                TransactionAuthorizationRequestData.PaymentGateway.NPG
            )

        val transactionExpiredEvent =
            TransactionTestUtils.transactionExpiredEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent
                )
            )
        val transactionRefundRequestedEvent =
            TransactionTestUtils.transactionRefundRequestedEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent,
                    transactionExpiredEvent
                ),
                null // N.B.: Is null when getting error while retrieving authorization data from
                // gateway
                )
        val transactionRefundErrorEvent =
            TransactionTestUtils.transactionRefundErrorEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent,
                    transactionExpiredEvent,
                    transactionRefundRequestedEvent
                )
            )
        val transactionRefundRetryEvent =
            TransactionTestUtils.transactionRefundRetriedEvent(
                1,
                TransactionTestUtils.npgTransactionGatewayAuthorizationData(
                    OperationResultDto.EXECUTED
                )
            )
        val transactionRefundedEvent =
            TransactionTestUtils.transactionRefundedEvent(
                TransactionTestUtils.reduceEvents(
                    transactionActivatedEvent,
                    transactionAuthorizationRequestedEvent,
                    transactionExpiredEvent,
                    transactionRefundRequestedEvent,
                    transactionRefundErrorEvent,
                    transactionRefundRetryEvent
                ),
                NpgGatewayRefundData(refundOperationId)
            )

        val events =
            listOf(
                transactionActivatedEvent,
                transactionAuthorizationRequestedEvent,
                transactionExpiredEvent,
                transactionRefundRequestedEvent,
                transactionRefundErrorEvent,
                transactionRefundRetryEvent,
                transactionRefundedEvent
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

        StepVerifier.create(transactionInfoService.getTransactionInfoDetails(baseTransaction))
            .expectErrorMatches {
                it is RuntimeException &&
                    it.message ==
                        "Transaction with id ${TransactionTestUtils.TRANSACTION_ID} npg state cannot be retrieved. Reason: Received HTTP error code from NPG: 400 BAD_REQUEST"
            }
            .verify()
    }
}
