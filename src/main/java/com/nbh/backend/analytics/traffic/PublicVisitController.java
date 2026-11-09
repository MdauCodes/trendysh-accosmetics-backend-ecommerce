package com.nbh.backend.analytics.traffic;

import com.nbh.backend.analytics.traffic.TrafficDtos.EventRequest;
import com.nbh.backend.analytics.traffic.TrafficDtos.VisitRequest;
import com.nbh.backend.analytics.traffic.TrafficDtos.VisitResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The website reports a visit on its first page and a few things that happen in it (owner, 2026-11-09: know how much
 * comes from organic, social, direct, Google Ads). No sign-in; bots and floods are dropped without telling the caller,
 * so the shop never shows an error over this.
 */
@RestController
@RequestMapping("/api/v1/public/visits")
public class PublicVisitController {

    private final TrafficService traffic;
    private final VisitThrottle throttle;

    public PublicVisitController(TrafficService traffic, VisitThrottle throttle) {
        this.traffic = traffic;
        this.throttle = throttle;
    }

    @PostMapping
    public VisitResponse visit(@Valid @RequestBody VisitRequest request, HttpServletRequest http) {
        if (TrafficService.isBot(http.getHeader("User-Agent")) || !throttle.allow(address(http))) {
            return new VisitResponse(null);
        }
        return new VisitResponse(traffic.recordVisit(request));
    }

    @PostMapping("/{visitId}/events")
    public ResponseEntity<Void> event(@PathVariable UUID visitId, @Valid @RequestBody EventRequest request,
                                      HttpServletRequest http) {
        if (!TrafficService.isBot(http.getHeader("User-Agent")) && throttle.allow(address(http))) {
            traffic.recordEvent(visitId, request.kind(), request.value());
        }
        return ResponseEntity.noContent().build();
    }

    /** Behind the server's proxy the caller is the first X-Forwarded-For address; it is only used to count, never kept. */
    static String address(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return http.getRemoteAddr();
    }
}
