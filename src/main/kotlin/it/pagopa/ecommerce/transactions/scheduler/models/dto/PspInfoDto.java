package it.pagopa.ecommerce.transactions.scheduler.models.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class PspInfoDto {

    @JsonProperty("pspId")
    private String pspId;

    @JsonProperty("businessName")
    private String businessName;

    @JsonProperty("idChannel")
    private String idChannel;

    public PspInfoDto pspId(String pspId) {
        this.pspId = pspId;
        return this;
    }

    public String getPspId() {
        return pspId;
    }

    public void setPspId(String pspId) {
        this.pspId = pspId;
    }

    public PspInfoDto businessName(String businessName) {
        this.businessName = businessName;
        return this;
    }


    public String getBusinessName() {
        return businessName;
    }

    public void setBusinessName(String businessName) {
        this.businessName = businessName;
    }

    public PspInfoDto idChannel(String idChannel) {
        this.idChannel = idChannel;
        return this;
    }

    public String getIdChannel() {
        return idChannel;
    }

    public void setIdChannel(String idChannel) {
        this.idChannel = idChannel;
    }

}
