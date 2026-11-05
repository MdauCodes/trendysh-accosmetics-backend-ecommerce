package com.nbh.backend.catalog.controller;

import com.nbh.backend.catalog.service.MerchantFeedService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * The product feed Google Merchant Center fetches (owner, 2026-11-06). Public, like the rest of the catalogue: it holds only
 * what the storefront shows anyone.
 */
@RestController
public class MerchantFeedController {

    private final MerchantFeedService feed;

    public MerchantFeedController(MerchantFeedService feed) {
        this.feed = feed;
    }

    @GetMapping(value = "/api/v1/catalog/merchant-feed.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> merchantFeed() {
        return ResponseEntity.ok()
                .contentType(new MediaType("application", "xml", java.nio.charset.StandardCharsets.UTF_8))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .body(feed.feed());
    }
}
