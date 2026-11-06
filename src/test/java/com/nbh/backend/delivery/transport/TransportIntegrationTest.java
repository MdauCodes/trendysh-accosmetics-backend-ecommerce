package com.nbh.backend.delivery.transport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Metros against real Postgres (V82): the seeded operators are there, a location a shopper types is kept for the next
 * shopper of that operator (tidied), a similar spelling is the same place with its fare, and staff set fares.
 */
@SpringBootTest
@ActiveProfiles("test")
class TransportIntegrationTest {

    @Autowired private TransportService service;

    private UUID newOperator() {
        return service.saveOperator(null, new TransportService.SaveOperator(
                "QA Sacco " + UUID.randomUUID().toString().substring(0, 6), "Somewhere", true, 5)).id();
    }

    @Test
    void theResearchedOperatorsAreSeededWithSuperMetroAndNicco() {
        var names = service.operatorsForShoppers().stream().map(TransportService.OperatorDto::name).toList();
        assertThat(names).contains("Super Metro", "Nicco Movers", "Embassava", "Forward Travellers");
    }

    @Test
    void aTypedLocationIsKeptTidiedAndASimilarSpellingFindsItWithTheFareStaffSet() {
        UUID op = newOperator();
        var first = service.resolveForOrder(op, "  kencom stage ");
        assertThat(first.stopName()).isEqualTo("Kencom Stage");
        assertThat(first.fareMin()).isNull();
        assertThat(service.suggest(op, "kenc")).extracting(TransportService.StopDto::name).containsExactly("Kencom Stage");

        var stop = service.stopsForStaff(op).get(0);
        service.saveStop(op, stop.id(), new TransportService.SaveStop("Kencom Stage", new BigDecimal("150"),
                new BigDecimal("200"), false));

        var again = service.resolveForOrder(op, "KENCOM");
        assertThat(again.stopName()).isEqualTo("Kencom Stage");
        assertThat(again.fareMin()).isEqualByComparingTo("150");
        assertThat(again.fareMax()).isEqualByComparingTo("200");
        assertThat(service.stopsForStaff(op)).hasSize(1).first()
                .satisfies(s -> assertThat(s.uses()).isEqualTo(2));
    }

    @Test
    void aHiddenLocationIsNotSuggestedAndAnUnknownOperatorLeavesTheOrderAsTyped() {
        UUID op = newOperator();
        service.resolveForOrder(op, "my gate opposite the kiosk");
        var stop = service.stopsForStaff(op).get(0);
        service.saveStop(op, stop.id(), new TransportService.SaveStop(stop.name(), null, null, true));
        assertThat(service.suggest(op, "")).isEmpty();
        assertThat(service.resolveForOrder(UUID.randomUUID(), "Kencom")).isNull();
    }

