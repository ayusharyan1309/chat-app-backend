package com.ayush.chat.util;

import com.ayush.chat.model.logger.RequestLogger;
import com.google.firebase.auth.FirebaseToken;
import org.springframework.stereotype.Service;



@Service
public class ThreadMemory {

    private static final ThreadLocal<RequestLogger> requestLogger = new ThreadLocal<>();
    private static final ThreadLocal<String> mobileNo = new ThreadLocal<>();
    private static final ThreadLocal<FirebaseToken> fireBaseTokenData = new ThreadLocal<>();
    //private static final ThreadLocal<Users> user = new ThreadLocal<>();

/*
    private static final ThreadLocal<PaymentTransaction> paymentTransaction = new ThreadLocal<>();
*/


    public static RequestLogger getRequestLogger(){
        return requestLogger.get();
    }

    public static void setRequestLogger (RequestLogger requestLog){
        requestLogger.set(requestLog);
    }
    public static FirebaseToken getFireBaseTokenData(){
        return fireBaseTokenData.get();
    }

    public static void setFireBaseTokenData (FirebaseToken fireBaseToken){
        fireBaseTokenData.set(fireBaseToken);
    }



    public static void clear() {
        requestLogger.remove();
        mobileNo.remove();
        fireBaseTokenData.remove();
        //user.remove();

    }
}
