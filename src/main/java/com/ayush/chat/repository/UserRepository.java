package com.ayush.chat.repository;

import com.ayush.chat.model.User;
import jakarta.annotation.Resource;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends PagingAndSortingRepository<User, Long> {

    @Query("FROM User WHERE email=:email")
    User findByEmail(@Param("email") String email);

    @Query("FROM User WHERE uid=:uid")
    User findByUId(@Param("uid") String uid);

    @Query("SELECT u FROM User u WHERE u.email = :email")
    public User getUserByUsername(@Param("email") String email);

    @Modifying
    @Query("UPDATE User u SET u.authType = ?2 WHERE u.email = ?1")
    public void updateAuthenticationType(String email, Resource.AuthenticationType authType);

    @Query("FROM User")
    List<User> userList();

    @Query(value = "SELECT * FROM app_user WHERE email = :email", nativeQuery = true)
    Optional<User> findByUserEmail(@Param("email") String email);


}
