package com.ayush.chat.service;

import com.ayush.chat.model.logger.RequestLogger;
import com.ayush.chat.repository.STDB;
import com.ayush.chat.util.CommonUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;

@Service
public class LoggerService {
    private static final Logger logger = LoggerFactory.getLogger(LoggerService.class);
    private static final Logger api = LoggerFactory.getLogger("api");


    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RestTemplate restTemplate;

/*    @Autowired
    private ToneTagIvrDB arbitratorDB;*/

    @Autowired
    private STDB db;


    @Autowired
    private CommonUtil commonUtil;


    //@Transactional
    public RequestLogger saveReqLogger(RequestLogger logger){
        RequestLogger requestLogger = new RequestLogger();
        requestLogger = objectMapper.convertValue(logger,RequestLogger.class);
        return (RequestLogger) db.save(requestLogger);
        //return (RequestLogger) arbitratorDB.save(logger);
    }

/*    public SubRequestLogger saveSubReqLogger(SubRequestLogger subLogger){
        SubRequestLogger subRequestLogger = new SubRequestLogger();
        subRequestLogger = objectMapper.convertValue(subLogger,SubRequestLogger.class);
        return (SubRequestLogger) db.save(subRequestLogger);
        //return (SubRequestLogger) arbitratorDB.save(subLogger);
    }*/

    //@Transactional
    private RequestLogger updateReqLogger(RequestLogger logger){
        if(logger.getId()!=null) {
            return (RequestLogger) db.update(logger);
            //return (RequestLogger) arbitratorDB.update(logger);
        }/*else {
            return saveReqLogger(logger);
        }*/
        return logger;
    }

   /* @Transactional
    public void saveSubReqLogger(SubRequestLogger logger){
        subRequestLoggerRepository.save(logger);
    }
*/
    public RequestLogger getRequestLoggerById(Long id){
        if (id==null){
            return new RequestLogger();
        }
        //return (RequestLogger) arbitratorDB.find(id,RequestLogger.class);
        return (RequestLogger) db.find(id,RequestLogger.class);
    }

    //@Transactional
    public RequestLogger createOrGetRequestLogger(String path, String ip, String httpMethod, String contentId){
        RequestLogger requestLogger = new RequestLogger();
        requestLogger.setPath(path);
        requestLogger.setIpAddress(ip);
        requestLogger.setContentId(contentId);
        requestLogger.setHttpMethod(httpMethod);
        requestLogger.setCreatedAt(new Timestamp(System.currentTimeMillis()));
        requestLogger.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        if ("/api/status".equalsIgnoreCase(path.trim()) || "/favicon.ico".equalsIgnoreCase(path.trim())){
            return requestLogger;
        }
        return saveReqLogger(requestLogger);
    }

    public RequestLogger updateRequestLogger(RequestLogger requestLogger){
        requestLogger.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        return updateReqLogger(requestLogger);
    }

/*    public RequestLogger updateRequestLogger(Object req, Object resp, Boolean successStatus){
        RequestLogger requestLogger = getRequestLoggerById(ThreadMemory.getRequestLogger().getId());
        requestLogger.setResponse(resp*//*new JSONObject(resp)*//*);
        requestLogger.setRequest(req*//*new JSONObject(req)*//*);
        requestLogger.setSuccessStatus(successStatus);
        requestLogger.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        return updateRequestLogger(requestLogger);
    }*/

    public RequestLogger updateRequestLogger(RequestLogger requestLogger, String httpMethod, String ip, Integer responseCode, String exception){
        if(StringUtils.isEmpty(requestLogger.getIpAddress())) {
            requestLogger.setIpAddress(ip);
        }
        if(StringUtils.isEmpty(requestLogger.getHttpMethod())) {
            requestLogger.setHttpMethod(httpMethod);
        }
        if(StringUtils.isEmpty(requestLogger.getResponseCode())) {
            requestLogger.setResponseCode(responseCode.toString());
        }
        if(StringUtils.isEmpty(requestLogger.getException())) {
            requestLogger.setException(exception);
        }
        requestLogger.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        return updateRequestLogger(requestLogger);
    }

/*    private SubRequestLogger createSubRequestLogger(SubRequestLogger subRequestLogger){
        subRequestLogger.setCreatedAt(new Timestamp(System.currentTimeMillis()));
        subRequestLogger.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        return saveSubReqLogger(subRequestLogger);
    }*/

/*    public RequestLogger test(){
        RequestLogger requestLogger = getRequestLoggerById(8);
        return requestLogger;
    }*/
/*    public void sendExceptionToSlack(RequestLogger requestLogger) throws Exception{
        logger.info("###  API::send exception to slack START ########################## ");

        Map<String,Object> map = new LinkedHashMap<>();
        JSONObject request = new JSONObject();
        request.put("ip",requestLogger.getIpAddress());
        request.put("url",requestLogger.getPath());
        request.put("responseCode",requestLogger.getResponseCode());
        request.put("createdAt",requestLogger.getCreatedAt());
        request.put("exception",requestLogger.getException());
        map.put("text",request.toString());
        objectMapper = new ObjectMapper();
        String getSendExceptionReq= objectMapper.writeValueAsString(map);

        HttpEntity<String> getHttpSendExceptionReq = RequestUtil.getHttpRequest(getSendExceptionReq, MediaType.APPLICATION_JSON);
        final ResponseEntity<String> getSendSlackResponse = restTemplate.postForEntity(CustomConfiguration.customConfiguration.getSlackHookEndpoint(),getHttpSendExceptionReq,String.class);
        logger.info("###  API::send exception to slack end ########################## ");
    }*/

/*    public static void printReqLog(Logger log, String msg, String url){
        try {
            if(CustomConfiguration.customConfiguration.isReqRespLogEnable() *//*|| !isCredContainRequest(url)*//*){
                log.info(msg);
            }else {
                log.info("**************** log print off *****************");
            }
        }catch (Exception e){
            logger.error("while logging exception occur in url:"+url,e);
        }
    }*/

/*    public Map<String,Object> check()throws Exception{
        String sql = "select * from sub_request_logger order by id desc limit 1";
        SubRequestLogger subRequestLogger = (SubRequestLogger) IVRDB.getSingleResult(sql,SubRequestLogger.class, db.getEntityManager());
        Map<String,Object> req = (Map<String, Object>) subRequestLogger.getRequest();
        String r = req.get("encBody").toString();
        String dec = aes.decrypt(CustomConfiguration.customConfiguration.getEncryptionReqRespPKey(),CustomConfiguration.customConfiguration.getEncryptionSalt(),r);
        Map<String,Object> map = objectMapper.readValue(dec, HashMap.class);
        return map;
    }*/

