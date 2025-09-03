package com.ayush.chat.model;

import jakarta.annotation.Resource;
import jakarta.persistence.*;
import lombok.Data;

import java.io.Serializable;
import java.sql.Timestamp;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "user")
public class User implements Serializable{
    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "uid")
    private String uid;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "full_name")
    private String fullName;

    //@NotEmpty(message = "Email can not be empty")
    //@Email(message = "Please provide a valid email id")
    @Column(name = "email",nullable = false,unique = true)
    private String email;

    //@NotEmpty(message = "Mobile can not be empty")
    @Column(name = "mobile",nullable = false,unique = true)
    private String mobile;

    @Column(name = "password")
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type")
    private Resource.AuthenticationType authType;

    @Column(name = "status")
    private String status;

    @Column(name = "profile_url")
    private String profileUrl;

    @Column(name = "city")
    private String city;

    @Column(name = "state")
    private String state;

    @Column(name = "country")
    private String country;

    @Column(name = "gender")
    private String gender;

    @Column(name = "bio")
    private String bio;

    @Column(name = "created_at",updatable = false)
    private Timestamp createdAt;

    @Column(name = "updated_at")
    private Timestamp updatedAt;

    @Column(name = "last_seen")
    private LocalDateTime lastSeen;

}
