package com.nbh.backend.order.words;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/** One word of the shop's list for reading customers' WhatsApp messages (see V103__order_words.sql). */
@Getter
@Setter
@Entity
@Table(name = "order_word")
public class OrderWord extends BaseEntity {

    public enum Kind { NICKNAME, SAME_AS, IGNORE }

    public enum Status { APPROVED, SUGGESTED }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Kind kind;

    @Column(nullable = false, length = 120)
    private String phrase;

    @Column(length = 200)
    private String meaning;

    @Column(name = "variant_id")
    private UUID variantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.APPROVED;

    @Column(name = "added_by", length = 120)
    private String addedBy;
}
