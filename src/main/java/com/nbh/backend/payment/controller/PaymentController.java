package com.nbh.backend.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.payment.entity.PaymentRecord;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import com.nbh.backend.payment.service.PaymentService;
import com.nbh.backend.staff.entity.StaffUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * {@link #confirmManual}/{@link #failManual}: a staff action for a payment seen on the M-PESA or bank statement, cash,
 * or a bank transfer. They call the same {@link PaymentService} methods as every other confirmation, so there is only
 * one place an order actually moves out of PENDING_PAYMENT.
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentRecordRepository paymentRecordRepository;
    // §4 C9: the app-wide bean (jackson-datatype-jsr310 registered, consistent
    // Instant serialisation) instead of a bare `new ObjectMapper()` — matters
    // here specifically because toJson()'s output is stored in payment_record's
    // jsonb columns, read back months later to settle a payment dispute.
    private final ObjectMapper objectMapper;

    public PaymentController(PaymentService paymentService,
                              PaymentRecordRepository paymentRecordRepository,
                              ObjectMapper objectMapper) {
        this.paymentService = paymentService;
        this.paymentRecordRepository = paymentRecordRepository;
        this.objectMapper = objectMapper;
    }

    @PreAuthorize("hasAnyAuthority('PERM_PAYMENT_MANAGE','PERM_ORDER_MANAGE_ALL')")
    @PostMapping("/{paymentRecordId}/confirm-manual")
    public void confirmManual(@PathVariable UUID paymentRecordId, @RequestBody ManualConfirmRequest request,
                              @AuthenticationPrincipal StaffUser staff) {
        Map<String, String> note = new LinkedHashMap<>();
        note.put("type", "manual_staff_confirmation");
        // Who confirmed it, kept with the payment: the money trail says who made this payment count.
        note.put("by", staff == null ? null : staff.getFullName());
        note.put("notes", request.notes());
        paymentService.confirmSuccess(paymentRecordId, request.reference(), toJson(note), request.method(),
                Boolean.TRUE.equals(request.revive()));
    }

    @PreAuthorize("hasAnyAuthority('PERM_PAYMENT_MANAGE','PERM_ORDER_MANAGE_ALL')")
    @PostMapping("/{paymentRecordId}/fail-manual")
    public void failManual(@PathVariable UUID paymentRecordId, @RequestBody ManualFailRequest request,
                           @AuthenticationPrincipal StaffUser staff) {
        Map<String, String> note = new LinkedHashMap<>();
        note.put("type", "manual_staff_failure");
        note.put("by", staff == null ? null : staff.getFullName());
        note.put("reason", request.reason());
        paymentService.markFailed(paymentRecordId, request.reason(), toJson(note));
    }

    /** rawRequest/rawResponse are mapped as jsonb — every caller must go through here, never a bare toString(). */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise payment record detail to JSON", e);
        }
    }

    /**
     * {@code method} optional: how the customer actually paid (MPESA/BANK/CASH); omitted keeps the record's own.
     * {@code revive} true only for "the customer did pay" on an order whose payment staff marked failed: without it a
     * failed payment is refused (a stale Confirm click must not bring back a payment someone failed on purpose).
     */
    public record ManualConfirmRequest(String reference, String notes, PaymentRecord.PaymentMethod method,
                                       Boolean revive) {}

    public record ManualFailRequest(String reason) {}
}
