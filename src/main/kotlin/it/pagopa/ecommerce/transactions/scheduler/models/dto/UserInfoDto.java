package it.pagopa.ecommerce.transactions.scheduler.models.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

public class UserInfoDto {

    @JsonProperty("userFiscalCode")
    private String userFiscalCode;

    @JsonProperty("notificationEmail")
    private String notificationEmail;

    @JsonProperty("surname")
    private String surname;

    @JsonProperty("name")
    private String name;

    @JsonProperty("username")
    private String username;

    @JsonProperty("authenticationType")
    private String authenticationType;

    public UserInfoDto userFiscalCode(String userFiscalCode) {
        this.userFiscalCode = userFiscalCode;
        return this;
    }

    public String getUserFiscalCode() {
        return userFiscalCode;
    }

    public void setUserFiscalCode(String userFiscalCode) {
        this.userFiscalCode = userFiscalCode;
    }

    public UserInfoDto notificationEmail(String notificationEmail) {
        this.notificationEmail = notificationEmail;
        return this;
    }

    public String getNotificationEmail() {
        return notificationEmail;
    }

    public void setNotificationEmail(String notificationEmail) {
        this.notificationEmail = notificationEmail;
    }

    public UserInfoDto surname(String surname) {
        this.surname = surname;
        return this;
    }

    public String getSurname() {
        return surname;
    }

    public void setSurname(String surname) {
        this.surname = surname;
    }

    public UserInfoDto name(String name) {
        this.name = name;
        return this;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UserInfoDto username(String username) {
        this.username = username;
        return this;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public UserInfoDto authenticationType(String authenticationType) {
        this.authenticationType = authenticationType;
        return this;
    }

    public String getAuthenticationType() {
        return authenticationType;
    }

    public void setAuthenticationType(String authenticationType) {
        this.authenticationType = authenticationType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        UserInfoDto userInfo = (UserInfoDto) o;
        return Objects.equals(this.userFiscalCode, userInfo.userFiscalCode) &&
                Objects.equals(this.notificationEmail, userInfo.notificationEmail) &&
                Objects.equals(this.surname, userInfo.surname) &&
                Objects.equals(this.name, userInfo.name) &&
                Objects.equals(this.username, userInfo.username) &&
                Objects.equals(this.authenticationType, userInfo.authenticationType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userFiscalCode, notificationEmail, surname, name, username, authenticationType);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("class UserInfoDto {\n");
        sb.append("    userFiscalCode: ").append(toIndentedString(userFiscalCode)).append("\n");
        sb.append("    notificationEmail: ").append(toIndentedString(notificationEmail)).append("\n");
        sb.append("    surname: ").append(toIndentedString(surname)).append("\n");
        sb.append("    name: ").append(toIndentedString(name)).append("\n");
        sb.append("    username: ").append(toIndentedString(username)).append("\n");
        sb.append("    authenticationType: ").append(toIndentedString(authenticationType)).append("\n");
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
