package com.nbh.backend.analytics.traffic;

import com.nbh.backend.analytics.traffic.TrafficDtos.DayRow;
import com.nbh.backend.analytics.traffic.TrafficDtos.SourceRow;
import com.nbh.backend.analytics.traffic.TrafficDtos.SourcesReport;
import com.nbh.backend.analytics.traffic.TrafficDtos.SpendRow;
import com.nbh.backend.analytics.traffic.TrafficDtos.VisitRequest;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Visits, what happened in them, the source stamped on each order, and the report that puts them next to ad spend
 * (docs/plans/traffic-sources-dashboard.md). Plain SQL: the report is a handful of GROUP BYs across tables that have no
 * JPA entity of their own.
 */
@Service
public class TrafficService {

    static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    /** What a visit can lead to. WHATSAPP_BASKET carries the basket's value; the others count. */
    static final Set<String> EVENT_KINDS = Set.of("ADD_TO_CART", "CHECKOUT_STARTED", "WHATSAPP_BASKET", "WHATSAPP_CHAT");
    /** Link previews and crawlers fetch pages too; they are not shoppers. */
    private static final Pattern BOT = Pattern.compile(
            "bot|crawl|spider|slurp|preview|facebookexternalhit|whatsapp|headless|lighthouse|pingdom|curl|wget|python",
            Pattern.CASE_INSENSITIVE);
    private static final String PAID = "('PAID','PICKING','READY','DISPATCHED','DELIVERED')";
    /** Same rule as the Analytics page: real orders, or the demo ones while demo data is switched on. */
    private static final String REAL_OR_DEMO = """
            ((o.is_mock_data AND EXISTS (SELECT 1 FROM product mp WHERE mp.is_mock_data AND mp.is_published))
              OR (NOT o.is_mock_data AND NOT EXISTS (SELECT 1 FROM product mp WHERE mp.is_mock_data AND mp.is_published)))""";

    private final NamedParameterJdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final Clock clock;

    public TrafficService(NamedParameterJdbcTemplate jdbc, EntityManager entityManager, Clock clock) {
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    public static boolean isBot(String userAgent) {
        return userAgent == null || userAgent.isBlank() || BOT.matcher(userAgent).find();
    }

    @Transactional
    public UUID recordVisit(VisitRequest request) {
        TrafficChannel channel = TrafficChannel.parse(request.channel());
        if (channel == null) return null;
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO site_visit (id, visitor, channel, source, campaign, landing, device, started_at)
                VALUES (:id, :visitor, :channel, :source, :campaign, :landing, :device, :at)
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("visitor", request.visitor())
                .addValue("channel", channel.name())
                .addValue("source", tidy(request.source(), 80))
                .addValue("campaign", tidy(request.campaign(), 120))
                .addValue("landing", landing(request.landing()))
                .addValue("device", "phone".equals(request.device()) ? "phone" : "laptop")
                .addValue("at", Timestamp.from(Instant.now(clock))));
        return id;
    }

