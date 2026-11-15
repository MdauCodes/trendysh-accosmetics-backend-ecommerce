package com.nbh.backend.order.words;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public final class OrderWordDtos {

    private OrderWordDtos() {}

    /**
     * A word as staff see it. For a NICKNAME, the product option it stands for ({@code productLabel}, {@code price}),
     * read from the catalogue, so the list says what the word will add.
     */
    public record OrderWordDto(UUID id, String kind, String phrase, String meaning, UUID variantId, String productLabel,
                               BigDecimal price, String status, String addedBy) {}

    /** Adding or changing a word. {@code meaning} for SAME_AS, {@code variantId} for NICKNAME. */
    public record OrderWordInput(@NotNull OrderWord.Kind kind, @NotBlank @Size(max = 120) String phrase,
                                 @Size(max = 200) String meaning, UUID variantId) {}

    /** What a staff correction teaches: the words the customer wrote mean this product option. */
    public record Suggestion(@NotBlank @Size(max = 120) String phrase, @NotNull UUID variantId) {}
}
