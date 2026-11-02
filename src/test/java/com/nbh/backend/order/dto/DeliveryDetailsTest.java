package com.nbh.backend.order.dto;

import com.nbh.backend.order.entity.DeliveryTimeWindow;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The optional extras a customer can give about a delivery (2026-10-07). What is kept depends on how the order
 * travels, text is tidied before it is stored, and the rider is given the address followed by the way to the door.
 */
class DeliveryDetailsTest {

    private static DeliveryDetails everything() {
        return new DeliveryDetails("Kilimani", "Argwings Kodhek Rd", "Yaya Towers", "4th floor, door 12",
                "Easy Coach Nakuru", "Opposite the pharmacy", "Call at the gate", "Njeri Receptionist",
                DeliveryTimeWindow.MORNING);
    }

    @Test
    void aRidersDoorstepDeliveryKeepsEverythingExceptTheCouriersOffice() {
        assertThat(everything().cleanedFor("BODA")).isEqualTo(new DeliveryDetails("Kilimani", "Argwings Kodhek Rd",
                "Yaya Towers", "4th floor, door 12", null, "Opposite the pharmacy", "Call at the gate",
                "Njeri Receptionist", DeliveryTimeWindow.MORNING));
    }

    @Test
    void aDropAtAStageKeepsTheAreaDirectionsAndWhoCollectsButNoBuildingOrFloor() {
        assertThat(everything().cleanedFor("MATATU")).isEqualTo(new DeliveryDetails("Kilimani", null, null, null, null,
                "Opposite the pharmacy", "Call at the gate", "Njeri Receptionist", DeliveryTimeWindow.MORNING));
    }

    @Test
    void aCourierParcelKeepsTheOfficeButNoReceiverNameOrTimeOfDay() {
        assertThat(everything().cleanedFor("COURIER")).isEqualTo(new DeliveryDetails("Kilimani", null, null, null,
                "Easy Coach Nakuru", "Opposite the pharmacy", "Call at the gate", null, null));
    }

    @Test
    void anythingThatIsNotADeliveryMethodKeepsNothing() {
        assertThat(everything().cleanedFor(null)).isNull();
        assertThat(everything().cleanedFor("PICKUP")).isNull();
        assertThat(everything().cleanedFor("boda")).as("the methods are spelled in capitals everywhere else").isNull();
    }

    @Test
    void messyTextIsTidiedAndAnEmptyResultIsNullNotAnObjectOfNulls() {
        DeliveryDetails messy = new DeliveryDetails("  Kilimani \t ", "   ", null, null, null,
                " Blue  gate \r\n\r\n  second\u0007 line ", "\n\n", null, null);

        DeliveryDetails kept = messy.cleanedFor("BODA");

        assertThat(kept.estate()).isEqualTo("Kilimani");
        assertThat(kept.street()).as("blank is nothing").isNull();
        assertThat(kept.directions()).as("lines stay lines, blank ones and stray control characters go")
                .isEqualTo("Blue gate\nsecond line");
        assertThat(kept.instructions()).isNull();

        assertThat(new DeliveryDetails(" ", null, null, null, null, "", " \n ", null, null).cleanedFor("MATATU")).isNull();
        assertThat(new DeliveryDetails(null, null, null, null, null, null, null, null, null).cleanedFor("BODA")).isNull();
    }

    @Test
    void invisibleCharactersThatChangeWhichWayTextReadsAreRemoved() {
        DeliveryDetails sneaky = new DeliveryDetails("Kili‮mani‏", null, null, null, null,
                "Gate ⁦is blue⁩", null, null, null);

        DeliveryDetails kept = sneaky.cleanedFor("MATATU");

        assertThat(kept.estate()).isEqualTo("Kilimani");
        assertThat(kept.directions()).isEqualTo("Gate is blue");
    }

    @Test
    void aSingleLineFieldNeverKeepsALineBreak() {
        assertThat(new DeliveryDetails("Kilimani\nNairobi", null, null, null, null, null, null, null, null)
                .cleanedFor("MATATU").estate()).isEqualTo("Kilimani Nairobi");
    }
}
