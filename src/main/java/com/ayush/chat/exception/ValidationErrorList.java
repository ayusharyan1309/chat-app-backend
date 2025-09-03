package com.ayush.chat.exception;
 /* 
 Created by Shubham Kumar on 09/03/22 
 */

import java.util.List;

public class ValidationErrorList {
    private List<ValidationError> errors;

    public ValidationErrorList(List<ValidationError> errors) {
        this.errors = errors;
    }
}
