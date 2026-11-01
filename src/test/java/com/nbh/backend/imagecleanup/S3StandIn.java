package com.nbh.backend.imagecleanup;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A local stand-in for the photo bucket's S3 API (path-style, one bucket). Every request's signature is worked out again
 * from what actually arrived (the signed headers' values, the path, the body's hash) and refused with 403 when it does
 * not match, so a header that is signed but not sent, or sent differently, fails the test.
 */
final class S3StandIn implements AutoCloseable {

    static final String ACCESS = "test-access";
    static final String SECRET = "test-secret";
    private static final Pattern AUTH = Pattern.compile(
            "AWS4-HMAC-SHA256 Credential=([^/]+)/(\\d{8})/([^/]+)/s3/aws4_request, SignedHeaders=([^,]+), Signature=([0-9a-f]{64})");

    final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    final Map<String, Map<String, String>> metadata = new ConcurrentHashMap<>();
    final List<String> requests = new CopyOnWriteArrayList<>();
    private final HttpServer server;
    private final String bucket;

    S3StandIn(String bucket) throws IOException {
        this.bucket = bucket;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange ex) throws IOException {
        byte[] body = ex.getRequestBody().readAllBytes();
        String method = ex.getRequestMethod();
        String rawPath = ex.getRequestURI().getRawPath();
        requests.add(method + " " + rawPath);
        if (!signatureMatches(ex, method, rawPath, body)) {
            reply(ex, 403, new byte[0]);
            return;
        }
        String prefix = "/" + bucket + "/";
        if (!rawPath.startsWith(prefix)) {
            reply(ex, 404, new byte[0]);
            return;
        }
        String key = java.net.URLDecoder.decode(rawPath.substring(prefix.length()).replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
        switch (method) {
            case "PUT" -> {
                objects.put(key, body);
                Map<String, String> meta = new TreeMap<>();
                ex.getRequestHeaders().forEach((name, values) -> meta.put(name.toLowerCase(), values.get(0)));
                metadata.put(key, meta);
                reply(ex, 200, new byte[0]);
            }
            case "HEAD" -> {
                byte[] stored = objects.get(key);
                if (stored == null) {
                    ex.sendResponseHeaders(404, -1);
                } else {
                    ex.getResponseHeaders().add("Content-Length", String.valueOf(stored.length));
                    ex.sendResponseHeaders(200, -1);
                }
                ex.close();
            }
            case "DELETE" -> {
                objects.remove(key);
                reply(ex, 204, null);
            }
            default -> reply(ex, 405, new byte[0]);
        }
    }

    private boolean signatureMatches(HttpExchange ex, String method, String rawPath, byte[] body) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        Matcher m = auth == null ? null : AUTH.matcher(auth);
        if (m == null || !m.matches() || !m.group(1).equals(ACCESS)) return false;
        String amzDate = ex.getRequestHeaders().getFirst("x-amz-date");
        String payloadHash = ex.getRequestHeaders().getFirst("x-amz-content-sha256");
        if (amzDate == null || payloadHash == null || !payloadHash.equals(S3PhotoStore.sha256Hex(body))) return false;
        Map<String, String> signed = new TreeMap<>();
        for (String name : m.group(4).split(";")) {
            String value = ex.getRequestHeaders().getFirst(name);
            if (value == null) return false;
            signed.put(name, value);
        }
        String expected = S3PhotoStore.authorization(method, rawPath, "", signed, payloadHash, amzDate, m.group(3), ACCESS, SECRET);
        return expected.equals(auth);
    }

    private static void reply(HttpExchange ex, int status, byte[] out) throws IOException {
        if (out == null) {
            ex.sendResponseHeaders(status, -1);
        } else {
            ex.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) ex.getResponseBody().write(out);
        }
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
