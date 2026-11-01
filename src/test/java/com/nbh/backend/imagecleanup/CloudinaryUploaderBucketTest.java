package com.nbh.backend.imagecleanup;

import com.nbh.backend.integrations.IntegrationKey;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * With photo storage switched to "s3" every photo the backend makes goes to the shop's own bucket, and nothing is ever
 * deleted from Cloudinary any more (it keeps the old photos as the way back). Switched off, Cloudinary works as before.
 */
class CloudinaryUploaderBucketTest {

    private S3StandIn s3;
    private HttpServer cloudinary;
    private final List<String> cloudinaryCalls = new CopyOnWriteArrayList<>();
    private final Map<IntegrationKey, String> values = new HashMap<>();
    private CloudinaryUploader uploader;

    @BeforeEach
    void start() throws Exception {
        s3 = new S3StandIn("nbh-media");
        cloudinary = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        cloudinary.createContext("/", ex -> {
            cloudinaryCalls.add(ex.getRequestURI().getPath());
            byte[] out = "{\"result\":\"ok\",\"secure_url\":\"https://res.cloudinary.com/shop/image/upload/v1/products/p.png\",\"public_id\":\"products/p\"}".getBytes();
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        cloudinary.start();
        values.put(IntegrationKey.CLOUDINARY_CLOUD_NAME, "shop");
        values.put(IntegrationKey.CLOUDINARY_API_KEY, "k");
        values.put(IntegrationKey.CLOUDINARY_API_SECRET, "s");
        values.put(IntegrationKey.PHOTO_S3_ACCESS_KEY, S3StandIn.ACCESS);
        values.put(IntegrationKey.PHOTO_S3_SECRET_KEY, S3StandIn.SECRET);
        values.put(IntegrationKey.PHOTO_STORAGE, "s3");
        var settings = S3PhotoStoreTest.settings(values);
        ImageCleanupConfig config = new ImageCleanupConfig();
        config.setCloudinaryUploadBaseUrl("http://127.0.0.1:" + cloudinary.getAddress().getPort());
        uploader = new CloudinaryUploader(config, settings,
                new S3PhotoStore(settings, s3.endpoint(), "garage", "nbh-media", "https://img.example.test"));
    }

    @AfterEach
    void stop() {
        s3.close();
        cloudinary.stop(0);
    }

    @Test
    void aMadePhotoGoesToTheBucketUnderItsFolderAndName() {
        CloudinaryUploader.Uploaded up = uploader.upload(S3PhotoStoreTest.PNG, "products", "abc-clean-1");

        assertThat(up.url()).isEqualTo("https://img.example.test/o/products/abc-clean-1.png");
        assertThat(up.publicId()).isEqualTo("products/abc-clean-1.png");
        assertThat(s3.objects).containsKey("products/abc-clean-1.png");
        assertThat(cloudinaryCalls).isEmpty();
        assertThat(uploader.configured()).isTrue();
        assertThat(uploader.isOwnUrl(up.url())).isTrue();
        assertThat(uploader.isOwnUrl("https://res.cloudinary.com/shop/image/upload/v1/products/old.png")).isTrue();
        assertThat(uploader.isOwnUrl("https://elsewhere.test/products/x.png")).isFalse();
    }

    @Test
    void aJpegKeepsItsOwnExtensionAndANonPictureIsRefused() {
        assertThat(uploader.upload(S3PhotoStoreTest.JPEG, "deals", "d1").publicId()).isEqualTo("deals/d1.jpg");
        assertThatThrownBy(() -> uploader.upload("hello".getBytes(), "deals", "d2")).hasMessageContaining("not a picture");
    }

    @Test
    void deletingTakesABucketPhotoAwayButNeverACloudinaryOne() {
        uploader.upload(S3PhotoStoreTest.PNG, "products", "gone");
        uploader.delete("products/gone.png");
        uploader.delete("trendysh/products/an-old-cloudinary-photo");

        assertThat(s3.objects).doesNotContainKey("products/gone.png");
        assertThat(cloudinaryCalls).isEmpty();
    }

    @Test
    void switchedOffCloudinaryIsUsedAsBefore() {
        values.put(IntegrationKey.PHOTO_STORAGE, "cloudinary");

        CloudinaryUploader.Uploaded up = uploader.upload(S3PhotoStoreTest.PNG, "products", "p");
        uploader.delete("products/p");

        assertThat(up.url()).startsWith("https://res.cloudinary.com/");
        assertThat(cloudinaryCalls).containsExactly("/v1_1/shop/image/upload", "/v1_1/shop/image/destroy");
        assertThat(s3.objects).isEmpty();
    }
}
