package com.nbh.backend.analytics.traffic;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class TrafficDtos {

    private TrafficDtos() {}

    /** One visit, as the website reports it on its first page. Nothing personal: the visitor id is random. */
    public record VisitRequest(
            @NotNull UUID visitor,
            @NotNull @Size(max = 24) String channel,
            @Size(max = 200) String source,
            @Size(max = 200) String campaign,
            @Size(max = 1000) String landing,
            @Size(max = 10) String device) {}

    /** The id is null when the visit was not kept (a bot, or too many from one address). */
    public record VisitResponse(UUID id) {}

    public record EventRequest(@NotNull @Size(max = 24) String kind, @PositiveOrZero BigDecimal value) {}

    /** A row of the report: a channel, or one source inside it. Money is KES; revenue counts paid orders' products only. */
    public record SourceRow(
            String channel,
            String source,
            long visits,
            long addToCarts,
            long checkoutsStarted,
            long whatsappBaskets,
            BigDecimal whatsappBasketValue,
            long whatsappChats,
            long orders,
            long paidOrders,
            BigDecimal revenue,
            BigDecimal spend) {}

    public record DayRow(LocalDate day, long visits, long paidOrders, BigDecimal revenue,
                         BigDecimal adsRevenue, BigDecimal spend) {}

    public record SourcesReport(LocalDate from, LocalDate to, String touch,
                                List<SourceRow> channels, List<SourceRow> sources, List<DayRow> days) {}

    public record SpendRow(LocalDate day, String channel, BigDecimal amountKes, String note, String updatedBy) {}

    public record SpendRequest(@NotNull LocalDate day, @NotNull @Size(max = 24) String channel,
                               @NotNull @PositiveOrZero BigDecimal amountKes, @Size(max = 200) String note) {}
}
