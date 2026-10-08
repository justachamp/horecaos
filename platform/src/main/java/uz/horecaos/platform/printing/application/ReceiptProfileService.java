package uz.horecaos.platform.printing.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.configuration.rls.TenantRlsSession;
import uz.horecaos.platform.printing.api.PrintingConfigurationKeys;
import uz.horecaos.platform.printing.application.PrintAudit.Subject;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.ProfileRow;
import uz.horecaos.platform.printing.render.TextCells;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * A brand's receipt profile (ADR 0154, Decision 4): the closed set of presentation a tenant may change --
 * whether the logo prints and a footer of up to three lines per locale -- and nothing else.
 *
 * <p>The seller's name and TIN, the order number, the totals, the QR and the fiscal block are not fields: a
 * tenant cannot move, resize or remove them, because a presentation layer that can reorder evidence is a
 * compliance defect. There is no template designer, and this is why. A brand with no profile has the default
 * one (logo off, no footer), version 0; the first save creates the row at version 1.
 */
@Service
public class ReceiptProfileService {

    /** Three lines, each at most as wide as a whole 80 mm roll. */
    static final int MAXIMUM_LINES = 3;

    static final int MAXIMUM_LINE_CELLS = 48;

    private final TenantRlsSession rls;
    private final JdbcPrintingStore store;
    private final PrintingGuard guard;
    private final PrintAudit audit;
    private final Clock clock;

    public ReceiptProfileService(
            TenantRlsSession rls, JdbcPrintingStore store, PrintingGuard guard, PrintAudit audit, Clock clock) {
        this.rls = rls;
        this.store = store;
        this.guard = guard;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ProfileRow get(UUID tenantId, UUID brandId) {
        rls.bindTenant(tenantId);
        return store.findProfile(tenantId, brandId)
                .orElseGet(() -> new ProfileRow(tenantId, brandId, false, Map.of(), 0, "", Instant.EPOCH));
    }

    /**
     * Saves the profile under the version the form was opened at (0 for a brand that has none). The footer is
     * authored text that reaches a fiscal receipt, so it is validated here and by the table's own CHECK.
     */
    @Transactional
    public ProfileRow put(
            UUID tenantId,
            UUID brandId,
            boolean printLogo,
            Map<String, List<String>> footerTexts,
            long expectedVersion,
            String actorSubject) {
        rls.bindTenant(tenantId);
        guard.requireEnabledForBrand(tenantId, brandId);
        Map<String, List<String>> footers = validated(footerTexts);
        ProfileRow before = get(tenantId, brandId);
        if (before.version() != expectedVersion) {
            throw ApiException.staleVersion(expectedVersion, before.version());
        }
        Instant now = clock.instant();
        try {
            if (before.version() == 0) {
                store.insertProfile(tenantId, brandId, printLogo, footers, actorSubject, now);
            } else if (store.updateProfile(
                            tenantId, brandId, printLogo, footers, actorSubject, (int) expectedVersion, now)
                    .isEmpty()) {
                throw ApiException.staleVersion(
                        expectedVersion,
                        store.findProfile(tenantId, brandId)
                                .map(ProfileRow::version)
                                .orElse(0));
            }
        } catch (DataIntegrityViolationException refused) {
            String detail = String.valueOf(refused.getMostSpecificCause().getMessage());
            if (detail.contains("fk_receipt_profile_brand")) {
                throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such brand");
            }
            // Two first saves at version 0 meet on the primary key: the second is a stale form.
            throw ApiException.staleVersion(expectedVersion, 1);
        }
        ProfileRow after = store.findProfile(tenantId, brandId).orElseThrow();
        audit.changed(
                "printing.profile.changed",
                AuditClass.BUSINESS,
                new Subject(tenantId, brandId, null, "printing.receipt_profile", brandId, actorSubject, null),
                snapshot(before),
                snapshot(after));
        return after;
    }

    private static Map<String, List<String>> validated(Map<String, List<String>> footerTexts) {
        Map<String, List<String>> clean = new LinkedHashMap<>();
        footerTexts.forEach((locale, lines) -> {
            if (!PrintingConfigurationKeys.DOCUMENT_LOCALES.contains(locale)) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "A footer is written in one of " + PrintingConfigurationKeys.DOCUMENT_LOCALES);
            }
            if (lines.size() > MAXIMUM_LINES) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "A footer is at most three lines");
            }
            List<String> stripped = lines.stream()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .toList();
            for (String line : stripped) {
                if (TextCells.length(line) > MAXIMUM_LINE_CELLS) {
                    throw new ApiException(
                            ErrorCode.VALIDATION_FAILED,
                            "A footer line is at most " + MAXIMUM_LINE_CELLS
                                    + " characters: it must fit an 80 mm roll");
                }
                if (line.codePoints().anyMatch(Character::isISOControl)) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "A footer line is plain text");
                }
            }
            if (!stripped.isEmpty()) {
                clean.put(locale, stripped);
            }
        });
        return clean;
    }

    /** What the audit fact compares. The footer's words are authored text and are recorded as lengths only. */
    static Map<String, Object> snapshot(ProfileRow profile) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("printLogo", profile.printLogo());
        for (String locale : PrintingConfigurationKeys.DOCUMENT_LOCALES) {
            int length = profile.footerTexts().getOrDefault(locale, List.of()).stream()
                    .mapToInt(String::length)
                    .sum();
            fields.put("footerLength" + locale.replace("-", ""), length);
        }
        return fields;
    }

    /** Whether any brand-level logo is on: used by tests. */
    Optional<ProfileRow> find(UUID tenantId, UUID brandId) {
        return store.findProfile(tenantId, brandId);
    }
}
