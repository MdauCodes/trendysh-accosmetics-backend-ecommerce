package com.nbh.backend.cart.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * How many free pieces a buy-X-get-Y deal gives a basket, and which shades they are (owner, 2026-11-04). The shopper puts
 * in the X they pay for (any shades of the same product) and the shop adds the Y free on top: every whole X paid for
 * gives Y free, so 10 paid under "buy 5 get 3" gives 6 free. The free shades default to the shade bought most; a shade
 * the shopper chose for their free pieces is kept for as long as it still fits. Free pieces need stock of their own: a
 * shade only takes what is left on its shelf after the paid pieces.
 */
final class FreePieces {

    private FreePieces() {}

    /**
     * @param paidByOption what the shopper pays for, per option of the deal's product, in the order the options are offered
     * @param freeNow      the free pieces in the basket now, per option, oldest first (the shopper's own choice of shades)
     * @param available    what each option's shelf can sell in all (paid and free pieces come off it)
     * @return the free pieces each option should hold, oldest choice first; options with none are left out
     */
    static Map<UUID, BigDecimal> plan(int buyQty, int freeQty, Map<UUID, BigDecimal> paidByOption,
                                      Map<UUID, BigDecimal> freeNow, Map<UUID, BigDecimal> available) {
        BigDecimal paid = paidByOption.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal entitled = paid.divide(BigDecimal.valueOf(buyQty), 0, RoundingMode.DOWN)
                .multiply(BigDecimal.valueOf(freeQty));

        // What each shelf can still give free, after the paid pieces.
        Map<UUID, BigDecimal> room = new LinkedHashMap<>();
        for (UUID option : union(paidByOption, freeNow, available)) {
            BigDecimal shelf = available.getOrDefault(option, BigDecimal.ZERO).max(BigDecimal.ZERO);
            room.put(option, shelf.subtract(paidByOption.getOrDefault(option, BigDecimal.ZERO)).max(BigDecimal.ZERO));
        }

        // Keep the shopper's choice, as far as each shelf allows.
        Map<UUID, BigDecimal> plan = new LinkedHashMap<>();
        for (Map.Entry<UUID, BigDecimal> e : freeNow.entrySet()) {
            BigDecimal keep = e.getValue().min(room.getOrDefault(e.getKey(), BigDecimal.ZERO)).max(BigDecimal.ZERO);
            if (keep.signum() > 0) plan.put(e.getKey(), keep);
        }

        // Too many (fewer paid for now): the newest choices give way first.
        BigDecimal total = sum(plan);
        if (total.compareTo(entitled) > 0) {
            BigDecimal over = total.subtract(entitled);
            List<UUID> newestFirst = new ArrayList<>(plan.keySet());
            java.util.Collections.reverse(newestFirst);
            for (UUID option : newestFirst) {
                if (over.signum() <= 0) break;
                BigDecimal cut = plan.get(option).min(over);
                BigDecimal left = plan.get(option).subtract(cut);
                if (left.signum() > 0) plan.put(option, left); else plan.remove(option);
                over = over.subtract(cut);
            }
            return plan;
        }

        // Too few: the shade bought most first, then the others with stock, in the order they are offered.
        BigDecimal short_ = entitled.subtract(total);
        List<UUID> order = new ArrayList<>(room.keySet());
        order.sort((a, b) -> paidByOption.getOrDefault(b, BigDecimal.ZERO).compareTo(paidByOption.getOrDefault(a, BigDecimal.ZERO)));
        for (UUID option : order) {
            if (short_.signum() <= 0) break;
            BigDecimal free = room.get(option).subtract(plan.getOrDefault(option, BigDecimal.ZERO));
            if (free.signum() <= 0) continue;
            BigDecimal add = free.min(short_);
            plan.merge(option, add, BigDecimal::add);
            short_ = short_.subtract(add);
        }
        return plan;
    }

    static BigDecimal sum(Map<UUID, BigDecimal> m) {
        return m.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @SafeVarargs
    private static List<UUID> union(Map<UUID, BigDecimal>... maps) {
        List<UUID> out = new ArrayList<>();
        for (Map<UUID, BigDecimal> m : maps) {
            for (UUID k : m.keySet()) if (!out.contains(k)) out.add(k);
        }
        return out;
    }
}
