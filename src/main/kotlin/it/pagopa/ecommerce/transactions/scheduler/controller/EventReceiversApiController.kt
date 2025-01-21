package it.pagopa.ecommerce.transactions.scheduler.controller


import it.pagopa.generated.scheduler.server.api.EventReceiversApi
import it.pagopa.generated.scheduler.server.model.DeploymentVersionDto
import it.pagopa.generated.scheduler.server.model.EventReceiverCommandRequestDto
import it.pagopa.generated.scheduler.server.model.EventReceiverStatusResponseDto
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

/** Event receivers commands api controller implementation */
@RestController
class EventReceiversApiController(

) : EventReceiversApi {

    /** Handle new receiver command */
    override suspend fun newReceiverCommand(
        eventReceiverCommandRequestDto: EventReceiverCommandRequestDto
    ): ResponseEntity<Unit> {
        TODO()
    }

    /** Returns receiver statuses */
    override suspend fun retrieveReceiverStatus(
        version: DeploymentVersionDto?
    ): ResponseEntity<EventReceiverStatusResponseDto> {
        TODO()
    }
}
