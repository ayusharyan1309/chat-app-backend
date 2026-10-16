package com.ayush.chat.audit;

/**
 * Implemented by authenticated principals so the audit log can name who acted without depending
 * on the authentication module.
 */
public interface AuditActor {

    /** e.g. an admin's email or {@code api-key:<name>}. */
    String auditName();

    /** ADMIN, API_KEY, ... */
    String auditType();
}
