package com.ayush.chat.chat.api;

import com.ayush.chat.auth.Principals.ChatUserPrincipal;
import com.ayush.chat.chat.MediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.time.Duration;

@RestController
@RequestMapping("/api/v1/media")
@RequiredArgsConstructor
public class MediaController {

    private final MediaService media;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public MediaService.MediaView upload(@AuthenticationPrincipal ChatUserPrincipal me, @RequestPart("file") MultipartFile file) {
        return media.upload(me.tenant(), file);
    }

    /** Public, signature-protected download (works in {@code <img src>}). */
    @GetMapping("/{tenant}/{mediaId}")
    public ResponseEntity<?> download(@PathVariable String tenant, @PathVariable String mediaId,
                                      @RequestParam(required = false) String sig) {
        MediaService.Download download = media.download(tenant, mediaId, sig);
        if (download.redirect().isPresent()) {
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(download.redirect().get())).build();
        }
        boolean inline = MediaService.isInlineSafe(download.object().contentType());
        return ResponseEntity.ok()
                .contentType(inline ? MediaType.parseMediaType(download.object().contentType()) : MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(download.object().size())
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate().immutable())
                .header("X-Content-Type-Options", "nosniff")
                // Uploads share the API/dashboard origin: never let one execute script there.
                .header("Content-Security-Policy", "sandbox; default-src 'none'; img-src 'self'; media-src 'self'")
                .header(HttpHeaders.CONTENT_DISPOSITION, inline ? "inline" : "attachment")
                .body(new InputStreamResource(download.object().data()));
    }
}
