package com.ayush.chat.service;

import com.ayush.chat.model.logger.RequestLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@Slf4j
public class STExecutorService {
/*
  @Autowired
  private MerchantService merchantService;
*/

/*
  @Autowired
  private OtherServices otherServices;
*/

    @Autowired
    private LoggerService loggerService;


/*
  public void executeMerchantDetailAsync(Integer tid, String phone, String name, boolean isCreateMerchantTransactionDetails){
    try {
      ExecutorService executorService = ExecutorUtil.getExecutorService();
      Callable<Boolean> callable = new Callable<Boolean>() {
        @Override
        public Boolean call() throws Exception {
          boolean isTaskDone = false;
          merchantService.saveMerchant(tid,name);
          if(isCreateMerchantTransactionDetails){
            merchantService.saveMerchantTransactionDetail(tid,phone);
          }
          return isTaskDone=true;
        }
      };
      FutureTask<Boolean> futureTask = new FutureTask<>(callable);
      executorService.submit(futureTask);
    }catch (Exception e){
      log.error("exception in ivr executor service merchant details save:",e);
      Sentry.captureException(e);
    }
    }
*/

    /*
      public void executeCheckUserAsync(String phone, String called,String callId){
        try {
          ExecutorService executorService = ExecutorUtil.getExecutorService();
          Callable<Boolean> callable = new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
              otherServices.checkUser(phone,called,callId);
              return true;
            }
          };
          FutureTask<Boolean> futureTask = new FutureTask<>(callable);
          executorService.submit(futureTask);
        }catch (Exception e){
          log.error("exception in ivr executor service check user :",e);
          Sentry.captureException(e);
        }
      }
    */
/*  public void executeSendSlackAsync(RequestLogger requestLogger){
    try {
      java.util.concurrent.ExecutorService executorService = ExecutorUtil.getExecutorService();
      Callable<Boolean> callable = new Callable<Boolean>() {
        @Override
        public Boolean call() throws Exception {
          //loggerService.sendExceptionToSlack(requestLogger);
          return true;
        }
      };
      FutureTask<Boolean> futureTask = new FutureTask<>(callable);
      executorService.submit(futureTask);
    }catch (Exception e){
      log.error("exception in ivr executor service send exception to slack :",e);
      //Sentry.captureException(e);
    }
  }*/
    @Async("asyncExecutor")
    public void executeUpdateReqLoggerAsync(RequestLogger requestLogger, Map<String, Object> req, String response,
                                            boolean isSuccess, String responseCode, Long responseTime,
                                            Exception exception, String contentId) {

        log.info("################################ async req call start");
        try {
            loggerService.updateRequestLogger(requestLogger, req, response, isSuccess, responseCode, responseTime, exception, contentId);
        } catch (Exception e) {
            log.error("exception in ivr executor service req logger :", e);
            //Sentry.captureException(e);
        } finally {
            log.info("################################ async req call end");
        }
    }

/*public void executeUpdateReqLoggerAsync(RequestLogger requestLogger,Map<String,Object> req,String response,
                                        boolean isSuccess,String responseCode, Long responseTime,
                                        Exception exception,String contentId){
  try {
    java.util.concurrent.ExecutorService executorService = ExecutorUtil.getExecutorService();
    Callable<Boolean> callable = new Callable<Boolean>() {
      @Override
      public Boolean call() throws Exception {
        loggerService.updateRequestLogger(requestLogger,req,response,isSuccess,responseCode,responseTime,exception,contentId);
        return true;
      }
    };
    FutureTask<Boolean> futureTask = new FutureTask<>(callable);
    executorService.submit(futureTask);
  }catch (Exception e){
    log.error("exception in ivr executor service req logger :",e);
    //Sentry.captureException(e);
  }
}*/


/*  public void executeUpdateSubReqLoggerAsync(Map<String, Object> req,String responseCode,Map<String,Object> response,
                                             boolean isSuccess,String ivrPartner,Long responseTime,String reqUrl,
                                             Exception exception,String serviceType,RequestLogger requestLogger,
                                             String transactionIdKey,String transactionIdVal,String serviceName,
                                             Integer httpCode,String errorMsg,Integer platformId,Integer pspClientId,
                                             Integer userId){
    try {
      java.util.concurrent.ExecutorService executorService = ExecutorUtil.getExecutorService();
      Callable<Boolean> callable = new Callable<Boolean>() {
        @Override
        public Boolean call() throws Exception {
          loggerService.createSubReqLogger(req,responseCode,response,isSuccess,ivrPartner,responseTime,
                  reqUrl,exception,serviceType,requestLogger,transactionIdKey,transactionIdVal, serviceName,
                  httpCode, errorMsg,platformId,pspClientId,userId);
          return true;
        }
      };
      FutureTask<Boolean> futureTask = new FutureTask<>(callable);
      executorService.submit(futureTask);
    }catch (Exception e){
      log.error("exception in ivr executor service sub req logger :",e);
      Sentry.captureException(e);
    }
  }*/
}
