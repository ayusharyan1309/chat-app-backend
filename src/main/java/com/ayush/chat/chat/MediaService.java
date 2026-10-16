package com.ayush.chat.chat;

import com.ayush.chat.blob.BlobStore;
import com.ayush.chat.core.config.InfrastructureService;
import com.ayush.chat.core.config.PlatformProperties;
import com.ayush.chat.core.error.ApiException;
import com.ayush.chat.core.util.Ids;
import com.ayush.chat.tenant.Feature;
import com.ayush.chat.tenant.TenantDirectory;
import com.ayush.chat.tenant.TenantSnapshot;
import com.ayush.chat.tenant.UsageService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Media upload and download. Uploaded files get an unguessable id and a signed capability URL
 * ({@code /api/v1/media/{tenant}/{mediaId}?sig=...}) that works in {@code <img src>} without
 * auth headers; when the blob store can presign (S3), downloads redirect straight to storage.
 */
@Service
public class MediaService {

    private static final Pattern MEDIA_ID = Pattern.compile("^[0-9a-f-]{36}(\\.[a-z0-9]{1,8})?$");

    /**
     * Types safe to render inline on the platform origin. Anything else (SVG, HTML, XML, PDF, ...)
     * could carry script and is always served as a download.
     */
    private static final Set<String> INLINE_SAFE = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/avif", "image/heic", "image/heif",
            "video/mp4", "video/webm", "video/quicktime", "video/3gpp",
            "audio/mpeg", "audio/mp4", "audio/aac", "audio/ogg", "audio/webm", "audio/wav", "audio/x-wav", "audio/amr");

    public static boolean isInlineSafe(String contentType) {
        if (contentType == null) {
            return false;
        }
        String base = contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        return INLINE_SAFE.contains(base);
    }

    private final InfrastructureService infrastructure;
    private final TenantDirectory tenants;
    private final UsageService usage;
    private final PlatformProperties properties;
    private final SecretKeySpec signingKey;

    public record MediaView(String mediaId, String url, String mimeType, long size, String fileName) {
    }

    public record Download(Optional<String> redirect, BlobStore.BlobObject object) {
    }

    public MediaService(InfrastructureService infrastructure, TenantDirectory tenants, UsageService usage,
                        PlatformProperties properties) throws Exception {
        this.infrastructure = infrastructure;
        this.tenants = tenants;
        this.usage = usage;
        this.properties = properties;
        byte[] key = MessageDigest.getInstance("SHA-256")
                .digest(("media:" + properties.getSecurity().effectiveMasterKey()).getBytes(StandardCharsets.UTF_8));
        this.signingKey = new SecretKeySpec(key, "HmacSHA256");
    }

    public MediaView upload(String slug, MultipartFile file) {
        TenantSnapshot tenant = tenants.requireUsable(slug);
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("file is required");
        }
        String mime = file.getContentType() == null ? "application/octet-stream" : file.getContentType().toLowerCase(Locale.ROOT);
        boolean media = isInlineSafe(mime);
        tenant.require(media ? Feature.MEDIA_SHARING : Feature.FILE_SHARING);
        long maxBytes = Math.min((long) tenant.settings().maxFileSizeMb() * 1024 * 1024, properties.getMedia().getMaxUploadBytes());
        if (file.getSize() > maxBytes) {
            throw ApiException.badRequest("File exceeds the " + (maxBytes / (1024 * 1024)) + " MB limit");
        }
        usage.consumeStorage(tenant, file.getSize());
        String mediaId = Ids.newId() + extension(file.getOriginalFilename());
        try (InputStream in = file.getInputStream()) {
            infrastructure.blobs().put(blobKey(slug, mediaId), in, file.getSize(), mime);
        } catch (IOException e) {
            throw ApiException.unavailable("Upload failed: " + e.getMessage());
        }
        return new MediaView(mediaId, url(slug, mediaId), mime, file.getSize(), sanitize(file.getOriginalFilename()));
    }

    public Download download(String slug, String mediaId, String signature) {
        if (!MEDIA_ID.matcher(mediaId).matches() || signature == null || !MessageDigest.isEqual(
                sign(slug, mediaId).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.notFound("Media");
        }
        tenants.requireUsable(slug);
        BlobStore blobs = infrastructure.blobs();
        Optional<String> presigned = blobs.presignedUrl(blobKey(slug, mediaId), Duration.ofMinutes(15));
        if (presigned.isPresent()) {
            return new Download(presigned, null);
        }
        return new Download(Optional.empty(), blobs.get(blobKey(slug, mediaId)).orElseThrow(() -> ApiException.notFound("Media")));
    }

    public String url(String slug, String mediaId) {
        String base = properties.getPublicUrl().endsWith("/")
                ? properties.getPublicUrl().substring(0, properties.getPublicUrl().length() - 1) : properties.getPublicUrl();
        return base + "/api/v1/media/" + slug + "/" + mediaId + "?sig=" + sign(slug, mediaId);
    }

    private String sign(String slug, String mediaId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(signingKey);
            byte[] digest = mac.doFinal((slug + "/" + mediaId).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blobKey(String slug, String mediaId) {
        return slug + "/" + mediaId;
    }

    private static String extension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{1,8}") ? "." + ext : "";
    }

    private static String sanitize(String filename) {
        if (filename == null || filename.isBlank()) {
            return "file";
        }
        String name = filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }
}
