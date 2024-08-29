package it.pagopa.ecommerce.transactions.scheduler.models.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TransactionInfoDto {

    @JsonProperty("creationDate")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime creationDate;

    @JsonProperty("status")
    private String status;

    @JsonProperty("statusDetails")
    private String statusDetails;

    @JsonProperty("events")
    private List<EventInfoDto> events = null;

    @JsonProperty("eventStatus")
    private TransactionStatusDto eventStatus;

    @JsonProperty("amount")
    private Integer amount;

    @JsonProperty("fee")
    private Integer fee;

    @JsonProperty("grandTotal")
    private Integer grandTotal;

    @JsonProperty("rrn")
    private String rrn;

    @JsonProperty("authorizationCode")
    private String authorizationCode;

    @JsonProperty("authorizationOperationId")
    private String authorizationOperationId;

    @JsonProperty("refundOperationId")
    private String refundOperationId;

    @JsonProperty("paymentMethodName")
    private String paymentMethodName;

    @JsonProperty("brand")
    private String brand;

    @JsonProperty("authorizationRequestId")
    private String authorizationRequestId;

    @JsonProperty("paymentGateway")
    private String paymentGateway;

    @JsonProperty("correlationId")
    private UUID correlationId;

    @JsonProperty("gatewayAuthorizationStatus")
    private String gatewayAuthorizationStatus;

    @JsonProperty("gatewayErrorCode")
    private String gatewayErrorCode;

    public TransactionInfoDto creationDate(OffsetDateTime creationDate) {
        this.creationDate = creationDate;
        return this;
    }

    public OffsetDateTime getCreationDate() {
        return creationDate;
    }

    public void setCreationDate(OffsetDateTime creationDate) {
        this.creationDate = creationDate;
    }

    public TransactionInfoDto status(String status) {
        this.status = status;
        return this;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public TransactionInfoDto statusDetails(String statusDetails) {
        this.statusDetails = statusDetails;
        return this;
    }

    public String getStatusDetails() {
        return statusDetails;
    }

    public void setStatusDetails(String statusDetails) {
        this.statusDetails = statusDetails;
    }

    public TransactionInfoDto events(List<EventInfoDto> events) {
        this.events = events;
        return this;
    }

    public TransactionInfoDto addEventsItem(EventInfoDto eventsItem) {
        if (this.events == null) {
            this.events = new ArrayList<>();
        }
        this.events.add(eventsItem);
        return this;
    }

    public List<EventInfoDto> getEvents() {
        return events;
    }

    public void setEvents(List<EventInfoDto> events) {
        this.events = events;
    }

    public TransactionInfoDto eventStatus(TransactionStatusDto eventStatus) {
        this.eventStatus = eventStatus;
        return this;
    }

    public TransactionStatusDto getEventStatus() {
        return eventStatus;
    }

    public void setEventStatus(TransactionStatusDto eventStatus) {
        this.eventStatus = eventStatus;
    }

    public TransactionInfoDto amount(Integer amount) {
        this.amount = amount;
        return this;
    }

    public Integer getAmount() {
        return amount;
    }

    public void setAmount(Integer amount) {
        this.amount = amount;
    }

    public TransactionInfoDto fee(Integer fee) {
        this.fee = fee;
        return this;
    }

    public Integer getFee() {
        return fee;
    }

    public void setFee(Integer fee) {
        this.fee = fee;
    }

    public TransactionInfoDto grandTotal(Integer grandTotal) {
        this.grandTotal = grandTotal;
        return this;
    }

    public Integer getGrandTotal() {
        return grandTotal;
    }

    public void setGrandTotal(Integer grandTotal) {
        this.grandTotal = grandTotal;
    }

    public TransactionInfoDto rrn(String rrn) {
        this.rrn = rrn;
        return this;
    }

    public String getRrn() {
        return rrn;
    }

    public void setRrn(String rrn) {
        this.rrn = rrn;
    }

    public TransactionInfoDto authorizationCode(String authorizationCode) {
        this.authorizationCode = authorizationCode;
        return this;
    }

    public String getAuthorizationCode() {
        return authorizationCode;
    }

    public void setAuthorizationCode(String authorizationCode) {
        this.authorizationCode = authorizationCode;
    }

    public TransactionInfoDto authorizationOperationId(String authorizationOperationId) {
        this.authorizationOperationId = authorizationOperationId;
        return this;
    }

    public String getAuthorizationOperationId() {
        return authorizationOperationId;
    }

    public void setAuthorizationOperationId(String authorizationOperationId) {
        this.authorizationOperationId = authorizationOperationId;
    }

    public TransactionInfoDto refundOperationId(String refundOperationId) {
        this.refundOperationId = refundOperationId;
        return this;
    }

    public String getRefundOperationId() {
        return refundOperationId;
    }

    public void setRefundOperationId(String refundOperationId) {
        this.refundOperationId = refundOperationId;
    }

    public TransactionInfoDto paymentMethodName(String paymentMethodName) {
        this.paymentMethodName = paymentMethodName;
        return this;
    }

    public String getPaymentMethodName() {
        return paymentMethodName;
    }

    public void setPaymentMethodName(String paymentMethodName) {
        this.paymentMethodName = paymentMethodName;
    }

    public TransactionInfoDto brand(String brand) {
        this.brand = brand;
        return this;
    }

    public String getBrand() {
        return brand;
    }

    public void setBrand(String brand) {
        this.brand = brand;
    }

    public TransactionInfoDto authorizationRequestId(String authorizationRequestId) {
        this.authorizationRequestId = authorizationRequestId;
        return this;
    }

    public String getAuthorizationRequestId() {
        return authorizationRequestId;
    }

    public void setAuthorizationRequestId(String authorizationRequestId) {
        this.authorizationRequestId = authorizationRequestId;
    }

    public TransactionInfoDto paymentGateway(String paymentGateway) {
        this.paymentGateway = paymentGateway;
        return this;
    }

   public String getPaymentGateway() {
        return paymentGateway;
    }

    public void setPaymentGateway(String paymentGateway) {
        this.paymentGateway = paymentGateway;
    }

    public TransactionInfoDto correlationId(UUID correlationId) {
        this.correlationId = correlationId;
        return this;
    }

   public UUID getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(UUID correlationId) {
        this.correlationId = correlationId;
    }

    public TransactionInfoDto gatewayAuthorizationStatus(String gatewayAuthorizationStatus) {
        this.gatewayAuthorizationStatus = gatewayAuthorizationStatus;
        return this;
    }

 public String getGatewayAuthorizationStatus() {
        return gatewayAuthorizationStatus;
    }

    public void setGatewayAuthorizationStatus(String gatewayAuthorizationStatus) {
        this.gatewayAuthorizationStatus = gatewayAuthorizationStatus;
    }

    public TransactionInfoDto gatewayErrorCode(String gatewayErrorCode) {
        this.gatewayErrorCode = gatewayErrorCode;
        return this;
    }

  public String getGatewayErrorCode() {
        return gatewayErrorCode;
    }

    public void setGatewayErrorCode(String gatewayErrorCode) {
        this.gatewayErrorCode = gatewayErrorCode;
    }

}