    /** False when the visit is unknown (removed, or never kept) or the kind is not one we record. */
    @Transactional
    public boolean recordEvent(UUID visitId, String kind, BigDecimal value) {
        String k = kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT);
        if (!EVENT_KINDS.contains(k)) return false;
        int n = jdbc.update("""
                INSERT INTO site_event (visit_id, kind, value, at)
                SELECT v.id, :kind, :value, :at FROM site_visit v WHERE v.id = :visit
                """, new MapSqlParameterSource()
                .addValue("visit", visitId)
                .addValue("kind", k)
                .addValue("value", "WHATSAPP_BASKET".equals(k) ? value : null)
                .addValue("at", Timestamp.from(Instant.now(clock))));
        return n > 0;
    }

    /**
     * Puts the visit the order was placed in (last touch) and the visitor's first visit (first touch) on the order.
     * Runs inside the checkout's transaction, after the new order is written, and changes nothing when the visit is
     * unknown, so an order is never refused over where it came from.
     */
    public void stampOrder(UUID orderId, UUID visitId) {
        if (orderId == null || visitId == null) return;
        entityManager.flush();
        jdbc.update("""
                UPDATE customer_order o SET
                    visit_id = v.id,
                    last_channel = v.channel, last_source = v.source, last_campaign = v.campaign,
                    first_channel = f.channel, first_source = f.source
                FROM site_visit v
                CROSS JOIN LATERAL (
                    SELECT f.channel, f.source FROM site_visit f
                    WHERE f.visitor = v.visitor ORDER BY f.started_at LIMIT 1) f
                WHERE v.id = :visit AND o.id = :order
                """, new MapSqlParameterSource().addValue("visit", visitId).addValue("order", orderId));
    }

    @Transactional(readOnly = true)
    public SourcesReport report(LocalDate from, LocalDate to, boolean firstTouch) {
        LocalDate end = to.isBefore(from) ? from : to;
        var p = new MapSqlParameterSource()
                .addValue("a", Timestamp.from(from.atStartOfDay(NAIROBI).toInstant()))
                .addValue("b", Timestamp.from(end.plusDays(1).atStartOfDay(NAIROBI).toInstant()))
                .addValue("from", from)
                .addValue("to", end);
        String t = firstTouch ? "first" : "last";
        String orderChannel = "COALESCE(o." + t + "_channel, CASE WHEN o.placed_by IS NOT NULL THEN 'STAFF' ELSE 'UNKNOWN' END)";

        Map<String, Acc> sources = new LinkedHashMap<>();
        jdbc.query("""
                SELECT channel, COALESCE(source, '') AS src, COUNT(*) AS n FROM site_visit
                WHERE started_at >= :a AND started_at < :b GROUP BY 1, 2
                """, p, rs -> {
            acc(sources, rs.getString("channel"), rs.getString("src")).visits += rs.getLong("n");
        });
        jdbc.query("""
                SELECT v.channel, COALESCE(v.source, '') AS src, e.kind, COUNT(*) AS n, COALESCE(SUM(e.value), 0) AS total
                FROM site_event e JOIN site_visit v ON v.id = e.visit_id
                WHERE e.at >= :a AND e.at < :b GROUP BY 1, 2, 3
                """, p, rs -> {
            Acc a = acc(sources, rs.getString("channel"), rs.getString("src"));
            long n = rs.getLong("n");
            switch (rs.getString("kind")) {
                case "ADD_TO_CART" -> a.addToCarts += n;
                case "CHECKOUT_STARTED" -> a.checkouts += n;
                case "WHATSAPP_BASKET" -> {
                    a.baskets += n;
                    a.basketValue = a.basketValue.add(rs.getBigDecimal("total"));
                }
                case "WHATSAPP_CHAT" -> a.chats += n;
                default -> { }
            }
        });
        jdbc.query("""
                SELECT %s AS channel, COALESCE(o.%s_source, '') AS src,
                       COUNT(*) AS orders,
                       COUNT(*) FILTER (WHERE o.status IN %s) AS paid,
                       COALESCE(SUM(o.subtotal) FILTER (WHERE o.status IN %s), 0) AS revenue
                FROM customer_order o
                WHERE o.created_at >= :a AND o.created_at < :b AND %s
                GROUP BY 1, 2
                """.formatted(orderChannel, t, PAID, PAID, REAL_OR_DEMO), p, rs -> {
            Acc a = acc(sources, rs.getString("channel"), rs.getString("src"));
            a.orders += rs.getLong("orders");
            a.paid += rs.getLong("paid");
            a.revenue = a.revenue.add(rs.getBigDecimal("revenue"));
        });
        Map<String, BigDecimal> spendByChannel = new LinkedHashMap<>();
        jdbc.query("""
                SELECT channel, SUM(amount_kes) AS total FROM ad_spend
                WHERE day >= :from AND day <= :to GROUP BY 1
                """, p, rs -> {
            spendByChannel.put(rs.getString("channel"), rs.getBigDecimal("total"));
        });

        Map<String, Acc> channels = new LinkedHashMap<>();
        for (Acc s : sources.values()) {
            Acc c = channels.computeIfAbsent(s.channel, ch -> new Acc(ch, null));
            c.add(s);
        }
        spendByChannel.forEach((ch, total) -> channels.computeIfAbsent(ch, x -> new Acc(ch, null)).spend = total);

        Comparator<SourceRow> biggestFirst = Comparator.comparing(SourceRow::revenue).reversed()
                .thenComparing(Comparator.comparingLong(SourceRow::visits).reversed());
        List<SourceRow> channelRows = channels.values().stream().map(Acc::row).sorted(biggestFirst).toList();
        List<SourceRow> sourceRows = sources.values().stream().map(Acc::row).sorted(biggestFirst).toList();
        return new SourcesReport(from, end, t, channelRows, sourceRows, days(p, from, end, orderChannel));
    }

    private List<DayRow> days(MapSqlParameterSource p, LocalDate from, LocalDate to, String orderChannel) {
        Map<LocalDate, long[]> counts = new LinkedHashMap<>();
        Map<LocalDate, BigDecimal[]> money = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            counts.put(d, new long[2]);
            money.put(d, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
        }
        jdbc.query("""
                SELECT CAST((started_at AT TIME ZONE 'Africa/Nairobi') AS date) AS day, COUNT(*) AS n
                FROM site_visit WHERE started_at >= :a AND started_at < :b GROUP BY 1
                """, p, rs -> {
            long[] c = counts.get(rs.getDate("day").toLocalDate());
            if (c != null) c[0] = rs.getLong("n");
        });
        jdbc.query("""
                SELECT CAST((o.created_at AT TIME ZONE 'Africa/Nairobi') AS date) AS day,
                       COUNT(*) FILTER (WHERE o.status IN %s) AS paid,
                       COALESCE(SUM(o.subtotal) FILTER (WHERE o.status IN %s), 0) AS revenue,
                       COALESCE(SUM(o.subtotal) FILTER (WHERE o.status IN %s AND %s = 'GOOGLE_ADS'), 0) AS ads
                FROM customer_order o
                WHERE o.created_at >= :a AND o.created_at < :b AND %s
                GROUP BY 1
                """.formatted(PAID, PAID, PAID, orderChannel, REAL_OR_DEMO), p, rs -> {
            LocalDate d = rs.getDate("day").toLocalDate();
            if (!counts.containsKey(d)) return;
            counts.get(d)[1] = rs.getLong("paid");
            money.get(d)[0] = rs.getBigDecimal("revenue");
            money.get(d)[1] = rs.getBigDecimal("ads");
        });
        jdbc.query("SELECT day, SUM(amount_kes) AS total FROM ad_spend WHERE day >= :from AND day <= :to GROUP BY 1", p, rs -> {
            BigDecimal[] m = money.get(rs.getDate("day").toLocalDate());
            if (m != null) m[2] = rs.getBigDecimal("total");
        });
        List<DayRow> out = new ArrayList<>();
        counts.forEach((d, c) -> {
            BigDecimal[] m = money.get(d);
            out.add(new DayRow(d, c[0], c[1], m[0], m[1], m[2]));
        });
        return out;
    }

    @Transactional(readOnly = true)
    public List<SpendRow> spend(LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT day, channel, amount_kes, note, updated_by FROM ad_spend
                WHERE day >= :from AND day <= :to ORDER BY day DESC, channel
                """, new MapSqlParameterSource().addValue("from", from).addValue("to", to),
                (rs, i) -> new SpendRow(rs.getDate("day").toLocalDate(), rs.getString("channel"),
                        rs.getBigDecimal("amount_kes"), rs.getString("note"), rs.getString("updated_by")));
    }

    /** One amount per day and channel: typing the day again replaces it; zero removes it. */
    @Transactional
    public void saveSpend(LocalDate day, TrafficChannel channel, BigDecimal amount, String note, String who) {
        var p = new MapSqlParameterSource()
                .addValue("day", day).addValue("channel", channel.name()).addValue("amount", amount)
                .addValue("note", tidyNote(note)).addValue("who", who)
                .addValue("at", Timestamp.from(Instant.now(clock)));
        if (amount.signum() == 0) {
            jdbc.update("DELETE FROM ad_spend WHERE day = :day AND channel = :channel", p);
            return;
        }
        jdbc.update("""
                INSERT INTO ad_spend (day, channel, amount_kes, note, updated_at, updated_by)
                VALUES (:day, :channel, :amount, :note, :at, :who)
                ON CONFLICT (day, channel) DO UPDATE SET amount_kes = EXCLUDED.amount_kes, note = EXCLUDED.note,
                    updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by
                """, p);
    }

    /** Visits are kept 13 months; each order keeps its own source for good. */
    @Scheduled(cron = "0 50 3 * * *", zone = "Africa/Nairobi")
    @Transactional
    public int forgetOldVisits() {
        Instant cutoff = Instant.now(clock).atZone(NAIROBI).minusMonths(13).toInstant().truncatedTo(ChronoUnit.DAYS);
        jdbc.update("UPDATE customer_order SET visit_id = NULL WHERE visit_id IN (SELECT id FROM site_visit WHERE started_at < :cut)",
                new MapSqlParameterSource("cut", Timestamp.from(cutoff)));
        return jdbc.update("DELETE FROM site_visit WHERE started_at < :cut",
                new MapSqlParameterSource("cut", Timestamp.from(cutoff)));
    }

    static String tidy(String value, int max) {
        if (value == null) return null;
        String v = value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (v.isEmpty()) return null;
        return v.length() > max ? v.substring(0, max) : v;
    }

    private static String tidyNote(String value) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim();
        return v.length() > 200 ? v.substring(0, 200) : v;
    }

    /** The first page's path only: no query string, so nothing a shopper typed can end up here. */
    static String landing(String value) {
        if (value == null || value.isBlank()) return "/";
        String v = value.trim();
        int cut = v.indexOf('?');
        if (cut >= 0) v = v.substring(0, cut);
        cut = v.indexOf('#');
        if (cut >= 0) v = v.substring(0, cut);
        if (!v.startsWith("/")) v = "/" + v;
        return v.length() > 300 ? v.substring(0, 300) : v;
    }

    private static Acc acc(Map<String, Acc> map, String channel, String source) {
        String src = source == null || source.isEmpty() ? null : source;
        return map.computeIfAbsent(channel + "\u0000" + (src == null ? "" : src), k -> new Acc(channel, src));
    }

    private static final class Acc {
        final String channel;
        final String source;
        long visits, addToCarts, checkouts, baskets, chats, orders, paid;
        BigDecimal basketValue = BigDecimal.ZERO, revenue = BigDecimal.ZERO, spend = null;

        Acc(String channel, String source) {
            this.channel = channel;
            this.source = source;
        }

        void add(Acc o) {
            visits += o.visits;
            addToCarts += o.addToCarts;
            checkouts += o.checkouts;
            baskets += o.baskets;
            chats += o.chats;
            orders += o.orders;
            paid += o.paid;
            basketValue = basketValue.add(o.basketValue);
            revenue = revenue.add(o.revenue);
        }

        SourceRow row() {
            return new SourceRow(channel, source, visits, addToCarts, checkouts, baskets, basketValue, chats,
                    orders, paid, revenue, spend);
        }
    }
}
