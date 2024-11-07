package it.pagopa.ecommerce.transactions.scheduler

import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestData
import it.pagopa.ecommerce.commons.documents.v2.deadletter.DeadLetterNpgTransactionInfoDetailsData
import it.pagopa.ecommerce.commons.documents.v2.deadletter.DeadLetterTransactionInfo
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OperationResultDto
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
import java.util.UUID

object TransactionSchedulerTestUtil {

    fun buildNpgTransactionInfo(transactionId: String) =
        DeadLetterTransactionInfo(
            transactionId,
            "auth request id",
            TransactionStatusDto.EXPIRED,
            TransactionAuthorizationRequestData.PaymentGateway.NPG,
            listOf("tokens"),
            "psp id",
            "payment method name",
            100,
            "rrn",
            DeadLetterNpgTransactionInfoDetailsData(
                OperationResultDto.EXECUTED,
                "id",
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
            )
        )

    fun getEventJsonString() =
        "{\n" +
            "    \"event\": {\n" +
            "        \"_class\": \"it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedEvent\",\n" +
            "        \"id\": \"71f42248-f08e-4a29-a01d-8694bb55fe6f\",\n" +
            "        \"transactionId\": \"e68308b5215f4757b242d6aaa010c235\",\n" +
            "        \"creationDate\": \"2024-08-27T10:05:37.038300574Z[Etc/UTC]\",\n" +
            "        \"data\": {\n" +
            "            \"email\": {\n" +
            "                \"data\": \"3fa85f64-5717-4562-b3fc-2c963f66afa6\"\n" +
            "            },\n" +
            "            \"paymentNotices\": [{\n" +
            "                    \"paymentToken\": \"5ed0ccc6d15f4986a9e2afe1a719fe39\",\n" +
            "                    \"rptId\": \"77777777777330200000000000000\",\n" +
            "                    \"description\": \"Quota Albo Ordine Giornalisti - 56857093886\",\n" +
            "                    \"amount\": 10000,\n" +
            "                    \"paymentContextCode\": null,\n" +
            "                    \"transferList\": [{\n" +
            "                            \"paFiscalCode\": \"77777777777\",\n" +
            "                            \"digitalStamp\": false,\n" +
            "                            \"transferAmount\": 5000,\n" +
            "                            \"transferCategory\": \"transferCategoryTest\"\n" +
            "                        }, {\n" +
            "                            \"paFiscalCode\": \"77777777777\",\n" +
            "                            \"digitalStamp\": true,\n" +
            "                            \"transferAmount\": 5000,\n" +
            "                            \"transferCategory\": null\n" +
            "                        }\n" +
            "                    ],\n" +
            "                    \"companyName\": \"company_53009433199\",\n" +
            "                    \"allCCP\": false\n" +
            "                }\n" +
            "            ],\n" +
            "            \"faultCode\": null,\n" +
            "            \"faultCodeString\": null,\n" +
            "            \"clientId\": \"CHECKOUT\",\n" +
            "            \"idCart\": null,\n" +
            "            \"paymentTokenValiditySeconds\": 120,\n" +
            "            \"transactionGatewayActivationData\": {\n" +
            "                \"type\": \"NPG\",\n" +
            "                \"orderId\": \"E1724753135269RGSK\",\n" +
            "                \"correlationId\": \"08ebf44b-3805-42c2-b421-02152d6ef8ed\"\n" +
            "            },\n" +
            "            \"userId\": null\n" +
            "        },\n" +
            "        \"eventCode\": \"TRANSACTION_ACTIVATED_EVENT\"\n" +
            "    },\n" +
            "    \"tracingInfo\": {\n" +
            "        \"traceparent\": \"00-3e118826757de38191f6942aafe38065-2db6a4f8578f997b-01\",\n" +
            "        \"tracestate\": null,\n" +
            "        \"baggage\": null\n" +
            "    }\n" +
            "}"
}
