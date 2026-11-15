package com.nbh.backend.order.words;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.order.words.OrderWordDtos.OrderWordDto;
import com.nbh.backend.order.words.OrderWordDtos.OrderWordInput;
import com.nbh.backend.order.words.OrderWordDtos.Suggestion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The shop's word list for reading customers' WhatsApp messages (owner, 15 Nov 2026). The dashboard's paste box reads the
 * approved words; staff approve, change, add and remove them; a correction staff make in the paste box comes back here as
 * a suggestion, so the list learns the customers' own words without anyone typing them in.
 */
@Service
public class OrderWordService {

    static final String ALREADY = "That word is already on the list.";
    static final String GONE = "That word is no longer on the list.";

    private final OrderWordRepository words;
    private final ProductVariantRepository variants;

    public OrderWordService(OrderWordRepository words, ProductVariantRepository variants) {
        this.words = words;
        this.variants = variants;
    }

    @Transactional(readOnly = true)
    public List<OrderWordDto> list() {
        List<OrderWord> all = words.findAllByOrderByKindAscPhraseAsc();
        List<UUID> ids = all.stream().map(OrderWord::getVariantId).filter(Objects::nonNull).distinct().toList();
        Map<UUID, ProductVariant> byId = variants.findAllById(ids).stream()
                .collect(Collectors.toMap(ProductVariant::getId, Function.identity()));
        return all.stream().map(w -> toDto(w, byId.get(w.getVariantId()))).toList();
    }

    @Transactional
    public OrderWordDto add(OrderWordInput input, String staffName) {
        String phrase = tidy(input.phrase());
        if (words.findFirstByKindAndPhraseIgnoreCase(input.kind(), phrase).isPresent()) {
            throw new UnprocessableException(ALREADY);
        }
        OrderWord word = new OrderWord();
        word.setKind(input.kind());
        word.setStatus(OrderWord.Status.APPROVED);
        word.setAddedBy(cut(staffName));
        return save(word, input, phrase);
    }

    @Transactional
    public OrderWordDto update(UUID id, OrderWordInput input) {
        OrderWord word = words.findById(id).orElseThrow(() -> new NotFoundException(GONE));
        String phrase = tidy(input.phrase());
        words.findFirstByKindAndPhraseIgnoreCase(input.kind(), phrase)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new UnprocessableException(ALREADY);
                });
        word.setKind(input.kind());
        return save(word, input, phrase);
    }

    @Transactional
    public OrderWordDto approve(UUID id) {
        OrderWord word = words.findById(id).orElseThrow(() -> new NotFoundException(GONE));
        word.setStatus(OrderWord.Status.APPROVED);
        words.save(word);
        return toDto(word, word.getVariantId() == null ? null : variants.findById(word.getVariantId()).orElse(null));
    }

    @Transactional
    public void delete(UUID id) {
        words.deleteById(id);
    }

    /**
     * Staff chose a product for a line the customer wrote: remember those words as a nickname for it, waiting for
     * approval. Nothing happens when the phrase is already a nickname (approved or suggested), is too short or too long to
     * be a useful one, or the product option is gone.
     */
    @Transactional
    public void suggest(Suggestion suggestion, String staffName) {
        String phrase = tidy(suggestion.phrase()).toLowerCase(Locale.ROOT);
        if (phrase.length() < 3 || phrase.length() > 80 || !phrase.matches(".*\\p{L}.*")) return;
        if (words.findFirstByKindAndPhraseIgnoreCase(OrderWord.Kind.NICKNAME, phrase).isPresent()) return;
        if (!variants.existsById(suggestion.variantId())) return;
        OrderWord word = new OrderWord();
        word.setKind(OrderWord.Kind.NICKNAME);
        word.setPhrase(phrase);
        word.setVariantId(suggestion.variantId());
        word.setStatus(OrderWord.Status.SUGGESTED);
        word.setAddedBy(cut(staffName));
        words.save(word);
    }

    private OrderWordDto save(OrderWord word, OrderWordInput input, String phrase) {
        word.setPhrase(phrase);
        ProductVariant variant = null;
        switch (input.kind()) {
            case NICKNAME -> {
                if (input.variantId() == null) throw new UnprocessableException("Choose the product this word means.");
                variant = variants.findById(input.variantId())
                        .orElseThrow(() -> new UnprocessableException("That product option is no longer in the catalogue."));
                word.setVariantId(variant.getId());
                word.setMeaning(null);
            }
            case SAME_AS -> {
                String meaning = input.meaning() == null ? "" : tidy(input.meaning());
                if (meaning.isEmpty()) throw new UnprocessableException("Say which word it means.");
                if (meaning.equalsIgnoreCase(phrase)) throw new UnprocessableException("A word cannot mean itself.");
                word.setMeaning(meaning);
                word.setVariantId(null);
            }
            case IGNORE -> {
                word.setMeaning(null);
                word.setVariantId(null);
            }
        }
        words.save(word);
        return toDto(word, variant);
    }

    private static OrderWordDto toDto(OrderWord w, ProductVariant variant) {
        return new OrderWordDto(w.getId(), w.getKind().name(), w.getPhrase(), w.getMeaning(), w.getVariantId(),
                variant == null ? null : variant.displayLabel(),
                variant == null ? null : variant.getSellingPrice(), w.getStatus().name(), w.getAddedBy());
    }

    private static String tidy(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }

    private static String cut(String text) {
        return text == null ? null : text.length() <= 120 ? text : text.substring(0, 120);
    }
}
