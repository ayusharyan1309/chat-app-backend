package com.ayush.chat.exception;

import lombok.Data;

import java.util.Date;

@Data
public class Error {
    private Date timestamp;
    private Integer code;
    private String status;

    private boolean success;
    private String message;

    private String exception;

    public Error(Date timestamp, Integer code, String status, String message) {
        super();
        this.timestamp = timestamp;
        this.code = code;
        this.status = status;
        this.message = message;
    }
    public Error(Date timestamp, Integer code, String status, String message,String exception,boolean success) {
        super();
        this.timestamp = timestamp;
        this.code = code;
        this.status = status;
        this.message = message;
        this.exception = exception;
        this.success = success;
    }
}
