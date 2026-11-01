package com.nbh.backend.imagecleanup;

import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.integrations.IntegrationKey;
import com.nbh.backend.integrations.IntegrationSettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Moves every photo address the database holds off Cloudinary (owner, 1 Nov 2026: "we must leave Cloudinary as soon as
 * possible"). Developer-only, run from Admin > Settings > Integrations, in the background:
 * <ol>
 *   <li>every text column of every table with an {@code id} is searched for the shop's Cloudinary addresses (no hand-kept
 *       list of columns: a JSON request that names a photo is found too);</li>
 *   <li>each address is checked against the bucket (most were copied by ops/vps/copy); one that is missing is downloaded
 *       from Cloudinary (read only), checked to be a picture of the announced size, stored, and its size read back;</li>
 *   <li>each row is rewritten to the photo server's address ({@code https://img…/o/<key>}), one row at a time and only if it
 *       has not changed meanwhile, and the old and new value are kept in {@code photo_url_move}.</li>
 * </ol>
 * An address that could not be copied is left as it is (the photo server sends it on to Cloudinary). {@link #undo} writes
 * every old value back. Cloudinary is never written to or deleted from.
 */
@Slf4j
@Service
public class PhotoMoveService {

    private static final Pattern NAME = Pattern.compile("^[a-z_][a-z0-9_]*$");
    private static final Pattern VERSION = Pattern.compile("^v\\d+$");
    private static final Pattern TRANSFORMATION = Pattern.compile("^[a-z]{1,3}_[^/]*$");
    private static final long MAX_BYTES = 40L * 1024 * 1024;

    public record Report(String action, boolean dryRun, Instant startedAt, Instant finishedAt, int columns,
                         List<String> columnsWithPhotos, int addresses, int alreadyThere, int copied, int failed,
                         List<String> problems, int rowsRewritten, int rowsSkipped, int rowsUndone) {}

    public record Status(boolean running, String progress, Report last) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final S3PhotoStore bucket;
    private final IntegrationSettingsService settings;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "photo-move");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<String> progress = new AtomicReference<>(null);
    private volatile Report last;

    /** Where Cloudinary's photos are downloaded from: its delivery host, or a stand-in in tests. */
    private final String cloudinaryBase;

    public PhotoMoveService(JdbcTemplate jdbc, PlatformTransactionManager transactions, S3PhotoStore bucket,
                            IntegrationSettingsService settings,
                            @org.springframework.beans.factory.annotation.Value(
                                    "${app.photos.cloudinary-delivery-base:https://res.cloudinary.com}") String cloudinaryBase) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.bucket = bucket;
        this.settings = settings;
        this.cloudinaryBase = cloudinaryBase.replaceAll("/+$", "");
    }

    public Status status() {
        String p = progress.get();
        return new Status(p != null, p, last);
    }

    /** Starts a move (or with {@code dryRun}, only a count of what it would do) in the background. */
    public Status start(boolean dryRun) {
        if (!bucket.configured()) {
            throw new UnprocessableException("Set the photo bucket's access key and secret key first.");
        }
        if (!dryRun && !bucket.active()) {
            // otherwise a photo deleted in the dashboard meanwhile would still be deleted on Cloudinary, the way back
            throw new UnprocessableException("Switch photo storage to s3 first, so new photos go to the bucket too.");
        }
        begin(dryRun ? "Checking" : "Moving", () -> last = move(dryRun));
        return status();
    }

    /** Writes back every value the move changed (newest first) in the background. */
    public Status startUndo() {
        begin("Undoing", () -> last = undo());
        return status();
    }

    private void begin(String what, Runnable job) {
        if (!progress.compareAndSet(null, what + "…")) {
            throw new UnprocessableException("A photo move is already running.");
        }
        worker.execute(() -> {
            try {
                job.run();
            } catch (RuntimeException e) {
                log.error("Photo move failed", e);
                last = new Report(what, false, Instant.now(), Instant.now(), 0, List.of(), 0, 0, 0, 0,
                        List.of("Stopped: " + e.getMessage()), 0, 0, 0);
            } finally {
                progress.set(null);
            }
        });
    }

    Report move(boolean dryRun) {
        Instant started = Instant.now();
        String cloud = settings.get(IntegrationKey.CLOUDINARY_CLOUD_NAME).trim();
        if (cloud.isBlank()) {
            throw new UnprocessableException("The Cloudinary cloud name is not set, so the old addresses cannot be recognised.");
        }
        Pattern address = Pattern.compile("https?://res\\.cloudinary\\.com/" + Pattern.quote(cloud)
                + "/image/upload/[^\\s\"'<>\\\\)?#]+");
        String like = "%res.cloudinary.com/" + cloud + "/image/upload/%";

        List<String[]> columns = textColumns();
        Map<String, List<Row>> found = new LinkedHashMap<>();
        Set<String> urls = new LinkedHashSet<>();
        for (String[] c : columns) {
            String table = c[0], column = c[1];
            List<Row> rows = jdbc.query("SELECT id::text AS id, " + q(column) + "::text AS v FROM " + q(table)
                    + " WHERE " + q(column) + "::text LIKE ?", (rs, i) -> new Row(rs.getString("id"), rs.getString("v")), like);
            if (rows.isEmpty()) continue;
            found.put(table + "." + column, rows);
            for (Row r : rows) {
                Matcher m = address.matcher(r.value());
                while (m.find()) urls.add(m.group());
            }
        }

        List<String> problems = new ArrayList<>();
        Map<String, String> moved = new LinkedHashMap<>();
        int already = 0, copied = 0, failed = 0, n = 0;
        for (String url : urls) {
            progress.set((dryRun ? "Checking" : "Copying") + " photo " + (++n) + " of " + urls.size());
            Optional<String> key = keyOf(url, cloud);
            if (key.isEmpty()) {
                failed++;
                problems.add("Left as it is (already a resized address): " + url);
                continue;
            }
            try {
                Optional<Long> stored = bucket.size(key.get());
                if (stored.isPresent()) {
                    already++;
                    if (!dryRun) record(url, key.get(), stored.get(), null, "ALREADY_THERE", null);
                    moved.put(url, bucket.publicUrl(key.get()));
                } else if (dryRun) {
                    copied++;
                } else {
                    long[] size = new long[1];
                    String sha = copy(url, key.get(), size);
                    record(url, key.get(), size[0], sha, "COPIED", null);
                    copied++;
                    moved.put(url, bucket.publicUrl(key.get()));
                }
            } catch (RuntimeException e) {
                failed++;
                problems.add(url + ": " + e.getMessage());
                if (!dryRun) record(url, key.get(), null, null, "FAILED", e.getMessage());
            }
        }

        int rewritten = 0, skipped = 0;
        if (!dryRun) {
            int done = 0, total = found.values().stream().mapToInt(List::size).sum();
            for (Map.Entry<String, List<Row>> e : found.entrySet()) {
                String table = e.getKey().substring(0, e.getKey().indexOf('.'));
                String column = e.getKey().substring(e.getKey().indexOf('.') + 1);
                for (Row r : e.getValue()) {
                    progress.set("Rewriting row " + (++done) + " of " + total);
                    String next = r.value();
                    for (Map.Entry<String, String> m : moved.entrySet()) next = next.replace(m.getKey(), m.getValue());
                    if (next.equals(r.value())) continue;
                    if (rewrite(table, column, r.id(), r.value(), next)) rewritten++;
                    else skipped++;
                }
            }
        }
        List<String> withPhotos = found.entrySet().stream().map(e -> e.getKey() + " (" + e.getValue().size() + ")").toList();
        log.info("Photo move {}: {} addresses, {} already in the bucket, {} copied, {} failed, {} rows rewritten, {} skipped",
                dryRun ? "(dry run)" : "", urls.size(), already, copied, failed, rewritten, skipped);
        return new Report("move", dryRun, started, Instant.now(), columns.size(), withPhotos, urls.size(), already, copied,
                failed, problems.stream().limit(50).toList(), rewritten, skipped, 0);
    }

    Report undo() {
        Instant started = Instant.now();
        List<Map<String, Object>> moves = jdbc.queryForList(
                "SELECT id, table_name, column_name, row_id, old_value, new_value FROM photo_url_move WHERE undone_at IS NULL ORDER BY id DESC");
        int undone = 0, n = 0;
        List<String> problems = new ArrayList<>();
        for (Map<String, Object> m : moves) {
            progress.set("Undoing row " + (++n) + " of " + moves.size());
            String table = (String) m.get("table_name"), column = (String) m.get("column_name"), row = (String) m.get("row_id");
            long id = ((Number) m.get("id")).longValue();
            Boolean ok = tx.execute(s -> {
                int changed = jdbc.update("UPDATE " + q(table) + " SET " + q(column) + " = CAST(? AS " + type(table, column)
                        + ") WHERE id::text = ? AND " + q(column) + "::text = ?", m.get("old_value"), row, m.get("new_value"));
                if (changed == 1) jdbc.update("UPDATE photo_url_move SET undone_at = now() WHERE id = ?", id);
                return changed == 1;
            });
            if (Boolean.TRUE.equals(ok)) undone++;
            else if (problems.size() < 50) problems.add(table + "." + column + " row " + row + " changed since the move; left as it is");
        }
        return new Report("undo", false, started, Instant.now(), 0, List.of(), 0, 0, 0, 0, problems, 0, 0, undone);
    }

    /** The bucket key of a Cloudinary address: its path without the version segment; empty for an already-resized one. */
    static Optional<String> keyOf(String url, String cloud) {
        String marker = "res.cloudinary.com/" + cloud + "/image/upload/";
        int at = url.indexOf(marker);
        if (at < 0) return Optional.empty();
        String[] parts = url.substring(at + marker.length()).split("/");
        int start = 0;
        if (parts.length > 1 && TRANSFORMATION.matcher(parts[0]).matches() && !VERSION.matcher(parts[0]).matches()) {
            return Optional.empty();
        }
        if (VERSION.matcher(parts[0]).matches()) start = 1;
        String key = String.join("/", java.util.Arrays.copyOfRange(parts, start, parts.length));
        return key.isBlank() || key.contains("..") ? Optional.empty() : Optional.of(key);
    }

    private String copy(String url, String key, long[] size) {
        byte[] bytes;
        long announced;
        try {
            URI from = URI.create(url.replaceFirst("^https?://res\\.cloudinary\\.com", cloudinaryBase));
            HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(from).timeout(Duration.ofSeconds(90)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() != 200) {
                r.body().close();
                throw new IllegalStateException("Cloudinary answered HTTP " + r.statusCode());
            }
            announced = r.headers().firstValueAsLong("content-length").orElse(-1);
            try (InputStream in = r.body()) {
                bytes = in.readNBytes((int) MAX_BYTES + 1);
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not download it: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
        if (bytes.length > MAX_BYTES) throw new IllegalStateException("over 40 MB");
        if (announced >= 0 && announced != bytes.length) {
            throw new IllegalStateException("got " + bytes.length + " bytes of the " + announced + " announced");
        }
        S3PhotoStore.ImageType type = S3PhotoStore.imageType(bytes)
                .orElseThrow(() -> new IllegalStateException("the file is not a picture"));
        bucket.put(key, bytes, type.contentType());
        long stored = bucket.size(key).orElseThrow(() -> new IllegalStateException("not found in the bucket after storing it"));
        if (stored != bytes.length) throw new IllegalStateException("the bucket holds " + stored + " bytes, not " + bytes.length);
        size[0] = bytes.length;
        return S3PhotoStore.sha256Hex(bytes);
    }

    private void record(String url, String key, Long bytes, String sha, String status, String note) {
        jdbc.update("""
                INSERT INTO photo_url_copy (old_url, bucket_key, new_url, bytes, sha256, status, note, checked_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (old_url) DO UPDATE SET bucket_key = EXCLUDED.bucket_key, new_url = EXCLUDED.new_url,
                    bytes = EXCLUDED.bytes, sha256 = COALESCE(EXCLUDED.sha256, photo_url_copy.sha256),
                    status = EXCLUDED.status, note = EXCLUDED.note, checked_at = now()
                """, url, key, bucket.publicUrl(key), bytes, sha, status, note);
    }

    /** One row, only if it still holds what was read; the old and new value are kept for {@link #undo}. */
    private boolean rewrite(String table, String column, String id, String old, String next) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            int changed = jdbc.update("UPDATE " + q(table) + " SET " + q(column) + " = CAST(? AS " + type(table, column)
                    + ") WHERE id::text = ? AND " + q(column) + "::text = ?", next, id, old);
            if (changed != 1) return false;
            jdbc.update("INSERT INTO photo_url_move (table_name, column_name, row_id, old_value, new_value) VALUES (?, ?, ?, ?, ?)",
                    table, column, id, old, next);
            return true;
        }));
    }

    /** Every text-like column of every table in this schema that has an id, except the move's own tables. */
    private List<String[]> textColumns() {
        return jdbc.query("""
                SELECT c.table_name, c.column_name FROM information_schema.columns c
                JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                     AND t.table_type = 'BASE TABLE'
                WHERE c.table_schema = current_schema()
                  AND c.data_type IN ('text', 'character varying', 'json', 'jsonb')
                  AND c.table_name NOT IN ('photo_url_copy', 'photo_url_move', 'flyway_schema_history')
                  AND EXISTS (SELECT 1 FROM information_schema.columns i WHERE i.table_schema = c.table_schema
                              AND i.table_name = c.table_name AND i.column_name = 'id')
                ORDER BY c.table_name, c.column_name
                """, (rs, i) -> new String[] {rs.getString(1), rs.getString(2)})
                .stream().filter(c -> NAME.matcher(c[0]).matches() && NAME.matcher(c[1]).matches()).toList();
    }

    private String type(String table, String column) {
        String t = jdbc.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_schema = current_schema()"
                + " AND table_name = ? AND column_name = ?", String.class, table, column);
        return switch (t == null ? "" : t) {
            case "json" -> "json";
            case "jsonb" -> "jsonb";
            case "character varying" -> "varchar";
            default -> "text";
        };
    }

    private static String q(String name) {
        if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("Unexpected name " + name);
        return "\"" + name + "\"";
    }

    private record Row(String id, String value) {}
}
