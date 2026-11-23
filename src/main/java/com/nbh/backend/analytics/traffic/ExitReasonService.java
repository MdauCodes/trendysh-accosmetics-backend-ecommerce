package com.nbh.backend.analytics.traffic;

import com.nbh.backend.analytics.traffic.TrafficDtos.DropoffFunnelRow;
import com.nbh.backend.analytics.traffic.TrafficDtos.DropoffNote;
import com.nbh.backend.analytics.traffic.TrafficDtos.DropoffReasonRow;
import com.nbh.backend.analytics.traffic.TrafficDtos.DropoffReport;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Why shoppers leave checkout: the answers the website sends, and the staff report that puts them next to the funnel. */
@Service
public class ExitReasonService {

    static final Set<String> STEPS = Set.of("DETAILS", "REVIEW", "CART");
    static final Set<String> REASONS = Set.of("JUST_LOOKING", "DELIVERY_FEE_HIGH", "DELIVERY_TOO_SLOW", "PREFER_WHATSAPP",
            "PAYMENT_CONFUSING", "SOMETHING_BROKE", "PRICE_HIGH", "OTHER", "NO_ANSWER");
    static final int MAX_PER_VISIT = 3;
    static final int NOTE_MAX = 300;

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+");
    /** 7 or more digits, allowing spaces, dashes and a leading plus between them. */
    private static final Pattern NUMBER = Pattern.compile("\\+?\\d(?:[\\s-]*\\d){6,}");

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public ExitReasonService(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Trimmed, one space between words, personal data replaced, at most 300 characters; null when nothing is left. */
    static String scrub(String note) {
        if (note == null) return null;
        String v = note.trim().replaceAll("\\s+", " ");
        v = EMAIL.matcher(v).replaceAll("[email]");
        v = NUMBER.matcher(v).replaceAll("[number]");
        if (v.isEmpty()) return null;
        return v.length() > NOTE_MAX ? v.substring(0, NOTE_MAX).trim() : v;
    }

    /** False when nothing was kept (unknown step or reason, or the visit already gave its three). */
    @Transactional
    public boolean record(UUID visitId, String step, String reason, String note) {
        String s = step == null ? "" : step.trim().toUpperCase(Locale.ROOT);
        String r = reason == null ? "" : reason.trim().toUpperCase(Locale.ROOT);
        if (!STEPS.contains(s) || !REASONS.contains(r)) return false;
        var p = new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("step", s).addValue("reason", r).addValue("note", scrub(note))
                .addValue("at", Timestamp.from(Instant.now(clock)));
        if (visitId == null) return insertUnlinked(p);
        p.addValue("visit", visitId).addValue("max", MAX_PER_VISIT);
        // Locking the visit row also tells us it exists, and stops two quick answers both slipping in as the third.
        if (jdbc.queryForList("SELECT id FROM site_visit WHERE id = :visit FOR UPDATE", p).isEmpty()) {
            return insertUnlinked(p);
        }
        return jdbc.update("""
                INSERT INTO checkout_exit_reason (id, visit_id, step, reason, note, created_at)
                SELECT :id, :visit, :step, :reason, :note, :at
                WHERE (SELECT COUNT(*) FROM checkout_exit_reason WHERE visit_id = :visit) < :max
                """, p) > 0;
    }

    private boolean insertUnlinked(MapSqlParameterSource p) {
        return jdbc.update("""
                INSERT INTO checkout_exit_reason (id, visit_id, step, reason, note, created_at)
                VALUES (:id, NULL, :step, :reason, :note, :at)
                """, p) > 0;
    }

    @Transactional(readOnly = true)
    public DropoffReport dropoff(LocalDate from, LocalDate to) {
        LocalDate end = to.isBefore(from) ? from : to;
        var p = new MapSqlParameterSource()
                .addValue("a", Timestamp.from(from.atStartOfDay(TrafficService.NAIROBI).toInstant()))
                .addValue("b", Timestamp.from(end.plusDays(1).atStartOfDay(TrafficService.NAIROBI).toInstant()));

        Map<String, long[]> funnel = new LinkedHashMap<>();
        jdbc.query("""
                SELECT v.channel, e.kind, COUNT(*) AS n, COUNT(DISTINCT e.visit_id) AS shoppers
                FROM site_event e JOIN site_visit v ON v.id = e.visit_id
                WHERE e.at >= :a AND e.at < :b
                  AND e.kind IN ('CHECKOUT_STARTED','CHECKOUT_DETAILS_DONE','CHECKOUT_PLACE_TAPPED','CHECKOUT_PLACE_FAILED','ORDER_PAID_CLAIM_SENT')
                GROUP BY 1, 2
                """, p, rs -> {
            long[] f = funnel.computeIfAbsent(rs.getString("channel"), c -> new long[6]);
            long n = rs.getLong("n");
            long shoppers = rs.getLong("shoppers");
            switch (rs.getString("kind")) {
                case "CHECKOUT_STARTED" -> f[0] += n;
                case "CHECKOUT_DETAILS_DONE" -> f[1] += shoppers;
                case "CHECKOUT_PLACE_TAPPED" -> f[2] += shoppers;
                case "CHECKOUT_PLACE_FAILED" -> f[3] += n;
                case "ORDER_PAID_CLAIM_SENT" -> f[5] += shoppers;
                default -> { }
            }
        });
        jdbc.query("""
                SELECT COALESCE(o.last_channel, CASE WHEN o.placed_by IS NOT NULL THEN 'STAFF' ELSE 'UNKNOWN' END) AS channel,
                       COUNT(*) AS orders
                FROM customer_order o
                WHERE o.created_at >= :a AND o.created_at < :b AND %s
                GROUP BY 1
                """.formatted(TrafficService.REAL_OR_DEMO), p, rs -> {
            funnel.computeIfAbsent(rs.getString("channel"), c -> new long[6])[4] += rs.getLong("orders");
        });
        List<DropoffFunnelRow> funnelRows = new ArrayList<>();
        funnel.forEach((ch, f) -> funnelRows.add(new DropoffFunnelRow(ch, f[0], f[1], f[2], f[3], f[4], f[5])));
        funnelRows.sort((x, y) -> Long.compare(y.checkoutStarted(), x.checkoutStarted()));

        List<DropoffReasonRow> reasons = jdbc.query("""
                SELECT COALESCE(v.channel, 'UNKNOWN') AS channel, x.step, x.reason, COUNT(*) AS n
                FROM checkout_exit_reason x LEFT JOIN site_visit v ON v.id = x.visit_id
                WHERE x.created_at >= :a AND x.created_at < :b
                GROUP BY 1, 2, 3 ORDER BY n DESC, 1, 2, 3
                """, p, (rs, i) -> new DropoffReasonRow(rs.getString("channel"), rs.getString("step"),
                rs.getString("reason"), rs.getLong("n")));

        List<DropoffNote> notes = jdbc.query("""
                SELECT x.created_at, COALESCE(v.channel, 'UNKNOWN') AS channel, x.step, x.reason, x.note
                FROM checkout_exit_reason x LEFT JOIN site_visit v ON v.id = x.visit_id
                WHERE x.created_at >= :a AND x.created_at < :b AND x.note IS NOT NULL
                ORDER BY x.created_at DESC LIMIT 100
                """, p, (rs, i) -> new DropoffNote(rs.getTimestamp("created_at").toInstant(), rs.getString("channel"),
                rs.getString("step"), rs.getString("reason"), rs.getString("note")));
        return new DropoffReport(funnelRows, reasons, notes);
    }
}
