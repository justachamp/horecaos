package uz.horecaos.platform.courier.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A versioned rate card as the calculator sees it (ADR 0042).
 *
 * <p>The version travels onto every earning at acceptance, so a statement line
 * names the version it was computed under and raising rates in October cannot
 * silently restate September.
 */
public record RateCard(UUID id, int version, String currency, List<RateComponent> components) {

    public RateCard {
        Objects.requireNonNull(id, "A rate card id is required");
        Objects.requireNonNull(currency, "A currency is required");
        components = List.copyOf(Objects.requireNonNull(components, "Components are required"));
        if (version < 1) {
            throw new IllegalArgumentException("A rate card version starts at one");
        }
    }

    public List<RateComponent> of(RateComponentType type) {
        return components.stream().filter(component -> component.type() == type).toList();
    }
}
