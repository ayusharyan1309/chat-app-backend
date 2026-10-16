package com.ayush.chat.chat.dto.response;

import com.ayush.chat.blob.BlobStore;

import java.util.Optional;

/** Either a redirect to presigned storage or the object to stream. */
public record MediaDownload(Optional<String> redirect, BlobStore.BlobObject object) {
}
