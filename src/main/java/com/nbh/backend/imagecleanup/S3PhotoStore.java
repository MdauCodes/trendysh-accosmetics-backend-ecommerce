package com.nbh.backend.imagecleanup;

import com.nbh.backend.common.exception.ConfigurationException;
import com.nbh.backend.integrations.IntegrationKey;
import com.nbh.backend.integrations.IntegrationSettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The shop's own photo bucket (Garage on the Contabo server, see ops/vps), which replaces Cloudinary: an S3 API at
 * {@code app.photos.s3-endpoint} for storing and deleting, and the photo server at {@code app.photos.public-base-url}
 * ({@code /o/<key>} the original, {@code /w<width>/<key>} a resized copy) for showing them. A key is the photo's folder and
 * file name, e.g. {@code trendysh/products/3f2c….jpg}; photos copied from Cloudinary keep their Cloudinary path.
 *
 * <p>Plain HTTP with AWS Signature Version 4 (path-style addresses), no SDK, the way {@link CloudinaryUploader} signs for
 * Cloudinary. Every object is stored with a year-long immutable Cache-Control (a stored photo is never overwritten) and
 * its sha256 in {@code x-amz-meta-sha256}, the same metadata the copy tool wrote.
 */
@Slf4j
@Service
public class S3PhotoStore {

    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final String CACHE_CONTROL = "public, max-age=31536000, immutable";
    private static final Pattern PHOTO_PATH = Pattern.compile("^/(?:o|w\\d+)/(.+)$");

    private final IntegrationSettingsService settings;
    private final String endpoint;
    private final String region;
    private final String bucket;
    private final String publicBase;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public S3PhotoStore(IntegrationSettingsService settings,
                        @Value("${app.photos.s3-endpoint:https://s3.trendyshaccosmetic.com}") String endpoint,
                        @Value("${app.photos.s3-region:garage}") String region,
                        @Value("${app.photos.bucket:nbh-media}") String bucket,
                        @Value("${app.photos.public-base-url:https://img.trendyshaccosmetic.com}") String publicBase) {
        this.settings = settings;
        this.endpoint = trimSlash(endpoint);
        this.region = region;
        this.bucket = bucket;
        this.publicBase = trimSlash(publicBase);
    }

    /** Both keys are set, so the bucket can be written to. */
    public boolean configured() {
        return settings.has(IntegrationKey.PHOTO_S3_ACCESS_KEY) && settings.has(IntegrationKey.PHOTO_S3_SECRET_KEY);
    }

    /** New photos go to the bucket: storage is set to "s3" and the keys are there. */
    public boolean active() {
        return "s3".equalsIgnoreCase(settings.get(IntegrationKey.PHOTO_STORAGE).trim()) && configured();
    }

    /** The address a stored photo is shown at: the original, through the photo server. */
    public String publicUrl(String key) {
        return publicBase + "/o/" + key;
    }

    /** The key behind one of the photo server's addresses (original or resized copy), or empty for any other address. */
    public Optional<String> keyOf(String url) {
        if (url == null || publicBase.isBlank() || !url.startsWith(publicBase + "/")) {
            return Optional.empty();
        }
        Matcher m = PHOTO_PATH.matcher(url.substring(publicBase.length()));
        if (!m.matches() || m.group(1).contains("..")) {
            return Optional.empty();
        }
        return Optional.of(m.group(1));
    }

    /** Stores a photo; returns the address it is shown at. */
    public String put(String key, byte[] bytes, String contentType) {
        requireConfigured();
        Map<String, String> headers = new TreeMap<>();
        headers.put("cache-control", CACHE_CONTROL);
        headers.put("content-type", contentType);
        headers.put("x-amz-meta-sha256", sha256Hex(bytes));
        HttpResponse<String> response = send("PUT", key, headers, bytes);
        if (response.statusCode() != 200) {
            throw new IllegalStateException("The photo bucket refused " + key + ": HTTP " + response.statusCode() + " "
                    + abbreviate(response.body()));
        }
        return publicUrl(key);
    }

