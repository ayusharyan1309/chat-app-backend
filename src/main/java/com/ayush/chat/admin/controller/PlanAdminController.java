package com.ayush.chat.admin.controller;

import com.ayush.chat.tenant.dto.PlanRequest;
import com.ayush.chat.tenant.dto.PlanView;
import com.ayush.chat.tenant.service.PlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/plans")
@RequiredArgsConstructor
public class PlanAdminController {

    private final PlanService plans;

    @GetMapping
    public List<PlanView> list() {
        return plans.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlanView create(@RequestBody PlanRequest input) {
        return plans.create(input);
    }

    @PutMapping("/{code}")
    public PlanView update(@PathVariable String code, @RequestBody PlanRequest input) {
        return plans.update(code, input);
    }

    @DeleteMapping("/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String code) {
        plans.delete(code);
    }
}
