package com.nbh.backend.order.dto;

/**
 * Where an order came from, for staff (owner, 15 Nov 2026: "we can tell which place or source an order came from").
 * The website's own record of the visit the order was placed in ({@code analytics.traffic}): {@code channel} is a
 * {@code TrafficChannel} name, or STAFF for an order staff entered, or UNKNOWN for one placed before tracking began or
 * from a browser that sent no visit. {@code source} is the place inside the channel ("instagram", "superior-arcade",
 * "chatgpt.com"), {@code campaign} the ad campaign or tagged link, and {@code firstChannel}/{@code firstSource} where
 * the same shopper first found the shop, when that was somewhere else.
 */
public record OrderSourceDto(String channel, String source, String campaign, String firstChannel, String firstSource) {

    public static OrderSourceDto of(String lastChannel, String lastSource, String lastCampaign,
                                    String firstChannel, String firstSource, String placedBy) {
        String channel = lastChannel != null ? lastChannel : placedBy != null ? "STAFF" : "UNKNOWN";
        boolean firstDiffers = firstChannel != null
                && (!firstChannel.equals(lastChannel) || (firstSource != null && !firstSource.equals(lastSource)));
        return new OrderSourceDto(channel, blankToNull(lastSource), blankToNull(lastCampaign),
                firstDiffers ? firstChannel : null, firstDiffers ? blankToNull(firstSource) : null);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
