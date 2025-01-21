package it.pagopa.ecommerce.transactions.scheduler.streams.commands

import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.util.*

/** Event dispatcher generic command event class */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.CLASS,
    include = JsonTypeInfo.As.PROPERTY,
    property = "_class",
    visible = false
)
sealed class EventDispatcherGenericCommand(
    val commandId: UUID = UUID.randomUUID(),
    val type: CommandType
) {
    enum class CommandType {
        RECEIVER_COMMAND
    }
}
