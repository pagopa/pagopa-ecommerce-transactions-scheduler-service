package it.pagopa.ecommerce.transactions.scheduler.exceptions

class InvalidGatewayException(transactionId: String?) :
    RuntimeException("Transaction with id: $transactionId has invalid gateway")

class InvalidNpgOrderException(transactionId: String?) :
    RuntimeException("Invalid operation retrived from gateway for transaction: $transactionId")

class NoOperationFoundException(transactionId: String?) :
    RuntimeException("No operations found for transaction with id: $transactionId")

class NpgBadGatewayException(errorCodeReason: String?) :
    RuntimeException("Bad gateway : Received HTTP error code from NPG: $errorCodeReason")

class NpgBadRequestException(transactionId: String?, errorCodeReason: String?) :
    RuntimeException(
        "Transaction with id $transactionId npg state cannot be retrieved. Reason: Received HTTP error code from NPG: $errorCodeReason"
    )

/** Exception thrown when no data can be found querying for event receiver statuses */
class NoEventReceiverStatusFound : RuntimeException("No event receiver status data found")