package com.nbh.backend.integrations;

import com.nbh.backend.common.exception.ConflictException;
import com.nbh.backend.common.exception.UnprocessableException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Asking the storefront host to rebuild: only its own hook addresses, throttled by hand, the secret never repeated. */
class StorefrontRebuildServiceTest {

    private HttpServer render;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int status = 200;
    private String base;
    private volatile String coolifyMethod;
    private volatile String coolifyAuth;
    private volatile String coolifyQuery;
    private IntegrationSettingsService settings;

    @BeforeEach
    void start() throws Exception {
        render = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        render.createContext("/deploy/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            calls.incrementAndGet();
            byte[] out = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        render.createContext("/api/v1/deploy", exchange -> {
            coolifyMethod = exchange.getRequestMethod();
            coolifyAuth = exchange.getRequestHeaders().getFirst("Authorization");
            coolifyQuery = exchange.getRequestURI().getQuery();
            calls.incrementAndGet();
            byte[] out = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        render.start();
        base = "http://127.0.0.1:" + render.getAddress().getPort() + "/deploy/";
        settings = mock(IntegrationSettingsService.class);
    }

    @AfterEach
    void stop() {
        render.stop(0);
    }

    private StorefrontRebuildService service(String hookUrl) {
        when(settings.get(IntegrationKey.STOREFRONT_DEPLOY_HOOK_URL)).thenReturn(hookUrl);
        when(settings.has(IntegrationKey.STOREFRONT_DEPLOY_HOOK_URL)).thenReturn(!hookUrl.isBlank());
        return new StorefrontRebuildService(settings, base);
    }

    private StorefrontRebuildService onCoolify(String renderHook) {
        when(settings.get(IntegrationKey.STOREFRONT_DEPLOY_HOOK_URL)).thenReturn(renderHook);
        when(settings.has(IntegrationKey.STOREFRONT_DEPLOY_HOOK_URL)).thenReturn(!renderHook.isBlank());
        String deploy = "http://127.0.0.1:" + render.getAddress().getPort() + "/api/v1/deploy?uuid=site&force=true";
        return new StorefrontRebuildService(settings, base, deploy, "DEPLOYKEY");
    }

    @Test
    void onCoolifyTheButtonAsksItsDeployApiWithTheKeyAndNeedsNoHook() {
        var svc = onCoolify("");
        assertThat(svc.status().configured()).isTrue();
        var result = svc.rebuild("manual", true);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(coolifyMethod).isEqualTo("GET");
        assertThat(coolifyAuth).isEqualTo("Bearer DEPLOYKEY");
        assertThat(coolifyQuery).isEqualTo("uuid=site&force=true");
        assertThat(result.lastOk()).isTrue();
        assertThatThrownBy(() -> svc.rebuild("manual", true)).isInstanceOf(ConflictException.class);
    }

    @Test
    void coolifyWinsOverAnOldRenderHookAndARefusalNeverRepeatsTheKey() {
        status = 401;
        var svc = onCoolify(base + "srv-1?key=OLD");
        var result = svc.rebuild("nightly", false);
        assertThat(coolifyAuth).isEqualTo("Bearer DEPLOYKEY");
        assertThat(result.lastOk()).isFalse();
        assertThat(result.lastMessage()).contains("HTTP 401").doesNotContain("DEPLOYKEY").doesNotContain("uuid");
    }

    @Test
    void theNightlyJobRunsOnCoolifyWithoutAHook() {
        onCoolify("").nightly();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void aHookThatRenderAcceptsIsRecordedAsOk() {
        var svc = service(base + "srv-1?key=SECRETKEY");
        var result = svc.rebuild("manual", true);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(result.configured()).isTrue();
        assertThat(result.lastOk()).isTrue();
        assertThat(result.lastTrigger()).isEqualTo("manual");
        assertThat(result.lastRunAt()).isNotNull();
    }

    @Test
    void aRefusalFromRenderIsReportedWithoutRepeatingTheSecretAddress() {
        status = 404;
        var svc = service(base + "srv-1?key=SECRETKEY");
        var result = svc.rebuild("manual", true);
        assertThat(result.lastOk()).isFalse();
        assertThat(result.lastMessage()).contains("HTTP 404").doesNotContain("SECRETKEY").doesNotContain("srv-1");
    }

    @Test
    void nothingIsSentWhenNoHookIsSetOrItPointsAnywhereButRender() {
        assertThatThrownBy(() -> service("").rebuild("manual", true)).isInstanceOf(UnprocessableException.class);
        assertThatThrownBy(() -> service("https://evil.example.com/deploy/x").rebuild("manual", true))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("Render deploy hook");
        assertThat(calls.get()).isZero();
        assertThat(service("").status().configured()).isFalse();
    }

    @Test
    void aSecondButtonPressWithinAFewMinutesIsRefusedButTheNightlyRunIsNot() {
        var svc = service(base + "srv-1?key=K");
        svc.rebuild("manual", true);
        assertThatThrownBy(() -> svc.rebuild("manual", true)).isInstanceOf(ConflictException.class);
        assertThat(calls.get()).isEqualTo(1);
        svc.rebuild("nightly", false);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void theNightlyJobDoesNothingWhenNoHookIsSet() {
        service("").nightly();
        assertThat(calls.get()).isZero();
    }

    @Test
    void anUnreachableRenderIsReportedNotThrown() {
        render.stop(0);
        var svc = service(base + "srv-1?key=K");
        var result = svc.rebuild("manual", true);
        assertThat(result.lastOk()).isFalse();
        assertThat(result.lastMessage()).contains("couldn't be reached");
    }
}