    /** The stored photo's size in bytes, or empty when the bucket has no such photo. */
    public Optional<Long> size(String key) {
        requireConfigured();
        HttpResponse<String> response = send("HEAD", key, new TreeMap<>(), new byte[0]);
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("The photo bucket could not look up " + key + ": HTTP " + response.statusCode());
        }
        return response.headers().firstValueAsLong("content-length").stream().boxed().findFirst();
    }

    /** Best effort, like {@link CloudinaryUploader#delete}: a failure is logged, never thrown. */
    public void delete(String key) {
        if (!configured() || key == null || key.isBlank() || key.contains("..")) {
            return;
        }
        try {
            HttpResponse<String> response = send("DELETE", key, new TreeMap<>(), new byte[0]);
            if (response.statusCode() != 204 && response.statusCode() != 200 && response.statusCode() != 404) {
                log.warn("Couldn't delete {} from the photo bucket: HTTP {}", key, response.statusCode());
            }
        } catch (RuntimeException e) {
            log.warn("Couldn't delete {} from the photo bucket: {}", key, e.getMessage());
        }
    }

    private HttpResponse<String> send(String method, String key, Map<String, String> extraHeaders, byte[] body) {
        URI base = URI.create(endpoint);
        String path = uriEncodePath("/" + bucket + "/" + key);
        URI uri = URI.create(base.getScheme() + "://" + base.getRawAuthority() + path);
        String amzDate = ZonedDateTime.now(ZoneOffset.UTC).format(AMZ_DATE);
        String payloadHash = sha256Hex(body);
        Map<String, String> headers = new TreeMap<>(extraHeaders);
        headers.put("host", base.getRawAuthority());
        headers.put("x-amz-content-sha256", payloadHash);
        headers.put("x-amz-date", amzDate);
        String authorization = authorization(method, path, "", headers, payloadHash, amzDate, region,
                settings.get(IntegrationKey.PHOTO_S3_ACCESS_KEY), settings.get(IntegrationKey.PHOTO_S3_SECRET_KEY));

        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(90))
                .method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body))
                .header("Authorization", authorization);
        headers.forEach((name, value) -> {
            if (!name.equals("host")) request.header(name, value);
        });
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("The photo bucket could not be reached: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while talking to the photo bucket", e);
        }
    }

    /**
     * The {@code Authorization} header of AWS Signature Version 4 for one request. {@code headers} are every header that is
     * signed, names in lower case (host, x-amz-date and x-amz-content-sha256 among them). Package-private for the test that
     * checks it against AWS's own published example.
     */
    static String authorization(String method, String canonicalPath, String canonicalQuery, Map<String, String> headers,
                                String payloadHash, String amzDate, String region, String accessKey, String secretKey) {
        Map<String, String> sorted = new TreeMap<>(headers);
        String canonicalHeaders = sorted.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue().trim() + "\n")
                .collect(Collectors.joining());
        String signedHeaders = String.join(";", sorted.keySet());
        String canonicalRequest = method + "\n" + canonicalPath + "\n" + canonicalQuery + "\n" + canonicalHeaders + "\n"
                + signedHeaders + "\n" + payloadHash;
        String date = amzDate.substring(0, 8);
        String scope = date + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] key = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        key = hmac(key, region);
        key = hmac(key, "s3");
        key = hmac(key, "aws4_request");
        String signature = HexFormat.of().formatHex(hmac(key, stringToSign));
        return "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;
    }

    /** S3's URI encoding of a path: every byte but the unreserved characters and '/' as %XX (upper case). */
    static String uriEncodePath(String path) {
        StringBuilder out = new StringBuilder();
        for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || "-_.~/".indexOf(c) >= 0) {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return out.toString();
    }

    /** What a picture's bytes say it is (not what its name or the browser claim). */
    public record ImageType(String extension, String contentType) {}

    public static Optional<ImageType> imageType(byte[] b) {
        if (b == null || b.length < 12) return Optional.empty();
        if ((b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return Optional.of(new ImageType("png", "image/png"));
        if ((b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8 && (b[2] & 0xff) == 0xFF) return Optional.of(new ImageType("jpg", "image/jpeg"));
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P')
            return Optional.of(new ImageType("webp", "image/webp"));
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return Optional.of(new ImageType("gif", "image/gif"));
        if (b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p' && b[8] == 'a' && b[9] == 'v' && b[10] == 'i')
            return Optional.of(new ImageType("avif", "image/avif"));
        return Optional.empty();
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new ConfigurationException(
                    "The photo bucket is not configured: set PHOTO_S3_ACCESS_KEY and PHOTO_S3_SECRET_KEY");
        }
    }

    private static String trimSlash(String s) {
        return s == null ? "" : s.replaceAll("/+$", "");
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
