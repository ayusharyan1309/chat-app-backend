package com.ayush.chat.util;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class CommonUtil {

    private static final Pattern NON_LATIN = Pattern.compile("[^\\w-]");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s]");


    public static boolean validateToken(String idToken) throws FirebaseAuthException {
        FirebaseToken decodedToken = getFirebaseToken(idToken);
        return decodedToken != null;
    }

    public static FirebaseToken getFirebaseToken(String idToken) throws FirebaseAuthException {
        FirebaseToken decodedToken = FirebaseAuth.getInstance().verifyIdToken(idToken);
        return decodedToken;
        /*try {
            FirebaseToken decodedToken = FirebaseAuth.getInstance().verifyIdToken(idToken);
            return decodedToken;
        } catch (FirebaseAuthException e) {
            //e.printStackTrace();
            return null;
        }*/
    }
    public static String getStringOfStackTrace(Exception e){
        if(e==null){
            return null;
        }
        return ExceptionUtils.getStackTrace(e);
    }

    public static Map<String, Object> toMap(JSONObject object) throws JSONException {
        Map<String, Object> map = new HashMap<String, Object>();
        Iterator<String> keysItr = object.keys();
        while(keysItr.hasNext()) {
            String key = keysItr.next();
            Object value = object.get(key);
            if(value instanceof JSONArray) {
                value = toList((JSONArray) value);
            }
            else if(value instanceof JSONObject) {
                value = toMap((JSONObject) value);
            }
            map.put(key, value);
        }
        return map;
    }
    public static List toList(JSONArray array) throws JSONException {
        List list = (List) new ArrayList();
        for(int i = 0; i < array.length(); i++){
            Object value = array.get(i);
            if(value instanceof JSONArray) {
                value = toList((JSONArray) value);
            }
            else if(value instanceof JSONObject) {
                value = toMap((JSONObject) value);
            }
            list.add(value);
        }
        return list;
    }

    public static boolean isEmpty(String data){
        boolean isEmpty = false;
        if (data == null || data.trim().isEmpty()){
            isEmpty = true;
        }
        return isEmpty;
    }

    public static boolean isNotEmpty(String data){
        return !isEmpty(data);
    }

    public static boolean isNotEmpty(List list){
        boolean isEmpty= false;
        if(list!=null && !list.isEmpty()){
            return isEmpty=true;
        }
        return isEmpty;
    }

    public static boolean isEmpty(List list){
        return !isNotEmpty(list);
    }

    public static LocalDateTime getLocalDateFromTimestamp(Timestamp timestamp){
        return timestamp.toLocalDateTime();
    }

    public static LocalDateTime getIncreasedTimestampFromLocalDate(LocalDateTime localDateTime,Integer increasedHour,Integer increasedMinute){
        return localDateTime.plusHours(increasedHour).plusMinutes(increasedMinute);
    }

    public static Timestamp getIncreasedTimestamp(Timestamp timestamp,Integer increasedHour,Integer increasedMinute) {
        LocalDateTime localDateTime = getLocalDateFromTimestamp(timestamp);
        return Timestamp.valueOf(getIncreasedTimestampFromLocalDate(localDateTime,increasedHour,increasedMinute));
    }

    public static String toSlug(String input){
        String noWhitespace = WHITESPACE.matcher(input).replaceAll("-");
        String normalized = Normalizer.normalize(noWhitespace, Normalizer.Form.NFD);
        String slug = NON_LATIN.matcher(normalized).replaceAll("");
        return slug.toUpperCase(Locale.ENGLISH);
    }
}
