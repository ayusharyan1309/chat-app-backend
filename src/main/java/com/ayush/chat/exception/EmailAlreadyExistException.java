package com.ayush.chat.exception;

public class EmailAlreadyExistException extends Exception{
    private static EmailAlreadyExistException emailAlreadyExistException = null;
    private EmailAlreadyExistException(){

    }
    public static EmailAlreadyExistException getInstance(){
        if(emailAlreadyExistException==null){
            emailAlreadyExistException=new EmailAlreadyExistException();
        }
        return emailAlreadyExistException;
    }
}
