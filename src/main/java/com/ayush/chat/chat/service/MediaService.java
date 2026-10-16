package com.ayush.chat.chat.service;

import com.ayush.chat.blob.BlobStore;
import com.ayush.chat.chat.dto.command.MediaUpload;
import com.ayush.chat.chat.dto.response.MediaDownload;
import com.ayush.chat.chat.dto.response.MediaView;
import com.ayush.chat.core.connector.runtime.PlatformConnectors;
import com.ayush.chat.core.exception.DomainException;
import com.ayush.chat.core.exception.InfrastructureUnavailableException;
import com.ayush.chat.core.util.Ids;
import com.ayush.chat.tenant.model.Feature;
import com.ayush.chat.tenant.model.TenantSnapshot;
import com.ayush.chat.tenant.service.QuotaService;
import com.ayush.chat.tenant.service.TenantDirectory;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Media upload and download. Uploads get an unguessable id and a signed capability URL; when the
 * blob store can presign (S3), downloads redirect straight to storage.
 */
@Service
public class MediaService {

    private static final Pattern MEDIA_ID = Pattern.compile("^[0-9a-f-]{36}(\\.[a-z0-9]{1,8})?$");
    private static final Duration PRESIGN_TTL = Duration.ofMinutes(15);
    private static final long BYTES_PER_MB = 1024L * 1024L;
    private static final int MAX_FILE_NAME = 200;

    /**
     * Types safe to render inline on the platform origin. Anything else (SVG, HTML, XML, PDF, ...)
     * could carry script and is always served as a download.
     */
    private static final Set<String> INLINE_SAFE = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/avif", "image/heic", "image/heif",
            "video/mp4", "video/webm", "video/quicktime", "video/3gpp",
            "audio/mpeg", "audio/mp4", "audio/aac", "audio/ogg", "audio/webm", "audio/wav", "audio/x-wav", "audio/amr");

    private final PlatformConnectors platform;
    private final TenantDirectory tenants;
    private final QuotaService quotas;
    private final MediaUrlSigner signer;
    private final long serverMaxBytes;

    public MediaService(PlatformConnectors platform, TenantDirectory tenants, QuotaService quotas, MediaUrlSigner signer,
                        MultipartProperties multipart) {
        this.platform = platform;
        this.tenants = tenants;
        this.quotas = quotas;
        this.signer = signer;
        this.serverMaxBytes = multipart.getMaxFileSize().toBytes();
    }

    public static boolean isInlineSafe(String contentType) {
        if (contentType == null) {
            return false;
        }
        return INLINE_SAFE.contains(contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim());
    }

    public MediaView upload(String tenantSlug, MediaUpload upload) {
        TenantSnapshot tenant = tenants.requireUsable(tenantSlug);
        if (upload == null || upload.size() <= 0) {
            throw DomainException.badRequest("file is required");
        }
        String mime = upload.contentType() == null ? "application/octet-stream" : upload.contentType().toLowerCase(Locale.ROOT);
        tenant.require(isInlineSafe(mime) ? Feature.MEDIA_SHARING : Feature.FILE_SHARING);
        long maxBytes = Math.min(tenant.settings().maxFileSizeMb() * BYTES_PER_MB, serverMaxBytes);
        if (upload.size() > maxBytes) {
            throw DomainException.badRequest("File exceeds the " + (maxBytes / BYTES_PER_MB) + " MB limit");
        }
        quotas.consumeStorage(tenant, upload.size());
        String mediaId = Ids.newId() + extension(upload.fileName());
        try (InputStream in = upload.content().open()) {
            platform.blobs().put(blobKey(tenantSlug, mediaId), in, upload.size(), mime);
        } catch (IOException e) {
            throw new InfrastructureUnavailableException("blob-store", e);
        }
        return new MediaView(mediaId, signer.url(tenantSlug, mediaId), mime, upload.size(), sanitize(upload.fileName()));
    }

    /** Unknown ids and bad signatures are indistinguishable (404). */
    public MediaDownload download(String tenantSlug, String mediaId, String signature) {
        if (!MEDIA_ID.matcher(mediaId).matches() || !signer.verify(tenantSlug, mediaId, signature)) {
            throw DomainException.notFound("Media");
        }
        tenants.requireUsable(tenantSlug);
        BlobStore blobs = platform.blobs();
        Optional<String> presigned = blobs.presignedUrl(blobKey(tenantSlug, mediaId), PRESIGN_TTL);
        if (presigned.isPresent()) {
            return new MediaDownload(presigned, null);
        }
        return new MediaDownload(Optional.empty(),
                blobs.get(blobKey(tenantSlug, mediaId)).orElseThrow(() -> DomainException.notFound("Media")));
    }

    private static String blobKey(String tenant, String mediaId) {
        return tenant + "/" + mediaId;
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
        return name.length() > MAX_FILE_NAME ? name.substring(name.length() - MAX_FILE_NAME) : name;
    }
}
