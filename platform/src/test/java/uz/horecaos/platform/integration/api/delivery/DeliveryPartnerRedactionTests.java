package uz.horecaos.platform.integration.api.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.DeliveryRequest;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.Dropoff;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.Pickup;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;

/**
 * A record's generated {@code toString} prints every component, so one log line
 * or one exception message that interpolates a delivery request puts a customer's
 * address and phone number — or a live partner credential — where ADR 0029 says
 * neither may go. These are the records that carry them.
 */
class DeliveryPartnerRedactionTests {

    private static final String CREDENTIAL = "a-partner-token-placeholder";
    private static final String PHONE = "+998901231076";
    private static final String ADDRESS = "Tashkent, Amir Temur 12";

    private static final Pickup PICKUP = new Pickup(
            41.311081, 69.240562, "Tashkent, Navoi 7", "Branch manager", "+998712001122", "back door");

    private static final Dropoff DROPOFF = new Dropoff(
            41.299496, 69.269873, ADDRESS, "Dilnoza K.", PHONE, "call on arrival", "2", "5", "34");

    @Test
    @DisplayName("a provider call never prints its credential")
    void aProviderCallRedactsItsCredential() {
        ProviderCall call = new ProviderCall(
                "https://partner.example/api", CREDENTIAL, "cmd-1", Duration.ofSeconds(5));

        assertThat(call.toString())
                .doesNotContain(CREDENTIAL)
                .contains("REDACTED")
                .as("an operator still needs to find the call")
                .contains("cmd-1", "https://partner.example/api");
    }

    @Test
    @DisplayName("a pickup prints nothing about the place or the person")
    void aPickupRedactsEverything() {
        assertThat(PICKUP.toString())
                .doesNotContain("Navoi", "Branch manager", "+998712001122", "back door", "41.31", "69.24");
    }

    @Test
    @DisplayName("a dropoff prints nothing about the customer, coordinates included")
    void aDropoffRedactsEverything() {
        assertThat(DROPOFF.toString())
                .as("a precise location is as identifying as the address beside it")
                .doesNotContain(ADDRESS, "Dilnoza", PHONE, "call on arrival", "41.29", "69.26");
    }

    @Test
    @DisplayName("a delivery request names the order and nothing about the people")
    void aRequestRedactsItsEndpointsAndItsPartnerOptions() {
        DeliveryRequest request = new DeliveryRequest(
                "QO-2026-000123", PICKUP, DROPOFF, Instant.parse("2026-08-24T10:00:00Z"),
                true, 145_000, "UZS", Map.of("comment", PHONE));

        assertThat(request.toString())
                .doesNotContain(ADDRESS, PHONE, "Dilnoza", "Navoi")
                .as("the reference is HorecaOS's own and is what a support ticket is opened against")
                .contains("QO-2026-000123");
    }
}
