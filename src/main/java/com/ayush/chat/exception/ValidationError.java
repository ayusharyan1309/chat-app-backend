package com.ayush.chat.exception;
 /* 
 Created by Shubham Kumar on 09/03/22 
 */

import java.util.Map;

public class ValidationError {
    private String code;
    private String message;
    private Map<String, Boolean> params;

    public ValidationError(String code, String message, Map<String, Boolean> params) {
        this.code = code;
        this.message = message;
        this.params = params;
    }
}
