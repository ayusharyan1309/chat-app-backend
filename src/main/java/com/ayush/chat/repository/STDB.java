package com.ayush.chat.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@Slf4j
@Repository
public class STDB {

    @Autowired
    @Qualifier("entityManagerFactory")
    private EntityManager entityManager;


    public List<?> getAllObjectsForSQLQuery(String sqlQuery, Class entity) {
        Query query = entityManager.createNativeQuery(sqlQuery,entity);
        List<?> list= query.getResultList();
        if (list==null){
            list = new ArrayList<>();
        }
        return list;
    }

    public List<Object> getAllObjectsForSQLQuery(String sqlQuery) {
        Query query = entityManager.createNativeQuery(sqlQuery);
        List<Object> list= query.getResultList();
        if (list==null){
            list = new ArrayList<>();
        }
        return list;
    }

    public  List<?> getResultList(String sql, Class entity){
        Query query = entityManager.createNativeQuery(sql,entity);
        return query.getResultList();
    }

    public  List<Object> getResultList(String sql){
        Query query = entityManager.createNativeQuery(sql);
        return query.getResultList();
    }

    public  Object getSingleResult(String sql, Class entity){
        Query query = entityManager.createNativeQuery(sql,entity);
        return query.getResultList().stream().findFirst().orElse(null);
    }

    public  Object getSingleResult(String sql){
        Query query = entityManager.createNativeQuery(sql);
        return query.getResultList().stream().findFirst().orElse(null);
    }

    public  List<?> getResultList(String sql, Class entity, Map<String,Object> map){
        Query query = entityManager.createNativeQuery(sql,entity);
        for (Map.Entry<String,Object> entry:map.entrySet()){
            query.setParameter(entry.getKey(),entry.getValue());
        }
        return query.getResultList();
    }

    public  List<?> getResultList(String sql, Map<String,Object> map){
        Query query = entityManager.createNativeQuery(sql);
        for (Map.Entry<String,Object> entry:map.entrySet()){
            query.setParameter(entry.getKey(),entry.getValue());
        }
        return query.getResultList();
    }

    public  Object getSingleResult(String sql, Class entity,Map<String,Object> map){
        Query query = entityManager.createNativeQuery(sql,entity);
        for (Map.Entry<String,Object> entry:map.entrySet()){
            query.setParameter(entry.getKey(),entry.getValue());
        }
        return query.getResultList().stream().findFirst().orElse(null);
    }

    public  List<Integer> getForIntegers(String sql){
        Query query = entityManager.createNativeQuery(sql);
        return query.getResultList();
    }

    public  List<Integer> getForIntegers(String sql,Map<String,Object> map){
        Query query = entityManager.createNativeQuery(sql);
        for (Map.Entry<String,Object> entry:map.entrySet()){
            query.setParameter(entry.getKey(),entry.getValue());
        }
        return query.getResultList();
    }

    public  List<BigInteger> getForBigIntegers(String sql,Map<String,Object> map){
        Query query = entityManager.createNativeQuery(sql);
        for (Map.Entry<String,Object> entry:map.entrySet()){
            query.setParameter(entry.getKey(),entry.getValue());
        }
        return query.getResultList();
    }

    public  List<String> getListForPrimitives(String sql){
        Query query = entityManager.createNativeQuery(sql);
        return query.getResultList();
    }

    public  List<String> getListForPrimitives(String sql,Map<String,Object> map){
        Query query = entityManager.createNativeQuery(sql);
        for (Map.Entry<String,Object> entry:map.entrySet()){
            query.setParameter(entry.getKey(),entry.getValue());
        }
        return query.getResultList();
    }

    public  String getForPrimitive(String sql,Map<String,Object> map){
        String str =null;
        Query query = entityManager.createNativeQuery(sql);
        for (Map.Entry<String,Object> entry:map.entrySet()){
            query.setParameter(entry.getKey(),entry.getValue());
        }
        List o = query.getResultList();//query.getResultStream().findFirst();//
        if(o!=null && o.size()>0){
            Object o1 = o.get(0);
            if(o1!=null){
                str = o1.toString();
            }
        }
        return str;
    }
    public  Integer getForCount(String sql){
        Query query = entityManager.createNativeQuery(sql);
        BigInteger count = (BigInteger) query.getSingleResult();
        return count.intValue();
    }
    public  Integer getForCount(String sql,Map<String,Object> map){
        Query query = entityManager.createNativeQuery(sql);
        for (Map.Entry<String,Object> entry:map.entrySet()){
            query.setParameter(entry.getKey(),entry.getValue());
        }
        BigInteger count = (BigInteger) query.getSingleResult();
        return count.intValue();
    }

    public  Double getForSum(String sql){
        Double sum =0.0;
        Query query = entityManager.createNativeQuery(sql);
        BigDecimal count = (BigDecimal) query.getSingleResult();
        if(count==null){
            return sum;
        }
        return sum =count.doubleValue();
    }
    public  Double getForSum(String sql,Map<String,Object> map){
        Double sum =0.0;
        Query query = entityManager.createNativeQuery(sql);
        for (Map.Entry<String,Object> entry:map.entrySet()){
            query.setParameter(entry.getKey(),entry.getValue());
        }
        BigDecimal count = (BigDecimal) query.getSingleResult();
        if(count==null){
            return sum;
        }
        return sum =count.doubleValue();
    }
    @Transactional
    public  Integer update(String sql){
        Query query = entityManager.createNativeQuery(sql);
        return query.executeUpdate();
    }
    @Transactional
    public  Integer update(String sql,Map<String,Object> param){
        Query query = entityManager.createNativeQuery(sql);
        for (Map.Entry<String,Object> entry:param.entrySet()){
            query.setParameter(entry.getKey(),entry.getValue());
        }
        return query.executeUpdate();
    }

    @Transactional(readOnly = false)
    public Object save(Object o){
        try {
            entityManager.persist(o);
        }catch (Exception e){
            log.error("Exception in save ",e);
            //Sentry.captureException(e);
        }
        return o;
    }

    @Transactional(readOnly = false)
    public Object update(Object o){
        try {
            entityManager.merge(o);
        }catch (Exception e){
            log.error("Exception in save ",e);
            //Sentry.captureException(e);
        }
        return o;
    }

    public Object find(Integer id,Class entity) {
        try {
            return entityManager.find(entity, id);
        }catch (Exception e){
            log.error("Exception in find ",e);
            //Sentry.captureException(e);
        }
        return null;
    }
    public Object find(Long id,Class entity) {
        try {
            return entityManager.find(entity, id);
        }catch (Exception e){
            log.error("Exception in find ",e);
            //Sentry.captureException(e);
        }
        return null;
    }
/*
    @Query("SELECT u FROM User u WHERE u.status = ?1 and u.name = ?2")
    User findUserByStatusAndName(Integer status, String name);*/
/*    @Query(
            value = "SELECT * FROM USERS u WHERE u.status = 1",
            nativeQuery = true)
    Collection<User> findAllActiveUsersNative();*/

    // multiple table get
/*    @Query(
            value = "SELECT p.*, c.*, s.*, d.* from patient p, consult c ,script s,dispense d "
                    + " where p.patient_id=c.patient_id "
                    + " and c.consult_id = d.consult_id "
                    + " and c.fk_script_id =s.script_id"
                    + " and c.consult_id=?1 ",
            nativeQuery = true
    )
    List<Map<String, Object>>  findInvoiceByConsultId(Long consultId);*/

    // join table example
    //https://www.baeldung.com/spring-data-jpa-query
}
