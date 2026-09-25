package uz.horecaos.platform.ordering.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Row 3.1: the dispatch board's non-PII destination label. Mirrors {@code
 * PhoneMasking}'s own test shape — the masked form is what is asserted,
 * never a parser's internal steps.
 */
class DeliveryDestinationTests {

    private static DeliveryDestination destination(
            String line1, String district, String city, String apartment, String entrance, String floor) {
        return new DeliveryDestination(
                line1, "", city, district, "", entrance, floor, apartment, "Behind the blue gate", 41.31, 69.24);
    }

    @Test
    void theLabelCombinesDistrictAndStreetWithoutTheHouseNumber() {
        DeliveryDestination destination =
                destination("Amir Temur ko'chasi 15", "Yunusobod", "Tashkent", "42", "3", "7");

        assertThat(destination.maskedLabel()).isEqualTo("Yunusobod, Amir Temur ko'chasi");
    }

    @Test
    void theLabelNeverCarriesTheHouseNumberDigits() {
        DeliveryDestination destination =
                destination("Mustaqillik shoh ko'chasi 128B", "Chilonzor", "Tashkent", "5", "1", "9");

        String label = destination.maskedLabel();
        assertThat(label).doesNotContain("128");
    }

    @Test
    void theLabelNeverCarriesTheApartmentEntranceOrFloor() {
        DeliveryDestination destination = destination("Bunyodkor ko'chasi 3", "Yashnobod", "Tashkent", "99", "12", "8");

        String label = destination.maskedLabel();
        assertThat(label).doesNotContain("99").doesNotContain("12").doesNotContain("8");
    }

    @Test
    void theLabelNeverCarriesTheLandmark() {
        // Landmark is the most specific, most identifying field short of the
        // house number itself, and is deliberately never read by maskedLabel.
        DeliveryDestination destination = destination("Bunyodkor ko'chasi 3", "Yashnobod", "Tashkent", "", "", "");

        assertThat(destination.maskedLabel()).doesNotContain("blue gate");
    }

    @Test
    void aBlankDistrictFallsBackToTheCity() {
        DeliveryDestination destination = destination("Chilonzor 19-kvartal", "", "Tashkent", "", "", "");

        assertThat(destination.maskedLabel()).isEqualTo("Tashkent, Chilonzor");
    }

    @Test
    void noZoneAndNoStreetLeftAfterMaskingIsNullNotAnEmptyString() {
        DeliveryDestination destination = destination("12", "", "", "", "", "");

        assertThat(destination.maskedLabel()).isNull();
    }

    @Test
    void aStreetWithNoHouseNumberIsKeptWhole() {
        DeliveryDestination destination = destination("Labzak ko'chasi", "Mirzo Ulugbek", "Tashkent", "", "", "");

        assertThat(destination.maskedLabel()).isEqualTo("Mirzo Ulugbek, Labzak ko'chasi");
    }
}
