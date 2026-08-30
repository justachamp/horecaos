package uz.horecaos.platform.tenancy.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record Slug(String value) {

    private static final int MAX_LENGTH = 63;
    private static final Pattern FORMAT = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    public Slug {
        Objects.requireNonNull(value, "Slug is required");
        value = value.strip().toLowerCase(Locale.ROOT);
        if (value.length() > MAX_LENGTH || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Slug must contain 1-63 lowercase letters, digits, or internal hyphens");
        }
    }
}
