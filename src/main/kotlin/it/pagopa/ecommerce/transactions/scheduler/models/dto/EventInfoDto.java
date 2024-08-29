package it.pagopa.ecommerce.transactions.scheduler.models.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.OffsetDateTime;
import java.util.Objects;

public class EventInfoDto {

    @JsonProperty("creationDate")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime creationDate;

    @JsonProperty("eventCode")
    private String eventCode;

    public EventInfoDto creationDate(OffsetDateTime creationDate) {
        this.creationDate = creationDate;
        return this;
    }

    public OffsetDateTime getCreationDate() {
        return creationDate;
    }

    public void setCreationDate(OffsetDateTime creationDate) {
        this.creationDate = creationDate;
    }

    public EventInfoDto eventCode(String eventCode) {
        this.eventCode = eventCode;
        return this;
    }


    public String getEventCode() {
        return eventCode;
    }

    public void setEventCode(String eventCode) {
        this.eventCode = eventCode;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        EventInfoDto eventInfo = (EventInfoDto) o;
        return Objects.equals(this.creationDate, eventInfo.creationDate) &&
                Objects.equals(this.eventCode, eventInfo.eventCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(creationDate, eventCode);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("class EventInfoDto {\n");
        sb.append("    creationDate: ").append(toIndentedString(creationDate)).append("\n");
        sb.append("    eventCode: ").append(toIndentedString(eventCode)).append("\n");
        sb.append("}");
        return sb.toString();
    }

    /**
     * Convert the given object to string with each line indented by 4 spaces
     * (except the first line).
     */
    private String toIndentedString(Object o) {
        if (o == null) {
            return "null";
        }
        return o.toString().replace("\n", "\n    ");
    }
}
