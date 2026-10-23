package uz.horecaos.platform.catalog.api;

import java.util.UUID;

/**
 * Whether a brand's menu and editor show the КБЖУ accuracy line (ADR 0137, row {@code 4.2c}): the one
 * code-owned sentence under a nutrition table that says the figures are approximate.
 *
 * <p>Whether any such line is required, and its exact wording, is a question for legal and product that the
 * record leaves open. This switch exists so that the eventual wording is a content change and not a code
 * change; it is off until someone turns it on, and nothing reads it for anything but drawing the line.
 */
@FunctionalInterface
public interface NutritionNoticeSwitch {

    /** Nobody asked for the line: what every reader built before the switch existed behaves as. */
    NutritionNoticeSwitch OFF = (tenantId, brandId) -> false;

    boolean enabled(UUID tenantId, UUID brandId);
}
