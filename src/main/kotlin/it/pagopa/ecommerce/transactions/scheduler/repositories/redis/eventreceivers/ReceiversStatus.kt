package it.pagopa.ecommerce.transactions.scheduler.repositories.redis.eventreceivers

import it.pagopa.generated.scheduler.server.model.DeploymentVersionDto

/** Data class that contain all information about a specific event receiver */
data class ReceiversStatus(
    val consumerInstanceId: String,
    val queriedAt: String,
    val version: DeploymentVersionDto?,
    val receiverStatuses: List<ReceiverStatus>
)
