package com.ayush.chat.exception;

public class MobileAlreadyExistException extends Exception {

    private static MobileAlreadyExistException mobileAlreadyExistException = null;
    private MobileAlreadyExistException(){

    }
    public static MobileAlreadyExistException getInstance(){
        if(mobileAlreadyExistException==null){
            mobileAlreadyExistException=new MobileAlreadyExistException();
        }
        return mobileAlreadyExistException;
    }
}
