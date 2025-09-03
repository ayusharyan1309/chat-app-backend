package com.ayush.chat.model.logger;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.sql.Timestamp;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "request_logger")
public class RequestLogger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "path")
    private String path;

    @Column(name = "http_method")
    private String httpMethod;

    @Column(name = "content_id")
    private String contentId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "exception")
    private String exception;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private Object request;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private Object response;

    @Column(name = "response_time")
    private Integer responseTime;

    @Column(name = "success_status")
    private Boolean successStatus;

    @Column(name = "response_code")
    private String responseCode;

    @Column(name = "created_at")
    private Timestamp createdAt;

    @Column(name = "updated_at")
    private Timestamp updatedAt;

}
