package uz.horecaos.platform.fulfillment.domain;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Conditions;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.IntRange;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.TimeWindow;

/**
 * A fluent builder over {@link Conditions}, so a test names only the condition it is about and every
 * other one stays open.
 */
final class ConditionsBuilder {

    private List<String> sources = List.of();
    private List<UUID> channels = List.of();
    private List<UUID> zones = List.of();
    private List<UUID> locations = List.of();
    private @Nullable IntRange prep;
    private @Nullable IntRange distance;
    private @Nullable TimeWindow time;
    private @Nullable Boolean prepaid;

    private ConditionsBuilder() {}

    /** The empty conjunction: matches every order. */
    static ConditionsBuilder any() {
        return new ConditionsBuilder();
    }

    ConditionsBuilder sources(String... values) {
        this.sources = List.of(values);
        return this;
    }

    ConditionsBuilder channels(UUID... values) {
        this.channels = List.of(values);
        return this;
    }

    ConditionsBuilder zones(UUID... values) {
        this.zones = List.of(values);
        return this;
    }

    ConditionsBuilder locations(UUID... values) {
        this.locations = List.of(values);
        return this;
    }

    ConditionsBuilder prep(IntRange value) {
        this.prep = value;
        return this;
    }

    ConditionsBuilder distance(IntRange value) {
        this.distance = value;
        return this;
    }

    ConditionsBuilder time(TimeWindow value) {
        this.time = value;
        return this;
    }

    ConditionsBuilder prepaid(boolean value) {
        this.prepaid = value;
        return this;
    }

    Conditions build() {
        return new Conditions(sources, channels, zones, locations, prep, distance, time, prepaid);
    }
}
