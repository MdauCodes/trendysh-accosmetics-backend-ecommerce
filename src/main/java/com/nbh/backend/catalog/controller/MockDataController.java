package com.nbh.backend.catalog.controller;

import com.nbh.backend.catalog.dto.MockDataStatusDto;
import com.nbh.backend.catalog.service.MockDataSeederService;
import com.nbh.backend.staff.entity.StaffUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Developer-only demo/mock catalogue toggle (2026-09-29) — see {@link MockDataSeederService}'s
 * javadoc for the full design. {@code hasRole('DEVELOPER')}, not {@code hasAuthority(...)}: the
 * client's own SUPER_ADMIN account holds the identical permission set as DEVELOPER (confirmed via
 * StaffRoleSeeder's boot log), so only the role check itself — not a permission — keeps this
 * control out of the client's hands.
 */
@RestController
@RequestMapping("/api/v1/admin/mock-data")
@PreAuthorize("hasRole('DEVELOPER')")
public class MockDataController {

    private final MockDataSeederService seederService;
    private final com.nbh.backend.order.service.TestOrderClearService testOrders;

    public MockDataController(MockDataSeederService seederService,
            com.nbh.backend.order.service.TestOrderClearService testOrders) {
        this.seederService = seederService;
        this.testOrders = testOrders;
    }

    @GetMapping("/test-orders")
    public java.util.List<com.nbh.backend.order.service.TestOrderClearService.TestOrder> testOrders() {
        return testOrders.waiting();
    }

    /** Permanently removes the test orders from before the launch; the word guards against a stray request. */
    @PostMapping("/test-orders/clear")
    public com.nbh.backend.order.service.TestOrderClearService.Cleared clearTestOrders(
            @org.springframework.web.bind.annotation.RequestBody ClearRequest body) {
        if (!"CLEAR".equals(body.confirm())) throw new IllegalArgumentException("Type CLEAR to confirm");
        return testOrders.clearAll();
    }

    public record ClearRequest(String confirm) {}

    @GetMapping("/status")
    public MockDataStatusDto status() {
        return seederService.status();
    }

    @PostMapping("/enable")
    public MockDataStatusDto enable(@AuthenticationPrincipal StaffUser staff) {
        return seederService.enable(staff);
    }

    @PostMapping("/disable")
    public MockDataStatusDto disable() {
        return seederService.disable();
    }
}
