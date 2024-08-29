package it.pagopa.ecommerce.transactions.scheduler.models.dto;
import com.fasterxml.jackson.annotation.JsonProperty;


public class TransactionResultDto {

    @JsonProperty("userInfo")
    private UserInfoDto userInfo;

    @JsonProperty("transactionInfo")
    private TransactionInfoDto transactionInfo;

    @JsonProperty("paymentInfo")
    private PaymentInfoDto paymentInfo;

    @JsonProperty("pspInfo")
    private PspInfoDto pspInfo;

    @JsonProperty("product")
    private ProductDto product;

    public TransactionResultDto userInfo(UserInfoDto userInfo) {
        this.userInfo = userInfo;
        return this;
    }

    public UserInfoDto getUserInfo() {
        return userInfo;
    }

    public void setUserInfo(UserInfoDto userInfo) {
        this.userInfo = userInfo;
    }

    public TransactionResultDto transactionInfo(TransactionInfoDto transactionInfo) {
        this.transactionInfo = transactionInfo;
        return this;
    }

    public TransactionInfoDto getTransactionInfo() {
        return transactionInfo;
    }

    public void setTransactionInfo(TransactionInfoDto transactionInfo) {
        this.transactionInfo = transactionInfo;
    }

    public TransactionResultDto paymentInfo(PaymentInfoDto paymentInfo) {
        this.paymentInfo = paymentInfo;
        return this;
    }

    public PaymentInfoDto getPaymentInfo() {
        return paymentInfo;
    }

    public void setPaymentInfo(PaymentInfoDto paymentInfo) {
        this.paymentInfo = paymentInfo;
    }

    public TransactionResultDto pspInfo(PspInfoDto pspInfo) {
        this.pspInfo = pspInfo;
        return this;
    }

    public PspInfoDto getPspInfo() {
        return pspInfo;
    }

    public void setPspInfo(PspInfoDto pspInfo) {
        this.pspInfo = pspInfo;
    }

    public TransactionResultDto product(ProductDto product) {
        this.product = product;
        return this;
    }

    public ProductDto getProduct() {
        return product;
    }

    public void setProduct(ProductDto product) {
        this.product = product;
    }
}

