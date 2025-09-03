package com.ayush.chat.service;

import com.ayush.chat.model.User;

public interface UserService {
    public User getUserByUid(String uid);
}