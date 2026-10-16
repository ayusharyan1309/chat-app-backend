package com.ayush.chat.admin.controller;

import com.ayush.chat.audit.dto.AuditEntryView;
import com.ayush.chat.audit.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService audit;

    @GetMapping
    public List<AuditEntryView> list(@RequestParam(required = false) String tenant,
                                 @RequestParam(required = false) Long before,
                                 @RequestParam(defaultValue = "100") int limit) {
        return audit.list(tenant, before, limit);
    }
}
