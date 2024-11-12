package it.pagopa.ecommerce.transactions.scheduler.services

import com.azure.spring.messaging.checkpoint.Checkpointer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.opentelemetry.api.trace.Tracer
import it.pagopa.ecommerce.commons.client.NpgClient
import it.pagopa.ecommerce.commons.documents.v2.*
import it.pagopa.ecommerce.commons.documents.v2.activation.NpgTransactionGatewayActivationData
import it.pagopa.ecommerce.commons.documents.v2.authorization.NpgTransactionGatewayAuthorizationRequestedData
import it.pagopa.ecommerce.commons.documents.v2.deadletter.*
import it.pagopa.ecommerce.commons.exceptions.NpgApiKeyConfigurationException
import it.pagopa.ecommerce.commons.exceptions.NpgResponseException
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OperationResultDto
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OrderResponseDto
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
import it.pagopa.ecommerce.commons.utils.NpgApiKeyConfiguration
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils.*
import it.pagopa.ecommerce.transactions.scheduler.configurations.PaymentGatewayConfig
import it.pagopa.ecommerce.transactions.scheduler.exceptions.*
import it.pagopa.ecommerce.transactions.scheduler.repositories.TransactionsEventStoreRepository
import it.pagopa.ecommerce.transactions.scheduler.utils.TransactionInfoUtils
import it.pagopa.ecommerce.transactions.scheduler.utils.TransactionInfoUtils.Companion.buildOrderResponseDtoForNgpOrderAuthorized
import it.pagopa.ecommerce.transactions.scheduler.utils.TransactionInfoUtils.Companion.buildOrderResponseDtoForNgpOrderNotAuthorized
import it.pagopa.ecommerce.transactions.scheduler.utils.TransactionInfoUtils.Companion.buildOrderResponseDtoForNpgOrderRefunded
import it.pagopa.ecommerce.transactions.scheduler.utils.TransactionInfoUtils.Companion.buildOrderResponseDtoForNpgOrderRefundedFaulty
import it.pagopa.ecommerce.transactions.scheduler.utils.TransactionInfoUtils.Companion.buildOrderResponseDtoForNpgOrderVoid
import it.pagopa.ecommerce.transactions.scheduler.utils.TransactionInfoUtils.Companion.buildOrderResponseDtoInvalidOrder
import it.pagopa.ecommerce.transactions.scheduler.utils.TransactionInfoUtils.Companion.buildOrderResponseDtoNullOperation
import java.time.ZonedDateTime
import java.util.*
import java.util.stream.Stream
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
        private val correlationId = UUID.randomUUID().toString()
        private val paymentEndToEndId = "paymentEndToEndId"
        private val operationId = "operationId"

        @JvmStatic
        private fun retriverArgsTest() =
            Stream.of(
                Arguments.of(
                    TransactionAuthorizationRequestData.PaymentGateway.NPG,
                    buildOrderResponseDtoForNgpOrderNotAuthorized(),
                    DeadLetterNpgTransactionInfoDetailsData(
                        OperationResultDto.FAILED,
                        operationId,
                        correlationId,
                        paymentEndToEndId
                    )
                ),
                Arguments.of(
                    TransactionAuthorizationRequestData.PaymentGateway.NPG,
                    buildOrderResponseDtoForNgpOrderAuthorized(),
                    DeadLetterNpgTransactionInfoDetailsData(
                        OperationResultDto.EXECUTED,
                        operationId,
                        correlationId,
                        paymentEndToEndId
                    )
                ),
                Arguments.of(
                    TransactionAuthorizationRequestData.PaymentGateway.NPG,
                    buildOrderResponseDtoForNpgOrderRefunded(),
                    DeadLetterNpgTransactionInfoDetailsData(
                        OperationResultDto.VOIDED,
                        operationId,
                        correlationId,
                        paymentEndToEndId
                    )
                ),
                Arguments.of(
                    TransactionAuthorizationRequestData.PaymentGateway.REDIRECT,
                    buildOrderResponseDtoForNpgOrderRefunded(),
                    DeadLetterRedirectTransactionInfoDetailsData("")
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
        PaymentGatewayConfig()
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

    @Test
    fun `Should process correctly the event into a transaction info`() {

        val transactionView =
            transactionDocument(TransactionStatusDto.NOTIFIED_OK, ZonedDateTime.now())

        val transactionUserReceiptData =
            transactionUserReceiptData(TransactionUserReceiptData.Outcome.OK)
        val transactionActivatedEvent =
            transactionActivateEvent(NpgTransactionGatewayActivationData("orderId", correlationId))
        val authorizationRequestedEvent =
            transactionAuthorizationRequestedEvent(
                TransactionAuthorizationRequestData.PaymentGateway.NPG
            )
        val closureSentEvent = transactionClosedEvent(TransactionClosureData.Outcome.KO)
        val addUserReceiptEvent = transactionUserReceiptRequestedEvent(transactionUserReceiptData)
        val userReceiptAddErrorEvent = transactionUserReceiptAddErrorEvent(addUserReceiptEvent.data)
        val userReceiptAddedEvent = transactionUserReceiptAddedEvent(userReceiptAddErrorEvent.data)
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

        val baseTransaction = reduceEvents(*events.toTypedArray())
        val expected =
            transactionInfoService.baseTransactionToTransactionInfoDto(
                baseTransaction,
                DeadLetterNpgTransactionInfoDetailsData(
                    OperationResultDto.VOIDED,
                    operationId,
                    correlationId,
                    paymentEndToEndId
                )
            )

        given(checkPointer.success()).willReturn(Mono.empty())
        given(
                transactionEventRepository.findByTransactionIdOrderByCreationDateAsc(
                    transactionView.transactionId
                )
            )
            .willReturn(Flux.fromIterable(events))

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
        val events =
            TransactionInfoUtils.buildEventsList(
                correlationId,
                TransactionAuthorizationRequestData.PaymentGateway.NPG
            )
        val baseTransaction = reduceEvents(*events.toTypedArray())
        val expected =
            transactionInfoService.baseTransactionToTransactionInfoDto(
                baseTransaction,
                DeadLetterNpgTransactionInfoDetailsData(
                    OperationResultDto.VOIDED,
                    operationId,
                    correlationId,
                    paymentEndToEndId
                )
            )

        given(npgClient.getOrder(any(), any(), any()))
            .willReturn(buildOrderResponseDtoForNpgOrderRefunded())
        given(checkPointer.success()).willReturn(Mono.empty())
        given(transactionEventRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID))
            .willReturn(Flux.fromIterable(events))
        StepVerifier.create(
                transactionInfoService.getTransactionInfoByTransactionId(TRANSACTION_ID)
            )
            .expectNext(expected)
            .verifyComplete()
    }

    @ParameterizedTest
    @MethodSource("retriverArgsTest")
    fun `Should retrieve gateway info and put it into TransactionInfo object`(
        gateway: TransactionAuthorizationRequestData.PaymentGateway,
        orderResponseMock: Mono<OrderResponseDto>,
        detailsExpected: DeadLetterTransactionInfoDetailsData
    ) {

        val events = TransactionInfoUtils.buildEventsList(correlationId, gateway)
        val baseTransaction = reduceEvents(*events.toTypedArray())

        given(checkPointer.success()).willReturn(Mono.empty())
        given(transactionEventRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID))
            .willReturn(Flux.fromIterable(events))
        given(npgClient.getOrder(any(), any(), any())).willReturn(orderResponseMock)

        StepVerifier.create(transactionInfoService.getTransactionInfoDetails(baseTransaction))
            .expectNext(detailsExpected)
            .verifyComplete()
    }

    @Test
    fun `Should throw error for gateway param null`() {
        val events =
            TransactionInfoUtils.buildEventsList(
                correlationId,
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
                        null,
                        "paymentMethodDescription",
                        NpgTransactionGatewayAuthorizationRequestedData()
                    )
                )
            )
        val baseTransaction = reduceEvents(*events.toTypedArray())

        given(npgClient.getOrder(any(), any(), any()))
            .willReturn(buildOrderResponseDtoForNpgOrderRefunded())
        given(checkPointer.success()).willReturn(Mono.empty())
        given(transactionEventRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID))
            .willReturn(Flux.fromIterable(events))

        StepVerifier.create(transactionInfoService.getTransactionInfoDetails(baseTransaction))
            .expectErrorMatches {
                it is InvalidGatewayException &&
                    it.message == "Transaction with id: $TRANSACTION_ID has invalid gateway"
            }
            .verify()
    }

    @Test
    fun `Should throw error for invalid order`() {
        val events =
            TransactionInfoUtils.buildEventsList(
                correlationId,
                TransactionAuthorizationRequestData.PaymentGateway.NPG
            )
        val baseTransaction = reduceEvents(*events.toTypedArray())

        given(npgClient.getOrder(any(), any(), any()))
            .willReturn(buildOrderResponseDtoInvalidOrder())
        given(checkPointer.success()).willReturn(Mono.empty())
        given(transactionEventRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID))
            .willReturn(Flux.fromIterable(events))

        StepVerifier.create(transactionInfoService.getTransactionInfoDetails(baseTransaction))
            .expectErrorMatches {
                it is InvalidNpgOrderException &&
                    it.message ==
                        "Invalid operation retrived from gateway for transaction: ${TRANSACTION_ID}"
            }
            .verify()
    }

    @Test
    fun `Should throw error for invalid order refound`() {
        val events =
            TransactionInfoUtils.buildEventsList(
                correlationId,
                TransactionAuthorizationRequestData.PaymentGateway.NPG
            )
        val baseTransaction = reduceEvents(*events.toTypedArray())

        given(npgClient.getOrder(any(), any(), any()))
            .willReturn(buildOrderResponseDtoForNpgOrderRefundedFaulty())
        given(checkPointer.success()).willReturn(Mono.empty())
        given(transactionEventRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID))
            .willReturn(Flux.fromIterable(events))

        StepVerifier.create(transactionInfoService.getTransactionInfoDetails(baseTransaction))
            .expectErrorMatches {
                it is InvalidNpgOrderException &&
                    it.message ==
                        "Invalid operation retrived from gateway for transaction: ${TRANSACTION_ID}"
            }
            .verify()
    }

    @Test
    fun `Should throw error for invalid order authorized`() {
        val events =
            TransactionInfoUtils.buildEventsList(
                correlationId,
                TransactionAuthorizationRequestData.PaymentGateway.NPG
            )
        val baseTransaction = reduceEvents(*events.toTypedArray())

        given(npgClient.getOrder(any(), any(), any()))
            .willReturn(buildOrderResponseDtoForNpgOrderVoid())
        given(checkPointer.success()).willReturn(Mono.empty())
        given(transactionEventRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID))
            .willReturn(Flux.fromIterable(events))

        StepVerifier.create(transactionInfoService.getTransactionInfoDetails(baseTransaction))
            .expectErrorMatches {
                it is InvalidNpgOrderException &&
                    it.message ==
                        "Invalid operation retrived from gateway for transaction: ${TRANSACTION_ID}"
            }
            .verify()
    }

    @Test
    fun `Should throw error for null operations`() {
        val events =
            TransactionInfoUtils.buildEventsList(
                correlationId,
                TransactionAuthorizationRequestData.PaymentGateway.NPG
            )
        val baseTransaction = reduceEvents(*events.toTypedArray())

        given(checkPointer.success()).willReturn(Mono.empty())
        given(transactionEventRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID))
            .willReturn(Flux.fromIterable(events))
        given(npgClient.getOrder(any(), any(), any()))
            .willReturn(buildOrderResponseDtoNullOperation())

        StepVerifier.create(transactionInfoService.getTransactionInfoDetails(baseTransaction))
            .expectErrorMatches {
                it is NoOperationFoundException &&
                    it.message == "No operations found for transaction with id: $TRANSACTION_ID"
            }
            .verify()
    }

    @Test
    fun `Should throw error for wrong pspId`() {
        val events =
            TransactionInfoUtils.buildEventsList(
                correlationId,
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
            )
        val baseTransaction = reduceEvents(*events.toTypedArray())

        given(npgClient.getOrder(any(), any(), any()))
            .willReturn(buildOrderResponseDtoForNpgOrderRefunded())
        given(checkPointer.success()).willReturn(Mono.empty())
        given(transactionEventRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID))
            .willReturn(Flux.fromIterable(events))

        StepVerifier.create(transactionInfoService.getTransactionInfoDetails(baseTransaction))
            .expectErrorMatches {
                it is NpgApiKeyConfigurationException &&
                    it.message ==
                        "Cannot retrieve api key for payment method: [CARDS]. Cause: Requested API key for PSP: [pspId2]. Available PSPs: [pspId]"
            }
            .verify()
    }

    @Test
    fun `Should throw error when getOrder call fails - is5xxServerError is true`() {
        val events =
            TransactionInfoUtils.buildEventsList(
                correlationId,
                TransactionAuthorizationRequestData.PaymentGateway.NPG
            )
        val baseTransaction = reduceEvents(*events.toTypedArray())

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
        given(checkPointer.success()).willReturn(Mono.empty())
        given(transactionEventRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID))
            .willReturn(Flux.fromIterable(events))

        StepVerifier.create(transactionInfoService.getTransactionInfoDetails(baseTransaction))
            .expectErrorMatches {
                it is NpgBadGatewayException &&
                    it.message ==
                        "Bad gateway : Received HTTP error code from NPG: 500 INTERNAL_SERVER_ERROR"
            }
            .verify()
    }

    @Test
    fun `Should throw error when getOrder call fails - is5xxServerError is false`() {
        val events =
            TransactionInfoUtils.buildEventsList(
                correlationId,
                TransactionAuthorizationRequestData.PaymentGateway.NPG
            )
        val baseTransaction = reduceEvents(*events.toTypedArray())

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
        given(checkPointer.success()).willReturn(Mono.empty())
        given(transactionEventRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID))
            .willReturn(Flux.fromIterable(events))

        StepVerifier.create(transactionInfoService.getTransactionInfoDetails(baseTransaction))
            .expectErrorMatches {
                it is NpgBadRequestException &&
                    it.message ==
                        "Transaction with id $TRANSACTION_ID npg state cannot be retrieved. Reason: Received HTTP error code from NPG: 400 BAD_REQUEST"
            }
            .verify()
    }

    @Test
    fun `Should return empty data details when getOrder call fails`() {
        val events =
            TransactionInfoUtils.buildEventsList(
                correlationId,
                TransactionAuthorizationRequestData.PaymentGateway.NPG
            )

        val baseTransaction = reduceEvents(*events.toTypedArray())
        val expected =
            transactionInfoService.baseTransactionToTransactionInfoDto(
                baseTransaction,
                DeadLetterNpgTransactionInfoDetailsData()
            )

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
        given(checkPointer.success()).willReturn(Mono.empty())
        given(transactionEventRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID))
            .willReturn(Flux.fromIterable(events))

        StepVerifier.create(
                transactionInfoService.getTransactionInfoByTransactionId(TRANSACTION_ID)
            )
            .expectNext(expected)
            .verifyComplete()
    }
}
