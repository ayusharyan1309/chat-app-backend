package com.ayush.chat.chat.dto.response;

public record MediaView(String mediaId, String url, String mimeType, long size, String fileName) {
}