    public void updateRequestLogger(RequestLogger requestLogger,Map<String,Object> req, String res,
                                   boolean isSuccess,String responseCode, Long responseTime,
                                 Exception exception,String contentId){
        try {
            Map<String,Object> response = null;
            try {
                response = objectMapper.readValue(res, HashMap.class);
            }catch (Exception e){
                response = new HashMap<>();
                response.put("result_str",res);
            }

            //RequestLogger requestLogger = ThreadMemory.getRequestLogger();
            requestLogger.setRequest(req/*commonUtil.getEncApiReqResp(req*//*request*//*, isSuccess,true)*/);
            //requestLogger.setContentId(contentId);
            requestLogger.setSuccessStatus(isSuccess);
            requestLogger.setResponseCode(responseCode);
            requestLogger.setResponseTime(responseTime.intValue());
            requestLogger.setException(CommonUtil.getStringOfStackTrace(exception));
            if(!"200".equalsIgnoreCase(responseCode)) {
                requestLogger.setResponse(response /*commonUtil.getEncApiReqResp(response, isSuccess, false)*/);
            }
            requestLogger.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
            updateReqLogger(requestLogger);
            //ivrExecutorService.executeUpdateReqLoggerAsync(requestLogger);
        }catch (Exception e){
            logger.error("exception in apiService while calling executor service for sub req logger",e);
        }
    }


/*    // sub req logger
    public void createSubReqLogger(Map<String, Object> request, String responseCode, Map<String,Object> response,
                                   boolean isSuccess, String ivrPartner, Long responseTime, String reqUrl,
                                   Exception exception, String serviceType, RequestLogger requestLogger,
                                   String transactionIdKey, String transactionIdVal, String serviceName,
                                   Integer httpCode, String errorMsg,Integer platformId,Integer pspClientId,Integer userId){
        try {
            //Map<String, Object> request = objectMapper.readValue(req, HashMap.class);
            SubRequestLogger subRequestLogger = new SubRequestLogger();
            subRequestLogger.setRequestLoggerId(requestLogger.getId());
            subRequestLogger.setRequest(commonUtil.getEncApiReqResp(request, isSuccess,true));
            subRequestLogger.setPlatformType(serviceType);
            subRequestLogger.setUserId(userId);
            subRequestLogger.setTransactionIdKey(transactionIdKey);
            subRequestLogger.setTransactionIdVal(transactionIdVal);
            subRequestLogger.setPlatformName(serviceName);
            subRequestLogger.setPath(reqUrl);
            subRequestLogger.setPlatformId(platformId);
            subRequestLogger.setPspClientId(pspClientId);
            subRequestLogger.setPspClientName(ivrPartner);
            subRequestLogger.setSuccessStatus(isSuccess);
            subRequestLogger.setResponseCode(responseCode);
            subRequestLogger.setHttpStatusCode(httpCode);
            subRequestLogger.setResponseMessage(errorMsg);
            subRequestLogger.setResponseTime(responseTime.intValue());
            subRequestLogger.setException(CommonUtil.getStringOfStackTrace(exception));
            if(!isSuccess){
                subRequestLogger.setResponse(response);
            }else {
                subRequestLogger.setResponse(commonUtil.getEncApiReqResp(response,isSuccess,false));
            }
            createSubRequestLogger(subRequestLogger);
        }catch (Exception e){
            logger.error("exception in apiService while calling executor service for sub req logger",e);
        }
    }*/
}
