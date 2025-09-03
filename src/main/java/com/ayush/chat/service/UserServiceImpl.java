package com.ayush.chat.service;

import com.ayush.chat.model.User;
import com.ayush.chat.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    public UserServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public User getUserByUid(String uid) {
        return userRepository.findByUId(uid);
    }
}
