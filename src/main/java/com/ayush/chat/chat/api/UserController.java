package com.ayush.chat.chat.api;

import com.ayush.chat.auth.Principals.ChatUserPrincipal;
import com.ayush.chat.chat.ChatService;
import com.ayush.chat.chat.Views.UserView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The signed-in user's profile, devices, user directory and blocks.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class UserController {

    private final ChatService chat;

    public record ProfileUpdate(String displayName, String avatarUrl, String about) {
    }

    public record DeviceRequest(String token) {
    }

    @GetMapping("/me")
    public UserView me(@AuthenticationPrincipal ChatUserPrincipal me) {
        return chat.getUser(me.tenant(), me.userId());
    }

    @PatchMapping("/me")
    public UserView updateMe(@AuthenticationPrincipal ChatUserPrincipal me, @RequestBody ProfileUpdate update) {
        return chat.updateProfile(me.tenant(), me.userId(), update.displayName(), update.avatarUrl(), update.about());
    }

    @PostMapping("/me/devices")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addDevice(@AuthenticationPrincipal ChatUserPrincipal me, @RequestBody DeviceRequest request) {
        chat.addDevice(me.tenant(), me.userId(), request.token());
    }

    @DeleteMapping("/me/devices/{token}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeDevice(@AuthenticationPrincipal ChatUserPrincipal me, @PathVariable String token) {
        chat.removeDevices(me.tenant(), me.userId(), List.of(token));
    }

    @GetMapping("/users")
    public List<UserView> search(@AuthenticationPrincipal ChatUserPrincipal me, @RequestParam(required = false) String q,
                                 @RequestParam(defaultValue = "20") int limit) {
        return chat.searchUsers(me.tenant(), me.userId(), q, 0, limit, false);
    }

    @GetMapping("/users/{id}")
    public UserView user(@AuthenticationPrincipal ChatUserPrincipal me, @PathVariable String id) {
        return chat.getUser(me.tenant(), id);
    }

    @GetMapping("/blocks")
    public List<String> blocks(@AuthenticationPrincipal ChatUserPrincipal me) {
        return chat.listBlocked(me.tenant(), me.userId());
    }

    @PutMapping("/blocks/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void block(@AuthenticationPrincipal ChatUserPrincipal me, @PathVariable String userId) {
        chat.setBlocked(me.tenant(), me.userId(), userId, true);
    }

    @DeleteMapping("/blocks/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unblock(@AuthenticationPrincipal ChatUserPrincipal me, @PathVariable String userId) {
        chat.setBlocked(me.tenant(), me.userId(), userId, false);
    }
}
