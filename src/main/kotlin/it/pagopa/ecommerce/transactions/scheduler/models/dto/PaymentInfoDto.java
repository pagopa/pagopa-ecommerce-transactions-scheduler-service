package it.pagopa.ecommerce.transactions.scheduler.models.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class PaymentInfoDto {

    @JsonProperty("origin")
    private String origin;

    @JsonProperty("idTransaction")
    private String idTransaction;

    @JsonProperty("details")
    private List<PaymentDetailInfoDto> details = null;

    public PaymentInfoDto origin(String origin) {
        this.origin = origin;
        return this;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public PaymentInfoDto idTransaction(String idTransaction) {
        this.idTransaction = idTransaction;
        return this;
    }

    public String getIdTransaction() {
        return idTransaction;
    }

    public void setIdTransaction(String idTransaction) {
        this.idTransaction = idTransaction;
    }

    public PaymentInfoDto details(List<PaymentDetailInfoDto> details) {
        this.details = details;
        return this;
    }

    public PaymentInfoDto addDetailsItem(PaymentDetailInfoDto detailsItem) {
        if (this.details == null) {
            this.details = new ArrayList<>();
        }
        this.details.add(detailsItem);
        return this;
    }

    public List<PaymentDetailInfoDto> getDetails() {
        return details;
    }

    public void setDetails(List<PaymentDetailInfoDto> details) {
        this.details = details;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        PaymentInfoDto paymentInfo = (PaymentInfoDto) o;
        return Objects.equals(this.origin, paymentInfo.origin) &&
                Objects.equals(this.idTransaction, paymentInfo.idTransaction) &&
                Objects.equals(this.details, paymentInfo.details);
    }

    @Override
    public int hashCode() {
        return Objects.hash(origin, idTransaction, details);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("class PaymentInfoDto {\n");
        sb.append("    origin: ").append(toIndentedString(origin)).append("\n");
        sb.append("    idTransaction: ").append(toIndentedString(idTransaction)).append("\n");
        sb.append("    details: ").append(toIndentedString(details)).append("\n");
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

