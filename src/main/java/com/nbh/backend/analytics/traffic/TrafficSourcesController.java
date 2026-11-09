package com.nbh.backend.analytics.traffic;

import com.nbh.backend.analytics.traffic.TrafficDtos.SourcesReport;
import com.nbh.backend.analytics.traffic.TrafficDtos.SpendRequest;
import com.nbh.backend.analytics.traffic.TrafficDtos.SpendRow;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** Admin > Analytics > Sources: visits, orders and paid revenue by where shoppers came from, next to ad spend. */
@RestController
@RequestMapping("/api/v1/admin/analytics")
public class TrafficSourcesController {

    /** Longest period one report covers. */
    private static final int MAX_DAYS = 400;

    private final TrafficService traffic;

    public TrafficSourcesController(TrafficService traffic) {
        this.traffic = traffic;
    }

    @PreAuthorize("hasAuthority('PERM_ANALYTICS_VIEW')")
    @GetMapping("/sources")
    public SourcesReport sources(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                 @RequestParam(defaultValue = "last") String touch) {
        LocalDate start = from.isBefore(to.minusDays(MAX_DAYS)) ? to.minusDays(MAX_DAYS) : from;
        return traffic.report(start, to, "first".equalsIgnoreCase(touch));
    }

    @PreAuthorize("hasAuthority('PERM_ANALYTICS_VIEW')")
    @GetMapping("/spend")
    public List<SpendRow> spend(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return traffic.spend(from, to);
    }

    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    @PutMapping("/spend")
    public ResponseEntity<Void> saveSpend(@Valid @RequestBody SpendRequest request, Authentication auth) {
        TrafficChannel channel = TrafficChannel.parse(request.channel());
        if (channel == null || !channel.paid()) {
            return ResponseEntity.badRequest().build();
        }
        traffic.saveSpend(request.day(), channel, request.amountKes(), request.note(), auth == null ? null : auth.getName());
        return ResponseEntity.noContent().build();
    }
}
