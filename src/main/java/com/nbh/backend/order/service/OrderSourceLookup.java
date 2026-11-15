package com.nbh.backend.order.service;

import com.nbh.backend.order.dto.OrderSourceDto;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Reads where orders came from (the columns {@code TrafficService.stampOrder} fills at checkout), for a page of the
 * staff order list in one query, or for one order.
 */
@Component
public class OrderSourceLookup {

    private final NamedParameterJdbcTemplate jdbc;

    public OrderSourceLookup(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Map<UUID, OrderSourceDto> forOrders(Collection<UUID> ids) {
        Map<UUID, OrderSourceDto> out = new HashMap<>();
        if (ids == null || ids.isEmpty()) return out;
        jdbc.query("""
                SELECT id, last_channel, last_source, last_campaign, first_channel, first_source, placed_by
                FROM customer_order WHERE id IN (:ids)
                """, new MapSqlParameterSource("ids", ids), rs -> {
            out.put(rs.getObject("id", UUID.class), OrderSourceDto.of(rs.getString("last_channel"),
                    rs.getString("last_source"), rs.getString("last_campaign"), rs.getString("first_channel"),
                    rs.getString("first_source"), rs.getString("placed_by")));
        });
        return out;
    }

    public OrderSourceDto forOrder(UUID id) {
        return forOrders(java.util.List.of(id)).get(id);
    }
}
