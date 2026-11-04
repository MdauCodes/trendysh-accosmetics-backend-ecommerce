package com.nbh.backend.banners;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A real photo of the shop's owner (V96), shown to Gemini as who to draw when it makes her poses. */
@Getter
@Setter
@Entity
@Table(name = "owner_photo")
public class OwnerPhoto extends BaseEntity {

    @Column(name = "image_url", nullable = false, columnDefinition = "TEXT")
    private String imageUrl;

    @Column(length = 200)
    private String note;
}
