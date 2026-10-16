package com.ayush.chat.chat.dto.command;

import java.io.IOException;
import java.io.InputStream;

public record MediaUpload(String fileName, String contentType, long size, Content content) {

    /** The bytes of an upload; opened once, after all checks pass. */
    @FunctionalInterface
    public interface Content {
        InputStream open() throws IOException;
    }
}
