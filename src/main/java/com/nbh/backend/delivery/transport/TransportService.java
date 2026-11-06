package com.nbh.backend.delivery.transport;

import com.nbh.backend.common.exception.NotFoundException;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Metros (owner, 2026-10-28): the operators a shopper can send an order with, and the drop-off locations shoppers and
 * staff have named for each, with the likely fare. A location typed at checkout is kept straight away, tidied
 * ({@link TransportStops#clean}), and offered to the next shopper of that operator; a similar spelling finds the same
 * place ({@link TransportStops#key}). The fare is paid to the conductor on delivery, for now.
 */
@Service
public class TransportService {

    /** How many locations a shopper is offered as they type. */
    static final int SUGGESTED = 8;

    /** {@code kind} is the first of {@code kinds}, every section the option is listed under (V98). */
    public record OperatorDto(UUID id, String name, String areas, String kind, List<String> kinds) {
        static OperatorDto from(TransportOperator o) {
            return new OperatorDto(o.getId(), o.getName(), o.getAreas(), o.getKind(), o.getSections());
        }
    }

    /** {@code area}: INSIDE, OUTSIDE or null (both); it only matters while the option is in both areas (V98). */
    public record StopDto(UUID id, String name, BigDecimal fareMin, BigDecimal fareMax, boolean fareConfirmed, String area) {
        static StopDto from(TransportStop s) {
            return new StopDto(s.getId(), s.getName(), s.getFareMin(), s.getFareMax(), s.isFareConfirmed(), s.getArea());
        }
    }

    public record StaffOperatorDto(UUID id, String name, String areas, boolean active, int sortOrder, int picks, String kind,
                                   List<String> kinds) {
        static StaffOperatorDto from(TransportOperator o) {
            return new StaffOperatorDto(o.getId(), o.getName(), o.getAreas(), o.isActive(), o.getSortOrder(), o.getPicks(),
                    o.getKind(), o.getSections());
        }
    }

    public record StaffStopDto(UUID id, String name, BigDecimal fareMin, BigDecimal fareMax, boolean fareConfirmed,
                               int uses, boolean hidden, String addedBy, String area) {
        static StaffStopDto from(TransportStop s) {
            return new StaffStopDto(s.getId(), s.getName(), s.getFareMin(), s.getFareMax(), s.isFareConfirmed(),
                    s.getUses(), s.isHidden(), s.getAddedBy(), s.getArea());
        }
    }

    /**
     * {@code kinds}: every section it is listed under, the first being its main one; moving it is giving other sections. A
     * request with only {@code kind} (a dashboard from before V98) puts it in that one section. Both left out keeps it where
     * it is.
     */
    public record SaveOperator(@NotBlank @Size(max = 80) String name, @Size(max = 300) String areas,
                               Boolean active, Integer sortOrder,
                               @jakarta.validation.constraints.Pattern(regexp = "METRO|RIDER|OUTSIDE") String kind,
                               @Size(min = 1, max = 3)
                               List<@jakarta.validation.constraints.Pattern(regexp = "METRO|RIDER|OUTSIDE") String> kinds) {
        public SaveOperator(String name, String areas, Boolean active, Integer sortOrder) {
            this(name, areas, active, sortOrder, null, null);
        }

        public SaveOperator(String name, String areas, Boolean active, Integer sortOrder, String kind) {
            this(name, areas, active, sortOrder, kind, null);
        }
    }

    /**
     * {@code area}: INSIDE, OUTSIDE, or BOTH for a place shown in either. Left out, a new place takes its option's area
     * (both while the option is in both) and an existing place keeps its own.
     */
    public record SaveStop(@NotBlank @Size(max = 120) String name,
                           @DecimalMin("0") BigDecimal fareMin, @DecimalMin("0") BigDecimal fareMax, Boolean hidden,
                           @jakarta.validation.constraints.Pattern(regexp = "INSIDE|OUTSIDE|BOTH") String area) {
        public SaveStop(String name, BigDecimal fareMin, BigDecimal fareMax, Boolean hidden) {
            this(name, fareMin, fareMax, hidden, null);
        }
    }

    /** What an order carries from the shopper's Metro choice: the operator, the tidied location and its likely fare. */
    public record Resolved(UUID operatorId, String operatorName, String stopName, BigDecimal fareMin, BigDecimal fareMax) {}

    /** A place the customer chose under a provider, and the price the order is charged. */
    public record Priced(UUID operatorId, String operatorName, String placeName, BigDecimal price) {}

    private final TransportOperatorRepository operators;
    private final TransportStopRepository stops;

    public TransportService(TransportOperatorRepository operators, TransportStopRepository stops) {
        this.operators = operators;
        this.stops = stops;
    }

    // ---- shoppers ----

    @Transactional(readOnly = true)
    public List<OperatorDto> operatorsForShoppers() {
        return operators.activeForShoppers().stream().map(OperatorDto::from).toList();
    }

    @Transactional(readOnly = true)
    public List<StopDto> suggest(UUID operatorId, String typed) {
        String key = TransportStops.key(typed == null ? "" : typed);
        return stops.suggest(operatorId, key, PageRequest.of(0, SUGGESTED)).stream().map(StopDto::from).toList();
    }

    /**
     * At checkout: the operator the shopper chose and where it goes. A location not seen before for that operator is
     * kept for the next shopper; one seen before (by its key) is the same place, with its fare. Null when the operator is
     * unknown or switched off (the order then goes as plain text, as before).
     */
    @Transactional
    public Resolved resolveForOrder(UUID operatorId, String typedStop) {
        if (operatorId == null) return null;
        TransportOperator operator = operators.findById(operatorId).filter(TransportOperator::isActive).orElse(null);
        if (operator == null) return null;
        operators.countPick(operator.getId());
        String name = TransportStops.clean(typedStop);
        if (name == null) return new Resolved(operator.getId(), operator.getName(), null, null, null);
        String key = TransportStops.key(name);
        TransportStop stop = stops.findByOperatorIdAndNameKey(operator.getId(), key).orElse(null);
        if (stop == null) {
            stops.addIfNew(operator.getId(), name, key, areaOf(operator));
            stop = stops.findByOperatorIdAndNameKey(operator.getId(), key).orElseThrow();
        }
        stops.countUse(stop.getId());
        return new Resolved(operator.getId(), operator.getName(), stop.getName(), stop.getFareMin(), stop.getFareMax());
    }

    /** Every place with a price under this provider: what a customer chooses from. */
    @Transactional(readOnly = true)
    public List<StopDto> places(UUID operatorId) {
        return places(operatorId, null);
    }

    /**
     * The places a customer in {@code area} (INSIDE for Nairobi and Kiambu, OUTSIDE beyond them) chooses from. Only an option
     * listed in both areas tells them apart: there a place of the other area is left out; anywhere else every place shows.
     */
    @Transactional(readOnly = true)
    public List<StopDto> places(UUID operatorId, String area) {
        TransportOperator operator = operators.findById(operatorId).filter(TransportOperator::isActive).orElse(null);
        if (operator == null) return List.of();
        boolean split = operator.inBothAreas() && ("INSIDE".equals(area) || "OUTSIDE".equals(area));
        return stops.pricedFor(operatorId).stream()
                .filter(s -> !split || s.getArea() == null || s.getArea().equals(area))
                .map(StopDto::from).toList();
    }

    /** The price of a place under a provider, at checkout. Null when that place is not this provider's or has no price. */
    @Transactional
    public Priced priceForOrder(UUID operatorId, UUID stopId) {
        if (operatorId == null || stopId == null) return null;
        TransportOperator operator = operators.findById(operatorId).filter(TransportOperator::isActive).orElse(null);
        if (operator == null) return null;
        TransportStop stop = stops.findById(stopId)
                .filter(s -> s.getOperator().getId().equals(operatorId) && !s.isHidden() && s.getFareMin() != null)
                .orElse(null);
        if (stop == null) return null;
        operators.countPick(operator.getId());
        stops.countUse(stop.getId());
        return new Priced(operator.getId(), operator.getName(), stop.getName(), stop.getFareMin());
    }

    // ---- staff ----

    @Transactional(readOnly = true)
    public List<StaffOperatorDto> operatorsForStaff() {
        return operators.allForStaff().stream().map(StaffOperatorDto::from).toList();
    }

    @Transactional
    public StaffOperatorDto saveOperator(UUID id, SaveOperator body) {
        TransportOperator o = id == null ? new TransportOperator()
                : operators.findById(id).orElseThrow(() -> new NotFoundException("No such operator: " + id));
        String name = body.name().trim().replaceAll("\\s+", " ");
        operators.findByNameIgnoreCase(name)
                .filter(other -> !other.getId().equals(o.getId()))
                .ifPresent(other -> {
                    throw new IllegalArgumentException("There is already an operator called " + other.getName());
                });
        o.setName(name);
        o.setAreas(body.areas() == null || body.areas().isBlank() ? null : body.areas().trim());
        if (body.active() != null) o.setActive(body.active());
        if (body.sortOrder() != null) o.setSortOrder(body.sortOrder());
        if (body.kinds() != null) o.setSections(body.kinds());
        else if (body.kind() != null) o.setSections(List.of(body.kind()));
        return StaffOperatorDto.from(operators.save(o));
    }

    @Transactional(readOnly = true)
    public List<StaffStopDto> stopsForStaff(UUID operatorId) {
        return stops.allForStaff(operatorId).stream().map(StaffStopDto::from).toList();
    }

    /** Staff add a location or correct one: its name tidied like a shopper's, and a fare set by staff counts as confirmed. */
    @Transactional
    public StaffStopDto saveStop(UUID operatorId, UUID stopId, SaveStop body) {
        TransportOperator operator = operators.findById(operatorId)
                .orElseThrow(() -> new NotFoundException("No such operator: " + operatorId));
        TransportStop s = stopId == null ? new TransportStop()
                : stops.findById(stopId).filter(x -> x.getOperator().getId().equals(operatorId))
                        .orElseThrow(() -> new NotFoundException("No such location: " + stopId));
        String name = TransportStops.clean(body.name());
        if (name == null) throw new IllegalArgumentException("Give the location a name");
        String key = TransportStops.key(name);
        stops.findByOperatorIdAndNameKey(operatorId, key)
                .filter(other -> !other.getId().equals(s.getId()))
                .ifPresent(other -> {
                    throw new IllegalArgumentException(operator.getName() + " already has " + other.getName());
                });
        if (body.fareMin() != null && body.fareMax() != null && body.fareMin().compareTo(body.fareMax()) > 0) {
            throw new IllegalArgumentException("The lowest fare is more than the highest");
        }
        if (stopId == null) {
            s.setOperator(operator);
            s.setAddedBy("STAFF");
            s.setArea(areaOf(operator));
        }
        if (body.area() != null) s.setArea("BOTH".equals(body.area()) ? null : body.area());
        s.setName(name);
        s.setNameKey(key);
        s.setFareMin(body.fareMin());
        s.setFareMax(body.fareMax() != null ? body.fareMax() : body.fareMin());
        s.setFareConfirmed(body.fareMin() != null || body.fareMax() != null);
        if (body.hidden() != null) s.setHidden(body.hidden());
        return StaffStopDto.from(stops.save(s));
    }

    /** The area a new place of this option belongs to: its only one, or null (both) while it is listed in both. */
    static String areaOf(TransportOperator operator) {
        if (operator.inBothAreas()) return null;
        return operator.getSections().contains("OUTSIDE") ? "OUTSIDE" : "INSIDE";
    }
}
