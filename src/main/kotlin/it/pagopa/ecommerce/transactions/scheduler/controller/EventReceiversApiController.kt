package it.pagopa.ecommerce.transactions.scheduler.controller

import it.pagopa.ecommerce.transactions.scheduler.services.EventReceiverService
import it.pagopa.generated.scheduler.server.api.EventReceiversApi
import it.pagopa.generated.scheduler.server.model.DeploymentVersionDto
import it.pagopa.generated.scheduler.server.model.EventReceiverCommandRequestDto
import it.pagopa.generated.scheduler.server.model.EventReceiverStatusResponseDto
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

/** Event receivers commands api controller implementation */
@RestController
class EventReceiversApiController(
    @Autowired private val eventReceiverService: EventReceiverService,
) : EventReceiversApi {

    /** Handle new receiver command */
    override suspend fun newReceiverCommand(
        eventReceiverCommandRequestDto: EventReceiverCommandRequestDto
    ): ResponseEntity<Unit> {
        return eventReceiverService.handleCommand(eventReceiverCommandRequestDto).let {
            ResponseEntity.accepted().build()
        }
    }

    /** Returns receiver statuses */
    override suspend fun retrieveReceiverStatus(
        version: DeploymentVersionDto?
    ): ResponseEntity<EventReceiverStatusResponseDto> {
        val dto = eventReceiverService.getReceiversStatus(deploymentVersionDto = version)

        return ResponseEntity.ok(dto)
    }
}
