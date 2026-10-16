package com.ayush.chat.core.dto;

import java.time.Instant;

/**
 * The error envelope of every failed REST call:
 * {@code {status, error, message, path, timestamp}}, where {@code error} is a stable
 * {@link com.ayush.chat.core.exception.ErrorCode} id clients can switch on.
 */
public record ErrorResponse(int status, String error, String message, String path, Instant timestamp) {
}
