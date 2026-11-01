package com.nbh.backend.imagecleanup;

import com.nbh.backend.integrations.IntegrationKey;
import com.nbh.backend.integrations.IntegrationSettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The shop's own photo bucket (the move off Cloudinary, Nov 2026): the S3 signature matches AWS's own published example,
 * and storing, looking up and deleting work against a stand-in that re-checks every signature.
 */
class S3PhotoStoreTest {

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1, 1};

    private S3StandIn s3;
    private final Map<IntegrationKey, String> values = new HashMap<>();

    @BeforeEach
    void start() throws Exception {
        s3 = new S3StandIn("nbh-media");
        values.put(IntegrationKey.PHOTO_S3_ACCESS_KEY, S3StandIn.ACCESS);
        values.put(IntegrationKey.PHOTO_S3_SECRET_KEY, S3StandIn.SECRET);
        values.put(IntegrationKey.PHOTO_STORAGE, "s3");
    }

    @AfterEach
    void stop() {
        s3.close();
    }

    static IntegrationSettingsService settings(Map<IntegrationKey, String> values) {
        IntegrationSettingsService settings = mock(IntegrationSettingsService.class);
        when(settings.get(any())).thenAnswer(inv -> values.getOrDefault(inv.<IntegrationKey>getArgument(0), ""));
        when(settings.has(any())).thenAnswer(inv -> !values.getOrDefault(inv.<IntegrationKey>getArgument(0), "").isBlank());
        return settings;
    }

    private S3PhotoStore store() {
        return new S3PhotoStore(settings(values), s3.endpoint(), "garage", "nbh-media", "https://img.example.test/");
    }

    @Test
    void theSignatureIsAwsOwnPublishedExample() {
        // "GET Object" in AWS's Signature Version 4 examples for S3 (header-based authentication)
        Map<String, String> headers = new TreeMap<>();
        String empty = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        headers.put("host", "examplebucket.s3.amazonaws.com");
        headers.put("range", "bytes=0-9");
        headers.put("x-amz-content-sha256", empty);
        headers.put("x-amz-date", "20130524T000000Z");
        String auth = S3PhotoStore.authorization("GET", "/test.txt", "", headers, empty, "20130524T000000Z", "us-east-1",
                "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");
        assertThat(auth).isEqualTo("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, "
                + "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, "
                + "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41");
    }

    @Test
    void aPhotoIsStoredLookedUpAndDeleted() {
        S3PhotoStore store = store();
        String url = store.put("trendysh/products/a b+c.png", PNG, "image/png");

        assertThat(url).isEqualTo("https://img.example.test/o/trendysh/products/a b+c.png");
        assertThat(s3.objects.get("trendysh/products/a b+c.png")).isEqualTo(PNG);
        assertThat(s3.requests).contains("PUT /nbh-media/trendysh/products/a%20b%2Bc.png");
        Map<String, String> meta = s3.metadata.get("trendysh/products/a b+c.png");
        assertThat(meta).containsEntry("cache-control", "public, max-age=31536000, immutable")
                .containsEntry("content-type", "image/png")
                .containsEntry("x-amz-meta-sha256", S3PhotoStore.sha256Hex(PNG));

        assertThat(store.size("trendysh/products/a b+c.png")).contains((long) PNG.length);
        assertThat(store.size("trendysh/products/missing.png")).isEmpty();

        store.delete("trendysh/products/a b+c.png");
        assertThat(s3.objects).isEmpty();
    }

    @Test
    void aWrongSecretIsRefusedNotIgnored() {
        values.put(IntegrationKey.PHOTO_S3_SECRET_KEY, "wrong");
        assertThatThrownBy(() -> store().put("products/x.png", PNG, "image/png")).hasMessageContaining("HTTP 403");
        assertThat(s3.objects).isEmpty();
    }

    @Test
    void theBucketIsUsedOnlyWhenSwitchedOnAndBothKeysAreSet() {
        assertThat(store().active()).isTrue();
        values.put(IntegrationKey.PHOTO_STORAGE, "cloudinary");
        assertThat(store().active()).isFalse();
        values.put(IntegrationKey.PHOTO_STORAGE, " S3 ");
        values.remove(IntegrationKey.PHOTO_S3_SECRET_KEY);
        assertThat(store().active()).isFalse();
        assertThat(store().configured()).isFalse();
    }

    @Test
    void theKeyBehindAPhotoServerAddress() {
        S3PhotoStore store = store();
        assertThat(store.keyOf("https://img.example.test/o/products/a.png")).contains("products/a.png");
        assertThat(store.keyOf("https://img.example.test/w320/trendysh/products/b.jpg")).contains("trendysh/products/b.jpg");
        assertThat(store.keyOf("https://img.example.test/x/products/a.png")).isEmpty();
        assertThat(store.keyOf("https://img.example.test/o/../secret")).isEmpty();
        assertThat(store.keyOf("https://res.cloudinary.com/shop/image/upload/products/a.png")).isEmpty();
        assertThat(store.keyOf(null)).isEmpty();
    }

    @Test
    void whatThePicturesBytesSayItIs() {
        assertThat(S3PhotoStore.imageType(PNG).map(S3PhotoStore.ImageType::extension)).contains("png");
        assertThat(S3PhotoStore.imageType(JPEG).map(S3PhotoStore.ImageType::contentType)).contains("image/jpeg");
        assertThat(S3PhotoStore.imageType("RIFF\0\0\0\0WEBPVP8 ".getBytes())).map(S3PhotoStore.ImageType::extension).contains("webp");
        assertThat(S3PhotoStore.imageType("<html><body>hi</body>".getBytes())).isEqualTo(Optional.empty());
        assertThat(S3PhotoStore.imageType(new byte[3])).isEmpty();
    }
}
