package it.pagopa.ecommerce.transactions.scheduler.repositories.redis.eventreceivers

/** Data class that contains status information for a specific event receiver */
data class ReceiverStatus(val name: String, val status: Status)
