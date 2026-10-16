package com.ayush.chat.admin.tenant;

import com.ayush.chat.chat.webhook.WebhookAdminService;
import com.ayush.chat.chat.webhook.WebhookViews.DeliveryView;
import com.ayush.chat.chat.webhook.WebhookViews.WebhookInput;
import com.ayush.chat.chat.webhook.WebhookViews.WebhookView;
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
@RequestMapping("/api/admin/tenants/{slug}/webhooks")
@RequiredArgsConstructor
public class TenantWebhookController {

    private final WebhookAdminService webhooks;

    @GetMapping
    public List<WebhookView> list(@PathVariable String slug) {
        return webhooks.list(slug);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WebhookView create(@PathVariable String slug, @RequestBody WebhookInput input) {
        return webhooks.create(slug, input);
    }

    @PutMapping("/{id}")
    public WebhookView update(@PathVariable String slug, @PathVariable Long id, @RequestBody WebhookInput input) {
        return webhooks.update(slug, id, input);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String slug, @PathVariable Long id) {
        webhooks.delete(slug, id);
    }

    @PostMapping("/{id}/test")
    public DeliveryView test(@PathVariable String slug, @PathVariable Long id) {
        return webhooks.test(slug, id);
    }

    @GetMapping("/{id}/deliveries")
    public List<DeliveryView> deliveries(@PathVariable String slug, @PathVariable Long id) {
        return webhooks.deliveries(slug, id);
    }
}
