package com.nbh.backend.order.words;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderWordRepository extends JpaRepository<OrderWord, UUID> {

    List<OrderWord> findAllByOrderByKindAscPhraseAsc();

    Optional<OrderWord> findFirstByKindAndPhraseIgnoreCase(OrderWord.Kind kind, String phrase);

    long countByStatus(OrderWord.Status status);
}
