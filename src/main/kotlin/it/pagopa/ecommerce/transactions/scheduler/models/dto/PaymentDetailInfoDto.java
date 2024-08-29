package it.pagopa.ecommerce.transactions.scheduler.models.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

public class PaymentDetailInfoDto {

    @JsonProperty("subject")
    private String subject;

    @JsonProperty("iuv")
    private String iuv;

    @JsonProperty("rptId")
    private String rptId;

    @JsonProperty("amount")
    private Integer amount;

    @JsonProperty("paymentToken")
    private String paymentToken;

    @JsonProperty("creditorInstitution")
    private String creditorInstitution;

    @JsonProperty("paFiscalCode")
    private String paFiscalCode;

    public PaymentDetailInfoDto subject(String subject) {
        this.subject = subject;
        return this;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public PaymentDetailInfoDto iuv(String iuv) {
        this.iuv = iuv;
        return this;
    }

    public String getIuv() {
        return iuv;
    }

    public void setIuv(String iuv) {
        this.iuv = iuv;
    }

    public PaymentDetailInfoDto rptId(String rptId) {
        this.rptId = rptId;
        return this;
    }

    public String getRptId() {
        return rptId;
    }

    public void setRptId(String rptId) {
        this.rptId = rptId;
    }

    public PaymentDetailInfoDto amount(Integer amount) {
        this.amount = amount;
        return this;
    }

    public Integer getAmount() {
        return amount;
    }

    public void setAmount(Integer amount) {
        this.amount = amount;
    }

    public PaymentDetailInfoDto paymentToken(String paymentToken) {
        this.paymentToken = paymentToken;
        return this;
    }

    public String getPaymentToken() {
        return paymentToken;
    }

    public void setPaymentToken(String paymentToken) {
        this.paymentToken = paymentToken;
    }

    public PaymentDetailInfoDto creditorInstitution(String creditorInstitution) {
        this.creditorInstitution = creditorInstitution;
        return this;
    }

    public String getCreditorInstitution() {
        return creditorInstitution;
    }

    public void setCreditorInstitution(String creditorInstitution) {
        this.creditorInstitution = creditorInstitution;
    }

    public PaymentDetailInfoDto paFiscalCode(String paFiscalCode) {
        this.paFiscalCode = paFiscalCode;
        return this;
    }

    public String getPaFiscalCode() {
        return paFiscalCode;
    }

    public void setPaFiscalCode(String paFiscalCode) {
        this.paFiscalCode = paFiscalCode;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        PaymentDetailInfoDto paymentDetailInfo = (PaymentDetailInfoDto) o;
        return Objects.equals(this.subject, paymentDetailInfo.subject) &&
                Objects.equals(this.iuv, paymentDetailInfo.iuv) &&
                Objects.equals(this.rptId, paymentDetailInfo.rptId) &&
                Objects.equals(this.amount, paymentDetailInfo.amount) &&
                Objects.equals(this.paymentToken, paymentDetailInfo.paymentToken) &&
                Objects.equals(this.creditorInstitution, paymentDetailInfo.creditorInstitution) &&
                Objects.equals(this.paFiscalCode, paymentDetailInfo.paFiscalCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(subject, iuv, rptId, amount, paymentToken, creditorInstitution, paFiscalCode);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("class PaymentDetailInfoDto {\n");
        sb.append("    subject: ").append(toIndentedString(subject)).append("\n");
        sb.append("    iuv: ").append(toIndentedString(iuv)).append("\n");
        sb.append("    rptId: ").append(toIndentedString(rptId)).append("\n");
        sb.append("    amount: ").append(toIndentedString(amount)).append("\n");
        sb.append("    paymentToken: ").append(toIndentedString(paymentToken)).append("\n");
        sb.append("    creditorInstitution: ").append(toIndentedString(creditorInstitution)).append("\n");
        sb.append("    paFiscalCode: ").append(toIndentedString(paFiscalCode)).append("\n");
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

