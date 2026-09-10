package uz.horecaos.platform.tenancy.domain;

import java.util.List;
import java.util.Optional;

/**
 * The countries this platform trades in (ADR 0090), each with the currency and
 * timezone a tenant there starts from.
 *
 * <p>A market, not a hosting location: every tenant's data is hosted in one
 * place whatever its market.
 */
public final class Markets {

    /** One country the platform serves. */
    public record Market(String code, String name, String defaultCurrency, String defaultTimezone) {}

    private static final List<Market> ALL = List.of(
            new Market("UZ", "Uzbekistan", "UZS", "Asia/Tashkent"),
            new Market("KZ", "Kazakhstan", "KZT", "Asia/Almaty"),
            new Market("GE", "Georgia", "GEL", "Asia/Tbilisi"));

    private Markets() {}

    public static List<Market> all() {
        return ALL;
    }

    public static Optional<Market> find(String code) {
        return ALL.stream().filter(market -> market.code().equals(code)).findFirst();
    }
}
