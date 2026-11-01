package com.nbh.backend.imagecleanup;

import com.nbh.backend.integrations.IntegrationKey;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Moving the stored photo addresses off Cloudinary, on a schema of its own in the test database: every text column is
 * searched (JSON too), a photo already in the bucket is not copied again, a missing one is copied and checked, one that
 * cannot be copied is left as it is, each rewritten value is kept, and undo puts every one back (but not over a later edit).
 */
class PhotoMoveServiceTest {

    private static final String SCHEMA = "photo_move_test";
    private static final String CLOUD = "https://res.cloudinary.com/dhgwijhdu/image/upload/";
    private static final String OLD = CLOUD + "v1791/products/old.png";
    private static final String NEW = CLOUD + "v1792/products/new.png";
    private static final String MISSING = CLOUD + "v1/products/missing.png";
    private static final String RESIZED = CLOUD + "w_300,c_fill/v1/products/old.png";
    private static final String OTHER = "https://res.cloudinary.com/other/image/upload/v1/products/x.png";
    private static final byte[] NEW_BYTES = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3, 4, 5, 6, 7, 8};

    private S3StandIn s3;
    private HttpServer cloudinary;
    private JdbcTemplate jdbc;
    private PhotoMoveService service;
    private final UUID p1 = UUID.randomUUID(), p2 = UUID.randomUUID(), p3 = UUID.randomUUID(), p4 = UUID.randomUUID(),
            p5 = UUID.randomUUID(), job = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        String url = Objects.requireNonNullElse(System.getenv("DB_URL"), "jdbc:postgresql://localhost:5432/nairobi_beauty_hub_test");
        String user = Objects.requireNonNullElse(System.getenv("DB_USERNAME"), "postgres");
        String password = Objects.requireNonNullElse(System.getenv("DB_PASSWORD"), "postgres");
        new JdbcTemplate(new DriverManagerDataSource(url, user, password)).execute(
                "DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE; CREATE SCHEMA " + SCHEMA);
        DriverManagerDataSource ds = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA, user, password);
        jdbc = new JdbcTemplate(ds);
        try (var in = getClass().getResourceAsStream("/db/migration/V88__photo_url_move.sql")) {
            jdbc.execute(new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8));
        }
        jdbc.execute("""
                CREATE TABLE product_image (id UUID PRIMARY KEY, url VARCHAR(500) NOT NULL);
                CREATE TABLE variant_job (id UUID PRIMARY KEY, request TEXT);
                CREATE TABLE banner (id BIGSERIAL PRIMARY KEY, image_url TEXT);
                CREATE TABLE shop_note (id INT PRIMARY KEY, data JSONB);
                CREATE TABLE no_id (k TEXT);
                """);
        jdbc.update("INSERT INTO product_image VALUES (?, ?), (?, ?), (?, ?), (?, ?), (?, ?)",
                p1, OLD, p2, NEW, p3, MISSING, p4, OTHER, p5, RESIZED);
        jdbc.update("INSERT INTO variant_job VALUES (?, ?)", job,
                "{\"guides\":[\"" + OLD + "\",\"" + NEW + "\"],\"again\":\"" + OLD + "\"}");
        jdbc.update("INSERT INTO banner (image_url) VALUES (?)", NEW);
        jdbc.update("INSERT INTO shop_note VALUES (1, CAST(? AS jsonb))", "{\"photo\": \"" + OLD + "\"}");
        jdbc.update("INSERT INTO no_id VALUES (?)", OLD);

        s3 = new S3StandIn("nbh-media");
        s3.objects.put("products/old.png", new byte[] {1, 2, 3});
        cloudinary = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        cloudinary.createContext("/", ex -> {
            boolean known = ex.getRequestURI().getPath().equals("/dhgwijhdu/image/upload/v1792/products/new.png");
            ex.sendResponseHeaders(known ? 200 : 404, known ? NEW_BYTES.length : -1);
            if (known) ex.getResponseBody().write(NEW_BYTES);
            ex.close();
        });
        cloudinary.start();

        Map<IntegrationKey, String> values = new HashMap<>(Map.of(
                IntegrationKey.CLOUDINARY_CLOUD_NAME, "dhgwijhdu",
                IntegrationKey.PHOTO_S3_ACCESS_KEY, S3StandIn.ACCESS,
                IntegrationKey.PHOTO_S3_SECRET_KEY, S3StandIn.SECRET,
                IntegrationKey.PHOTO_STORAGE, "s3"));
        var settings = S3PhotoStoreTest.settings(values);
        S3PhotoStore bucket = new S3PhotoStore(settings, s3.endpoint(), "garage", "nbh-media", "https://img.example.test");
        service = new PhotoMoveService(jdbc, new DataSourceTransactionManager(ds), bucket, settings,
                "http://127.0.0.1:" + cloudinary.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        s3.close();
        cloudinary.stop(0);
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    private String image(UUID id) {
        return jdbc.queryForObject("SELECT url FROM product_image WHERE id = ?", String.class, id);
    }

    @Test
    void aDryRunCountsAndChangesNothing() {
        PhotoMoveService.Report r = service.move(true);

        assertThat(r.addresses()).isEqualTo(4); // OLD, NEW, MISSING, RESIZED (another account's is not ours)
        assertThat(r.alreadyThere()).isEqualTo(1);
        assertThat(r.copied()).isEqualTo(2);
        assertThat(r.failed()).isEqualTo(1);
        assertThat(r.columnsWithPhotos()).containsExactly("banner.image_url (1)", "product_image.url (4)",
                "shop_note.data (1)", "variant_job.request (1)");
        assertThat(image(p1)).isEqualTo(OLD);
        assertThat(s3.objects).doesNotContainKey("products/new.png");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM photo_url_move", Integer.class)).isZero();
    }

    @Test
    void theMoveCopiesWhatIsMissingRewritesEveryPlaceAndUndoPutsItAllBack() {
        PhotoMoveService.Report r = service.move(false);

        assertThat(r.alreadyThere()).isEqualTo(1);
        assertThat(r.copied()).isEqualTo(1);
        assertThat(r.failed()).isEqualTo(2);
        assertThat(r.rowsRewritten()).isEqualTo(5);
        assertThat(s3.objects.get("products/new.png")).isEqualTo(NEW_BYTES);
        assertThat(s3.objects.get("products/old.png")).containsExactly(1, 2, 3); // not copied again

        assertThat(image(p1)).isEqualTo("https://img.example.test/o/products/old.png");
        assertThat(image(p2)).isEqualTo("https://img.example.test/o/products/new.png");
        assertThat(image(p3)).isEqualTo(MISSING);
        assertThat(image(p4)).isEqualTo(OTHER);
        assertThat(image(p5)).isEqualTo(RESIZED);
        assertThat(jdbc.queryForObject("SELECT request FROM variant_job", String.class)).isEqualTo(
                "{\"guides\":[\"https://img.example.test/o/products/old.png\",\"https://img.example.test/o/products/new.png\"],"
                        + "\"again\":\"https://img.example.test/o/products/old.png\"}");
        assertThat(jdbc.queryForObject("SELECT image_url FROM banner", String.class)).isEqualTo("https://img.example.test/o/products/new.png");
        assertThat(jdbc.queryForObject("SELECT data->>'photo' FROM shop_note", String.class)).isEqualTo("https://img.example.test/o/products/old.png");
        assertThat(jdbc.queryForObject("SELECT k FROM no_id", String.class)).isEqualTo(OLD);
        assertThat(jdbc.queryForList("SELECT status FROM photo_url_copy ORDER BY old_url", String.class))
                .containsExactlyInAnyOrder("ALREADY_THERE", "COPIED", "FAILED");
        assertThat(jdbc.queryForObject("SELECT sha256 FROM photo_url_copy WHERE status = 'COPIED'", String.class))
                .isEqualTo(S3PhotoStore.sha256Hex(NEW_BYTES));

        // running it again finds nothing more to do
        assertThat(service.move(false).rowsRewritten()).isZero();

        PhotoMoveService.Report undo = service.undo();
        assertThat(undo.rowsUndone()).isEqualTo(5);
        assertThat(image(p1)).isEqualTo(OLD);
        assertThat(image(p2)).isEqualTo(NEW);
        assertThat(jdbc.queryForObject("SELECT request FROM variant_job", String.class)).contains(OLD, NEW).doesNotContain("img.example");
        assertThat(jdbc.queryForObject("SELECT data->>'photo' FROM shop_note", String.class)).isEqualTo(OLD);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM photo_url_move WHERE undone_at IS NULL", Integer.class)).isZero();
    }

    @Test
    void undoLeavesAValueStaffChangedSinceTheMove() {
        service.move(false);
        jdbc.update("UPDATE banner SET image_url = 'https://img.example.test/o/banners/chosen-later.png'");

        PhotoMoveService.Report undo = service.undo();

        assertThat(undo.rowsUndone()).isEqualTo(4);
        assertThat(undo.problems()).hasSize(1).first().asString().contains("banner.image_url");
        assertThat(jdbc.queryForObject("SELECT image_url FROM banner", String.class)).endsWith("chosen-later.png");
    }

    @Test
    void theKeyIsTheCloudinaryPathWithoutItsVersion() {
        assertThat(PhotoMoveService.keyOf(OLD, "dhgwijhdu")).contains("products/old.png");
        assertThat(PhotoMoveService.keyOf(CLOUD + "trendysh/products/b.jpg", "dhgwijhdu")).contains("trendysh/products/b.jpg");
        assertThat(PhotoMoveService.keyOf(RESIZED, "dhgwijhdu")).isEmpty();
        assertThat(PhotoMoveService.keyOf(CLOUD + "v1/../x.png", "dhgwijhdu")).isEmpty();
    }
}