    @Test
    void twoOperatorsCannotShareAName() {
        UUID op = newOperator();
        String name = service.operatorsForStaff().stream().filter(o -> o.id().equals(op)).findFirst().orElseThrow().name();
        assertThatThrownBy(() -> service.saveOperator(null, new TransportService.SaveOperator(name.toLowerCase(), null, true, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private UUID optionIn(java.util.List<String> kinds) {
        return service.saveOperator(null, new TransportService.SaveOperator(
                "QA Courier " + UUID.randomUUID().toString().substring(0, 6), "Everywhere", true, 5, null, kinds)).id();
    }

    private void place(UUID op, String name, String price, String area) {
        service.saveStop(op, null, new TransportService.SaveStop(name, new BigDecimal(price), null, false, area));
    }

    private java.util.List<String> placesFor(UUID op, String area) {
        return service.places(op, area).stream().map(TransportService.StopDto::name).toList();
    }

    @Test
    void anOptionInBothAreasShowsEachCustomerOnlyThePlacesOfTheirArea() {
        UUID op = optionIn(java.util.List.of("METRO", "OUTSIDE"));
        place(op, "Ruiru", "200", "INSIDE");
        place(op, "Nakuru", "400", "OUTSIDE");
        place(op, "Head Office", "100", "BOTH");
        place(op, "Thika Road", "250", null);

        assertThat(placesFor(op, "INSIDE")).containsExactly("Head Office", "Ruiru", "Thika Road");
        assertThat(placesFor(op, "OUTSIDE")).containsExactly("Head Office", "Nakuru", "Thika Road");
        assertThat(placesFor(op, null)).containsExactly("Head Office", "Nakuru", "Ruiru", "Thika Road");
        assertThat(service.operatorsForShoppers()).filteredOn(o -> o.id().equals(op)).singleElement()
                .satisfies(o -> {
                    assertThat(o.kinds()).containsExactly("METRO", "OUTSIDE");
                    assertThat(o.kind()).isEqualTo("METRO");
                });
        // The price charged is the place's own, whichever area it was chosen from.
        UUID nakuru = service.places(op, "OUTSIDE").stream().filter(s -> s.name().equals("Nakuru")).findFirst().orElseThrow().id();
        assertThat(service.priceForOrder(op, nakuru).price()).isEqualByComparingTo("400");
    }

    @Test
    void anOptionMovesToAnotherSectionWithAllItsPlacesAndAnOldDashboardStillMovesIt() {
        UUID op = optionIn(java.util.List.of("METRO"));
        place(op, "Ruiru", "200", null);
        assertThat(service.stopsForStaff(op)).singleElement().satisfies(s -> assertThat(s.area()).isEqualTo("INSIDE"));

        var moved = service.saveOperator(op, new TransportService.SaveOperator(
                nameOf(op), "Everywhere", true, 5, null, java.util.List.of("OUTSIDE")));
        assertThat(moved.kind()).isEqualTo("OUTSIDE");
        assertThat(moved.kinds()).containsExactly("OUTSIDE");
        assertThat(placesFor(op, "OUTSIDE")).as("in one area, every place shows").containsExactly("Ruiru");

        var back = service.saveOperator(op, new TransportService.SaveOperator(nameOf(op), "Everywhere", true, 5, "RIDER"));
        assertThat(back.kinds()).containsExactly("RIDER");

        var kept = service.saveOperator(op, new TransportService.SaveOperator(nameOf(op), "Everywhere", true, 5));
        assertThat(kept.kinds()).as("no section given keeps it where it is").containsExactly("RIDER");
    }

    @Test
    void aNewPlaceOfAnOptionInBothAreasIsShownInBothUntilStaffSayWhich() {
        UUID op = optionIn(java.util.List.of("RIDER", "OUTSIDE"));
        service.resolveForOrder(op, "my gate in kitengela");
        assertThat(service.stopsForStaff(op)).singleElement().satisfies(s -> assertThat(s.area()).isNull());
        var stop = service.stopsForStaff(op).get(0);
        service.saveStop(op, stop.id(), new TransportService.SaveStop(stop.name(), new BigDecimal("300"), null, false, "INSIDE"));
        assertThat(placesFor(op, "OUTSIDE")).isEmpty();
        assertThat(placesFor(op, "INSIDE")).containsExactly(stop.name());
        service.saveStop(op, stop.id(), new TransportService.SaveStop(stop.name(), new BigDecimal("300"), null, false));
        assertThat(service.stopsForStaff(op).get(0).area()).as("left out, the area is kept").isEqualTo("INSIDE");
    }

    private String nameOf(UUID op) {
        return service.operatorsForStaff().stream().filter(o -> o.id().equals(op)).findFirst().orElseThrow().name();
    }

    @Test
    void outsideNairobiEachPlaceListsEveryProviderThatGoesThereCheapestFirst() {
        String town = "Qa Town " + UUID.randomUUID().toString().substring(0, 5);
        UUID bus = optionIn(java.util.List.of("OUTSIDE"));
        UUID courier = optionIn(java.util.List.of("OUTSIDE"));
        UUID metro = optionIn(java.util.List.of("METRO"));
        UUID both = optionIn(java.util.List.of("METRO", "OUTSIDE"));
        place(bus, town, "450", null);
        place(courier, town + " Stage", "300", null);
        place(metro, town, "100", null);
        place(both, town, "200", "INSIDE");

        var found = service.placesInArea("OUTSIDE").stream()
                .filter(p -> p.name().toLowerCase().startsWith(town.toLowerCase())).toList();
        assertThat(found).as("the same town under every provider is one place").hasSize(1);
        assertThat(found.get(0).name()).isEqualTo(town);
        assertThat(found.get(0).options()).extracting(TransportService.PlaceOption::operatorId)
                .as("a Metro and a place kept for Nairobi customers are left out; the cheapest comes first")
                .containsExactly(courier, bus);
        assertThat(found.get(0).options().get(0).price()).isEqualByComparingTo("300");

        var inside = service.placesInArea("INSIDE").stream()
                .filter(p -> p.name().equalsIgnoreCase(town)).findFirst().orElseThrow();
        assertThat(inside.options()).extracting(TransportService.PlaceOption::operatorId).containsExactly(metro, both);
    }
}
