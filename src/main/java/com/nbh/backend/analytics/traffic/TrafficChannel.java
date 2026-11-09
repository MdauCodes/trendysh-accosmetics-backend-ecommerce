package com.nbh.backend.analytics.traffic;

/**
 * Where a visit came from. The website decides it from the address and the page it came from (frontend
 * lib/trafficSource.ts, docs/plans/traffic-sources-dashboard.md); the backend only accepts these names.
 */
public enum TrafficChannel {
    GOOGLE_ADS,
    OTHER_PAID,
    AI_ASSISTANT,
    SOCIAL,
    WHATSAPP,
    GOOGLE_BUSINESS,
    ORGANIC_SEARCH,
    REFERRAL,
    DIRECT;

    /** Channels that cost money, so the report puts spend next to them. */
    public boolean paid() {
        return this == GOOGLE_ADS || this == OTHER_PAID;
    }

    public static TrafficChannel parse(String value) {
        if (value == null) return null;
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
