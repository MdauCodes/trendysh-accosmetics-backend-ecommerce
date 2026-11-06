package com.nbh.backend.delivery.transport;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A Metro operator: a sacco or company running minibuses (Super Metro, Nicco...), V82. */
@Getter
@Setter
@Entity
@Table(name = "transport_operator")
public class TransportOperator extends BaseEntity {

    @Column(nullable = false, length = 80)
    private String name;

    /** Where it runs, in staff's words ("Thika Road, Ruiru, Juja"); shown under its name. */
    @Column(length = 300)
    private String areas;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 100;

    /** How many orders chose it: the checkout lists the most chosen first. */
    @Column(nullable = false)
    private int picks;

    /** METRO and RIDER carry within Nairobi and Kiambu; OUTSIDE (buses and couriers) beyond them (V91). The first of {@link #kinds}. */
    @Column(nullable = false, length = 10)
    private String kind = "METRO";

    /**
     * Every section it is listed under at checkout, comma-separated (V98, owner 6 Nov 2026: one option can be in both
     * Nairobi/Kiambu and outside). Set through {@link #setSections}, which keeps {@link #kind} as the first.
     */
    @Column(nullable = false, length = 30)
    private String kinds = "METRO";

    /** Offered only to a customer who searches for it by name (V98: Pickup Mtaani, which the client discourages). */
    @Column(name = "on_request", nullable = false)
    private boolean onRequest;

    /** The sections in the order they were set, the first being {@link #kind}. */
    public java.util.List<String> getSections() {
        return java.util.Arrays.stream(kinds.split(",")).map(String::trim).filter(k -> !k.isEmpty()).distinct().toList();
    }

    /** Puts it in this one section only (what moving it used to be). */
    public void setKind(String kind) {
        setSections(java.util.List.of(kind));
    }

    public void setSections(java.util.List<String> sections) {
        java.util.List<String> clean = sections.stream().map(String::trim).distinct().toList();
        if (clean.isEmpty()) throw new IllegalArgumentException("Choose at least one section for this delivery option");
        this.kinds = String.join(",", clean);
        this.kind = clean.get(0);
    }

    /** Listed both within Nairobi and Kiambu (METRO or RIDER) and outside them: its places then each show in their own area. */
    public boolean inBothAreas() {
        java.util.List<String> s = getSections();
        return s.contains("OUTSIDE") && (s.contains("METRO") || s.contains("RIDER"));
    }
}
