package it.pagopa.ecommerce.transactions.scheduler.services

import it.pagopa.ecommerce.transactions.scheduler.configurations.RedisStreamEventControllerConfigs
import it.pagopa.ecommerce.transactions.scheduler.configurations.redis.EventDispatcherCommandsTemplateWrapper
import it.pagopa.ecommerce.transactions.scheduler.configurations.redis.EventDispatcherReceiverStatusTemplateWrapper
import it.pagopa.ecommerce.transactions.scheduler.exceptions.NoEventReceiverStatusFound
import it.pagopa.ecommerce.transactions.scheduler.streams.commands.EventDispatcherReceiverCommand
import it.pagopa.generated.scheduler.server.model.*
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux

/** This class handles all InboundChannelsAdapters events receivers */
@Service
class EventReceiverService(
    @Autowired
    private val eventDispatcherCommandsTemplateWrapper: EventDispatcherCommandsTemplateWrapper,
    @Autowired
    private val eventDispatcherReceiverStatusTemplateWrapper:
        EventDispatcherReceiverStatusTemplateWrapper,
    @Autowired private val redisStreamConf: RedisStreamEventControllerConfigs
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun handleCommand(eventReceiverCommandRequestDto: EventReceiverCommandRequestDto) {
        val commandToSend =
            when (eventReceiverCommandRequestDto.command) {
                EventReceiverCommandRequestDto.Command.START ->
                    EventDispatcherReceiverCommand.ReceiverCommand.START
                EventReceiverCommandRequestDto.Command.STOP ->
                    EventDispatcherReceiverCommand.ReceiverCommand.STOP
            }
        logger.info("Received event receiver command request, command: {}", commandToSend)
        // trim all events before adding new event to be processed
        val recordId =
            eventDispatcherCommandsTemplateWrapper.writeEventToStreamTrimmingEvents(
                redisStreamConf.streamKey,
                EventDispatcherReceiverCommand(
                    receiverCommand = commandToSend,
                    version = eventReceiverCommandRequestDto.deploymentVersion
                ),
                0
            )

        logger.info("Sent new event to Redis stream with id: [{}]", recordId)
    }

    suspend fun getReceiversStatus(
        deploymentVersionDto: DeploymentVersionDto?
    ): EventReceiverStatusResponseDto {
        val lastStatuses =
            eventDispatcherReceiverStatusTemplateWrapper.allValuesInKeySpace?.filter {
                deploymentVersionDto == null || it.version == deploymentVersionDto
            }
                ?: Flux.empty()

        val list =
            lastStatuses
                .map { rs ->
                    EventReceiverStatusDto(
                        receiverStatuses =
                            rs.receiverStatuses.map { r ->
                                ReceiverStatusDto(
                                    status = ReceiverStatusDto.Status.valueOf(r.status.toString()),
                                    name = r.name
                                )
                            },
                        instanceId = rs.consumerInstanceId,
                        deploymentVersion = rs.version
                    )
                }
                .collectList()
                .awaitSingle()

        if (list.isEmpty()) {
            throw NoEventReceiverStatusFound()
        }

        return EventReceiverStatusResponseDto(status = list)
    }
}
