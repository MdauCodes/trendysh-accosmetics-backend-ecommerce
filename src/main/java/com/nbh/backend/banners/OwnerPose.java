package com.nbh.backend.banners;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One pose of the owner for the banners (V96, owner 2026-11-04), made by Gemini from her real photos ({@link OwnerPhoto})
 * on a plain backdrop, and approved by a person before any banner may use it. {@code layout} says how to place and crop
 * it (see {@link OwnerPoseService.Layout}); {@code edgeHex} is the backdrop's colour as measured, which the banner fills
 * with so she blends in.
 */
@Getter
@Setter
@Entity
@Table(name = "owner_pose")
public class OwnerPose extends BaseEntity {

    public enum Mood { LOOK, AMAZED, SURPRISED, MISS_OUT, WELCOME, POINTING }

    /** How much of her: head and shoulders, waist up, knee up (no feet: the default), or full height (only when asked). */
    public enum Framing { HEAD, WAIST, KNEE, FULL }

    public enum Side { LEFT, RIGHT }

    public enum Outfit { PINK_BLOUSE, BLAZER }

    public enum Status { MAKING, READY, APPROVED, REJECTED, FAILED }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Mood mood;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private Framing framing;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 5)
    private Side side;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Outfit outfit;

    @Column(name = "backdrop_hex", nullable = false, length = 7)
    private String backdropHex;

    @Column(name = "edge_hex", length = 7)
    private String edgeHex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 9)
    private Status status;

    @Column(name = "image_url", columnDefinition = "TEXT")
    private String imageUrl;

    private Integer width;

    private Integer height;

    /** The layout as JSON (OwnerPoseService.Layout). */
    @Column(columnDefinition = "TEXT")
    private String layout;

    @Column(columnDefinition = "TEXT")
    private String brief;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(length = 60)
    private String model;

    @Column(name = "created_by", length = 200)
    private String createdBy;

    @Column(name = "approved_by", length = 200)
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;
}
