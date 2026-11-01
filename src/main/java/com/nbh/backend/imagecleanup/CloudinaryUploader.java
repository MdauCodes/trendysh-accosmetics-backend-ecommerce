package com.nbh.backend.imagecleanup;

import com.fasterxml.jackson.databind.JsonNode;
import com.nbh.backend.common.exception.ConfigurationException;
import lombok.extern.slf4j.Slf4j;
import com.nbh.backend.integrations.IntegrationKey;
import com.nbh.backend.integrations.IntegrationSettingsService;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Server-side signed upload/delete to Cloudinary — the backend produces these images itself, so unlike staff
 * uploads (signed here, sent from the browser) it has to send them. Plain HTTP with the same SHA-1 signing the
 * rest of the app uses; no Cloudinary SDK.
 *
 * <p>Since the move off Cloudinary (Nov 2026) it is the one place every photo the backend makes is stored: with photo
 * storage set to "s3" ({@link S3PhotoStore#active()}) a photo goes to the shop's own bucket instead, and its "public id" is
 * then the bucket key (it has a file extension; a Cloudinary id never does). Nothing is ever deleted from Cloudinary while
 * the bucket is in use: Cloudinary keeps every old photo as the way back.
 */
@Slf4j
@Service
public class CloudinaryUploader {

    public record Uploaded(String url, String publicId) {}

    private static final java.util.regex.Pattern BUCKET_KEY = java.util.regex.Pattern.compile(".+\\.[A-Za-z0-9]{2,5}$");

    private final ImageCleanupConfig config;
    private final IntegrationSettingsService settings;
    /** Null only in tests that exercise the Cloudinary side alone. */
    private final S3PhotoStore bucket;
    /**
     * With timeouts: a hung upload used to wait forever, so the variant-job worker running it was never given back
     * (three of those and every later job sat queued with no error). Same limits as the Gemini clients.
     */
    private final RestTemplate http = withTimeouts();

    private static RestTemplate withTimeouts() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(90_000);
        return new RestTemplate(factory);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CloudinaryUploader(ImageCleanupConfig config, IntegrationSettingsService settings, S3PhotoStore bucket) {
        this.config = config;
        this.settings = settings;
        this.bucket = bucket;
    }

    public CloudinaryUploader(ImageCleanupConfig config, IntegrationSettingsService settings) {
        this(config, settings, null);
    }

    /** New photos go to the shop's own bucket rather than Cloudinary. */
    public boolean bucketActive() {
        return bucket != null && bucket.active();
    }

    /** The bucket key behind one of the photo server's addresses, or empty for any other address. */
    public java.util.Optional<String> bucketKeyOf(String url) {
        return bucket == null ? java.util.Optional.empty() : bucket.keyOf(url);
    }

    /** The account's cloud name, or blank when Cloudinary isn't set up. Photos on any other host are not ours to delete. */
    public String cloudName() {
        return settings.get(IntegrationKey.CLOUDINARY_CLOUD_NAME);
    }

    /**
     * Whether {@code url} is a file in this shop's own Cloudinary account. Photos named in staff's instructions to the
     * AI are downloaded by the server, so only these are accepted. A stand-in upload address (tests, QA) counts its own
     * files as the shop's.
     */
    public boolean isOwnUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        if (bucketKeyOf(url.trim()).isPresent()) {
            return true;
        }
        String cloud = cloudName();
        if (cloud != null && !cloud.isBlank() && url.startsWith("https://res.cloudinary.com/" + cloud + "/")) {
            return true;
        }
        String base = config.getCloudinaryUploadBaseUrl();
        if (base == null || base.contains("api.cloudinary.com")) {
            return false;
        }
        try {
            java.net.URI b = java.net.URI.create(base);
            java.net.URI u = java.net.URI.create(url.trim());
            return b.getHost() != null && b.getHost().equals(u.getHost()) && b.getPort() == u.getPort()
                    && java.util.Objects.equals(b.getScheme(), u.getScheme());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String apiKey() {
        return settings.get(IntegrationKey.CLOUDINARY_API_KEY);
    }

    private String apiSecret() {
        return settings.get(IntegrationKey.CLOUDINARY_API_SECRET);
    }

    /** Photos can be stored: in the bucket when it is in use, else on Cloudinary. */
    public boolean configured() {
        if (bucketActive()) {
            return true;
        }
        return settings.has(IntegrationKey.CLOUDINARY_CLOUD_NAME) && settings.has(IntegrationKey.CLOUDINARY_API_KEY)
                && settings.has(IntegrationKey.CLOUDINARY_API_SECRET);
    }

    public Uploaded upload(byte[] png, String folder, String publicId) {
        if (bucketActive()) {
            S3PhotoStore.ImageType type = S3PhotoStore.imageType(png)
                    .orElseThrow(() -> new IllegalArgumentException("That file is not a picture (PNG, JPEG, WebP, GIF or AVIF)."));
            String key = folder + "/" + publicId + "." + type.extension();
            return new Uploaded(bucket.put(key, png, type.contentType()), key);
        }
        requireConfigured();
        long timestamp = System.currentTimeMillis() / 1000;
        Map<String, String> signed = new TreeMap<>();
        signed.put("folder", folder);
        signed.put("public_id", publicId);
        signed.put("timestamp", String.valueOf(timestamp));

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        String mime = S3PhotoStore.imageType(png).map(S3PhotoStore.ImageType::contentType).orElse("image/png");
        form.add("file", "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(png));
        form.add("api_key", apiKey());
        signed.forEach(form::add);
        form.add("signature", sign(signed));

        JsonNode result = post("/image/upload", form);
        String url = result.path("secure_url").asText(null);
        String id = result.path("public_id").asText(publicId);
        if (url == null) {
            throw new IllegalStateException("Cloudinary's reply had no secure_url: " + result);
        }
        return new Uploaded(url, id);
    }

    /** Best effort: a failure to delete is logged, never thrown — the database state is what matters. */
    public void delete(String publicId) {
        if (publicId == null || publicId.isBlank()) {
            return;
        }
        if (BUCKET_KEY.matcher(publicId).matches()) {
            if (bucket != null) bucket.delete(publicId);
            return;
        }
        // a Cloudinary photo: kept there for good once the bucket is in use (it is the way back)
        if (bucketActive() || !settings.has(IntegrationKey.CLOUDINARY_CLOUD_NAME) || !settings.has(IntegrationKey.CLOUDINARY_API_KEY)
                || !settings.has(IntegrationKey.CLOUDINARY_API_SECRET)) {
            return;
        }
        try {
            long timestamp = System.currentTimeMillis() / 1000;
            Map<String, String> signed = new TreeMap<>();
            signed.put("public_id", publicId);
            signed.put("timestamp", String.valueOf(timestamp));
            // Without this the photo keeps being served from Cloudinary's CDN cache for a while after it is "deleted".
            signed.put("invalidate", "true");
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("api_key", apiKey());
            signed.forEach(form::add);
            form.add("signature", sign(signed));
            post("/image/destroy", form);
        } catch (RuntimeException e) {
            log.warn("Couldn't delete Cloudinary asset {}: {}", publicId, e.getMessage());
        }
    }

    private JsonNode post(String path, MultiValueMap<String, String> form) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        String url = config.getCloudinaryUploadBaseUrl() + "/v1_1/" + cloudName() + path;
        return http.exchange(url, HttpMethod.POST, new HttpEntity<>(form, headers), JsonNode.class).getBody();
    }

    private String sign(Map<String, String> params) {
        String toSign = params.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&")) + apiSecret();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(toSign.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new ConfigurationException(
                    "Cloudinary is not configured — set CLOUDINARY_CLOUD_NAME/CLOUDINARY_API_KEY/CLOUDINARY_API_SECRET");
        }
    }
}
