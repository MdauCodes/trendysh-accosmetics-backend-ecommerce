package com.nbh.backend.integrations;

import com.nbh.backend.common.exception.ConflictException;
import com.nbh.backend.common.exception.UnprocessableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;

/**
 * Asks the storefront's host to rebuild the site. The storefront prerenders every public page from the live
 * catalogue at build time (so search engines and AI assistants read real titles, text and structured data), which means
 * it only reflects new products and approved descriptions after a rebuild. The backend does this itself every midnight
 * (Nairobi time), and staff can do it on demand from Settings, Integrations. The deploy hook address is a secret saved
 * there (or the STOREFRONT_DEPLOY_HOOK_URL environment variable): it is never logged or returned.
 *
 * <p>Since 8 Nov 2026 the site is built by Coolify on the same server as the backend (owner: the frontend moves off
 * Render). Then the server's own settings name Coolify's deploy address for the site and a deploy-only key
 * (STOREFRONT_COOLIFY_DEPLOY_URL, STOREFRONT_COOLIFY_TOKEN); staff can't change either, and they win over a saved Render
 * hook. Otherwise only Render's own deploy-hook addresses are accepted, so a mistyped or malicious value can't make the
 * server call somewhere else. The last result is kept in memory (it resets when the server restarts).
 */
@Slf4j
@Service
public class StorefrontRebuildService {

    public record Status(boolean configured, Instant lastRunAt, String lastTrigger, Boolean lastOk, String lastMessage) {}

    static final Duration MIN_GAP = Duration.ofMinutes(5);

    private final IntegrationSettingsService settings;
    private final String allowedPrefix;
    private final String coolifyDeployUrl;
    private final String coolifyToken;
    private final RestTemplate http;

    private volatile Instant lastRunAt;
    private volatile String lastTrigger;
    private volatile Boolean lastOk;
    private volatile String lastMessage;

    public StorefrontRebuildService(IntegrationSettingsService settings, String allowedPrefix) {
        this(settings, allowedPrefix, "", "");
    }

    @Autowired
    public StorefrontRebuildService(IntegrationSettingsService settings,
                                    @Value("${app.storefront.deploy-hook-prefix:https://api.render.com/deploy/}") String allowedPrefix,
                                    @Value("${app.storefront.coolify-deploy-url:}") String coolifyDeployUrl,
                                    @Value("${app.storefront.coolify-token:}") String coolifyToken) {
        this.settings = settings;
        this.allowedPrefix = allowedPrefix;
        this.coolifyDeployUrl = coolifyDeployUrl == null ? "" : coolifyDeployUrl.trim();
        this.coolifyToken = coolifyToken == null ? "" : coolifyToken.trim();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(15_000);
        this.http = new RestTemplate(factory);
    }

    public Status status() {
        return new Status(onCoolify() || settings.has(IntegrationKey.STOREFRONT_DEPLOY_HOOK_URL), lastRunAt, lastTrigger,
                lastOk, lastMessage);
    }

    /** The site is built by Coolify on this server: its deploy address and key are both in the server's settings. */
    private boolean onCoolify() {
        return !coolifyDeployUrl.isEmpty() && !coolifyToken.isEmpty();
    }

    /** Every midnight, Nairobi time (the storefront reads the catalogue as it is when the build runs). */
    @Scheduled(cron = "0 0 0 * * *", zone = "Africa/Nairobi")
    public void nightly() {
        if (!onCoolify() && !settings.has(IntegrationKey.STOREFRONT_DEPLOY_HOOK_URL)) {
            log.info("Nightly storefront rebuild skipped: no deploy hook is set (Settings, Integrations).");
            return;
        }
        try {
            rebuild("nightly", false);
        } catch (RuntimeException e) {
            log.warn("Nightly storefront rebuild failed: {}", e.getMessage());
        }
    }

    /**
     * Asks Render to rebuild now. {@code throttle} (staff pressing the button) refuses a second request within a few
     * minutes: each rebuild costs build minutes and a burst of clicks would queue several.
     */
    public synchronized Status rebuild(String trigger, boolean throttle) {
        if (onCoolify()) return rebuildOnCoolify(trigger, throttle);
        String hook = settings.get(IntegrationKey.STOREFRONT_DEPLOY_HOOK_URL);
        if (hook.isBlank()) {
            throw new UnprocessableException("The storefront deploy hook isn't set yet. Add it in Settings, Integrations.");
        }
        if (!hook.startsWith(allowedPrefix)) {
            throw new UnprocessableException("That isn't a Render deploy hook address (it should start with " + allowedPrefix + ").");
        }
        Instant now = Instant.now();
        refuseIfJustAsked(throttle, now);
        try {
            http.postForEntity(hook, null, String.class);
            record(now, trigger, true, "Render accepted the rebuild.");
        } catch (HttpStatusCodeException e) {
            record(now, trigger, false, "Render answered HTTP " + e.getStatusCode().value() + ": check the deploy hook is current.");
        } catch (ResourceAccessException e) {
            record(now, trigger, false, "Render couldn't be reached.");
        }
        return status();
    }

    private void refuseIfJustAsked(boolean throttle, Instant now) {
        if (throttle && lastRunAt != null && Boolean.TRUE.equals(lastOk) && Duration.between(lastRunAt, now).compareTo(MIN_GAP) < 0) {
            throw new ConflictException("A rebuild was started a moment ago. Give it a few minutes to finish.");
        }
    }

    /** Coolify's deploy API, with the deploy-only key; neither is ever logged or returned. */
    private Status rebuildOnCoolify(String trigger, boolean throttle) {
        Instant now = Instant.now();
        refuseIfJustAsked(throttle, now);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(coolifyToken);
        try {
            http.exchange(coolifyDeployUrl, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            record(now, trigger, true, "The website host accepted the rebuild.");
        } catch (HttpStatusCodeException e) {
            record(now, trigger, false, "The website host answered HTTP " + e.getStatusCode().value()
                    + ": check its deploy key on the server.");
        } catch (ResourceAccessException e) {
            record(now, trigger, false, "The website host couldn't be reached.");
        }
        return status();
    }

    private void record(Instant at, String trigger, boolean ok, String message) {
        lastRunAt = at;
        lastTrigger = trigger;
        lastOk = ok;
        lastMessage = message;
        log.info("Storefront rebuild ({}): {}", trigger, message);
    }
}
