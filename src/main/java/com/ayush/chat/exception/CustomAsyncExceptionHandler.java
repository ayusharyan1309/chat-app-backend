package com.ayush.chat.exception;
 /* 
 Created by Shubham Kumar on 31/12/21 
 */

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;

import java.lang.reflect.Method;

public class CustomAsyncExceptionHandler implements AsyncUncaughtExceptionHandler {
    private static final Logger logger = LoggerFactory.getLogger(CustomAsyncExceptionHandler.class);

    @Override
    public void handleUncaughtException(
            Throwable throwable, Method method, Object... obj) {

        System.out.println("Exception message - " + throwable.getMessage());
        logger.info("Exception message - " + throwable.getMessage());
        System.out.println("Method name - " + method.getName());
        logger.info("Method name - " + method.getName());
        for (Object param : obj) {
            System.out.println("Parameter value - " + param);
            logger.info("Parameter value - " + param);
        }
    }
}
