package com.ayush.chat.exception;

public class UserAlreadyExistException extends Exception {
    private static UserAlreadyExistException userAlreadyExistException = null;
    private UserAlreadyExistException(){

    }
    public static UserAlreadyExistException getInstance(){
        if(userAlreadyExistException==null){
            userAlreadyExistException=new UserAlreadyExistException();
        }
        return userAlreadyExistException;
    }
}
