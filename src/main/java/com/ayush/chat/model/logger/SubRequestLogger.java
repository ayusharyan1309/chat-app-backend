package com.ayush.chat.model.logger;/*
package com.app.spicetrade.model.logger;

import com.vladmihalcea.hibernate.type.json.JsonType;
import lombok.Data;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;

import javax.persistence.*;
import java.sql.Timestamp;

@Data
//@AllArgsConstructor
//@NoArgsConstructor
@Entity
@Table(name="sub_request_logger")
//@TypeDef(name = "jsonb", typeClass = JsonBinaryType.class) // for postgres
//@TypeDef(name = "json", typeClass = JsonStringType.class) // for mysql
@TypeDef(name = "jsonObject", typeClass = JsonType.class) // worked for postgres and mysql both
public class SubRequestLogger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "transaction_id_key")
    private String transactionIdKey;

    @Column(name = "transaction_id_val")
    private String transactionIdVal; // seq number

    @Basic
    @Column(name = "request_logger_id")
    private Integer requestLoggerId;
    @Basic
    @Column(name = "path")
    private String path;


    @Column(name = "platform_name")
    private String platformName; // payment, billfetch, getBalance etc

    @Column(name = "psp_client_name")
    private String pspClientName; // nsdl ,axis, airtel, idfc,pnb etc

    @Column(name = "platform_type")
    private String platformType; //asi, bbps, upi etc

    @Column(name = "psp_client_id")
    private Integer pspClientId;

    @Column(name = "platform_id")
    private Integer platformId;
    @Basic
    @Column(name = "exception")
    private String exception;

    //@Basic
    @Type(type = "jsonObject")
    @Column(columnDefinition = "jsonObject")
    //@Column(name = "request")
    private Object request;

    @Basic
    @Type(type = "jsonObject")
    @Column(columnDefinition = "jsonObject")
    //@Column(name = "response")
    private Object response;

    @Basic
    @Column(name = "response_time")
    private Integer responseTime;

    @Basic
    @Column(name = "success_status")
    private Boolean successStatus;

    @Basic
    @Column(name = "response_code")
    private String responseCode;

    @Basic
    @Column(name = "http_status_code")
    private Integer httpStatusCode;

    @Basic
    @Column(name = "response_message")
    private String responseMessage;

    @Basic
    @Column(name = "created_at" */
/*,columnDefinition= "TIMESTAMP WITH OUT TIME ZONE"*//*
)
    private Timestamp createdAt;

    @Basic
    @Column(name = "updated_at")
    private Timestamp updatedAt;

    @Column(name = "user_id")
    private Integer userId;

    public Integer getUserId() {
        return userId;
    }

    public void setUserId(Integer userId) {
        this.userId = userId;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Integer getRequestLoggerId() {
        return requestLoggerId;
    }

    public void setRequestLoggerId(Integer requestLoggerId) {
        this.requestLoggerId = requestLoggerId;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getException() {
        return exception;
    }

    public void setException(String exception) {
        this.exception = exception;
    }

    public Object getRequest() {
        return request;
    }

    public void setRequest(Object request) {
        this.request = request;
    }

    public Object getResponse() {
        return response;
    }

    public void setResponse(Object response) {
        this.response = response;
    }

    public Integer getResponseTime() {
        return responseTime;
    }

    public void setResponseTime(Integer responseTime) {
        this.responseTime = responseTime;
    }

    public Boolean getSuccessStatus() {
        return successStatus;
    }

    public void setSuccessStatus(Boolean successStatus) {
        this.successStatus = successStatus;
    }

    public String getResponseCode() {
        return responseCode;
    }

    public void setResponseCode(String responseCode) {
        this.responseCode = responseCode;
    }

    public String getResponseMessage() {
        return responseMessage;
    }

    public void setResponseMessage(String responseMessage) {
        this.responseMessage = responseMessage;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public Timestamp getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Timestamp updatedAt) {
        this.updatedAt = updatedAt;
    }

*/
/*
    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }
*//*


    public Integer getHttpStatusCode() {
        return httpStatusCode;
    }

    public void setHttpStatusCode(Integer httpStatusCode) {
        this.httpStatusCode = httpStatusCode;
    }
}
*/
