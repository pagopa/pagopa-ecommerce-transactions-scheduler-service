package it.pagopa.ecommerce.transactions.scheduler.models.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum TransactionStatusDto {

    ACTIVATED("ACTIVATED"),

    AUTHORIZATION_REQUESTED("AUTHORIZATION_REQUESTED"),

    AUTHORIZATION_COMPLETED("AUTHORIZATION_COMPLETED"),

    CLOSURE_REQUESTED("CLOSURE_REQUESTED"),

    CLOSED("CLOSED"),

    CLOSURE_ERROR("CLOSURE_ERROR"),

    NOTIFIED_OK("NOTIFIED_OK"),

    NOTIFIED_KO("NOTIFIED_KO"),

    NOTIFICATION_ERROR("NOTIFICATION_ERROR"),

    NOTIFICATION_REQUESTED("NOTIFICATION_REQUESTED"),

    EXPIRED("EXPIRED"),

    REFUNDED("REFUNDED"),

    CANCELED("CANCELED"),

    EXPIRED_NOT_AUTHORIZED("EXPIRED_NOT_AUTHORIZED"),

    UNAUTHORIZED("UNAUTHORIZED"),

    REFUND_ERROR("REFUND_ERROR"),

    REFUND_REQUESTED("REFUND_REQUESTED"),

    CANCELLATION_REQUESTED("CANCELLATION_REQUESTED"),

    CANCELLATION_EXPIRED("CANCELLATION_EXPIRED");

    private String value;

    TransactionStatusDto(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }

    @JsonCreator
    public static TransactionStatusDto fromValue(String value) {
        for (TransactionStatusDto b : TransactionStatusDto.values()) {
            if (b.value.equals(value)) {
                return b;
            }
        }
        throw new IllegalArgumentException("Unexpected value '" + value + "'");
    }
}

