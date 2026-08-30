package uz.horecaos.platform.catalog.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything a Click or Payme receipt line needs to know about one priceable
 * node (ADR 0038).
 *
 * <p>V0021 carried two fields of this — ИКПУ/MXIK and the package code — as
 * columns on products, variants and modifier options, and called them "the
 * smaller interim" in its own comment. Reading both provider contracts showed
 * the interim was roughly a third of the required per-line field list. The four
 * fields a conformant line cannot be built without are {@link #mxikCode()},
 * {@link #packageCode()}, {@link #fiscalUnitCode()} and {@link #fiscalName()};
 * the rest are optional on the wire or describe a constraint rather than a line.
 *
 * <p>Every field is optional here even though the wire requires four of them.
 * Completeness is a validation rule rather than a construction rule because
 * ADR 0038's rollout turns the rule on per brand once that brand's coverage is
 * clean, and a type that refuses to exist half-filled cannot be switched on per
 * brand — it would refuse an operator saving half a classification and coming
 * back to it, and it would refuse the partially filled rows V0021 collected.
 * {@link #missingFields()} is what makes the gap actionable instead.
 *
 * @param mxikCode                  ИКПУ/MXIK. Click {@code SPIC}, Payme
 *                                  {@code code}. No format is asserted: the
 *                                  shape belongs to the official reference list
 * @param packageCode               код упаковки. Required by both providers.
 *                                  Nothing to do with the legacy bundle fields
 *                                  {@code variants.package_id} and
 *                                  {@code is_package}
 * @param fiscalUnitCode            Click {@code Units}, Payme {@code units}.
 *                                  Numeric, and distinct from the varchar
 *                                  measurement unit a variant is authored with
 * @param fiscalName                Click {@code Name}, with the unit of measure
 *                                  inside it and capped at 63 characters
 * @param barcode                   Click {@code Barcode}. Optional on the wire
 * @param markingRequired           whether this good carries a per-unit
 *                                  identifier that must reach the receipt
 * @param markingScheme             how those identifiers are encoded
 * @param excisable                 carried because receipts and aggregator
 *                                  feeds ask for it, not because anything here
 *                                  computes from it
 * @param alcoholByVolumeBasisPoints 4250 for 42.5% ABV, or null
 * @param ageRestrictionYears       the minimum age this node may be sold at, or
 *                                  null when it is unrestricted
 */
public record FiscalClassification(
        String mxikCode,
        String packageCode,
        Integer fiscalUnitCode,
        String fiscalName,
        String barcode,
        boolean markingRequired,
        MarkingScheme markingScheme,
        boolean excisable,
        Integer alcoholByVolumeBasisPoints,
        Integer ageRestrictionYears) {

    /**
     * Click caps {@code Name} at 63 characters.
     *
     * <p>The cap is the reason this field exists separately from the display
     * name at all: a Cyrillic dish name plus its modifiers plus a unit exceeds
     * it routinely, and silently truncating a customer-facing name at
     * fiscalization time produces a receipt line nobody can reconcile against a
     * menu.
     */
    public static final int FISCAL_NAME_LIMIT = 63;

    private static final FiscalClassification UNCLASSIFIED = new FiscalClassification(
            null, null, null, null, null, false, MarkingScheme.NONE, false, null, null);

    /** How a marked good's per-unit identifiers are encoded. */
    public enum MarkingScheme {
        /** Not a marked good. */
        NONE,
        /** The Data Matrix codes the Uzbek marking system uses. */
        DATA_MATRIX
    }

    /**
     * Normalises blanks away and refuses the two states that cannot be stored.
     *
     * <p>Blank becomes absent because an operator who tabs through a field
     * submits an empty string, which satisfies "the column is set" while
     * classifying nothing — and a coverage report built on "is not null" would
     * then call a brand complete while its receipts went out unclassified.
     *
     * <p>The two refusals are the ones where accepting the value quietly would
     * put a wrong figure on a legal document: a fiscal name over Click's limit
     * (which would be truncated by the column, on a receipt, without anyone
     * being told) and a marking scheme that disagrees with whether marking is
     * required (which is either a marked good whose codes nobody will capture,
     * or an unmarked good whose fiscal document blocks forever waiting for
     * codes that do not exist).
     */
    public FiscalClassification {
        mxikCode = blankToNull(mxikCode);
        packageCode = blankToNull(packageCode);
        fiscalName = blankToNull(fiscalName);
        barcode = blankToNull(barcode);
        markingScheme = markingScheme == null ? MarkingScheme.NONE : markingScheme;

        if (fiscalName != null && fiscalName.length() > FISCAL_NAME_LIMIT) {
            throw new IllegalArgumentException(
                    "A fiscal name is capped at %d characters by Click's Name field; \"%s\" is %d. "
                            .formatted(FISCAL_NAME_LIMIT, fiscalName, fiscalName.length())
                            + "Shorten it deliberately rather than letting a receipt line be "
                            + "truncated at fiscalization time");
        }
        if (markingRequired != (markingScheme != MarkingScheme.NONE)) {
            throw new IllegalArgumentException(
                    "Marking requirement and marking scheme must agree: markingRequired=%s with scheme %s"
                            .formatted(markingRequired, markingScheme));
        }
        if (fiscalUnitCode != null && fiscalUnitCode <= 0) {
            throw new IllegalArgumentException(
                    "A fiscal unit code is an identifier from the tax authority's list, so "
                            + fiscalUnitCode + " is not one");
        }
    }

    /** A node nobody has said anything about yet. */
    public static FiscalClassification unclassified() {
        return UNCLASSIFIED;
    }

    /**
     * The four fields both providers require, and nothing else.
     *
     * <p>The common case, and the one a bulk assignment tool writes: an ordinary
     * dish is not marked, not excisable and not age restricted.
     */
    public static FiscalClassification of(String mxikCode, String packageCode,
            Integer fiscalUnitCode, String fiscalName) {
        return new FiscalClassification(mxikCode, packageCode, fiscalUnitCode, fiscalName,
                null, false, MarkingScheme.NONE, false, null, null);
    }

    /** Whether anything at all has been recorded about this node. */
    public boolean isEmpty() {
        return equals(UNCLASSIFIED);
    }

    /** Whether a conformant Click or Payme receipt line can be built from this. */
    public boolean isComplete() {
        return missingFields().isEmpty();
    }

    /**
     * Which of the four required fields are absent, named the way an operator
     * would recognise them.
     *
     * <p>Returned rather than a boolean because "this node is unclassified" is
     * not something a brand with four hundred dishes can act on. "This node has
     * an ИКПУ and no package code" is one field to fill in.
     */
    public List<String> missingFields() {
        List<String> missing = new ArrayList<>(4);
        if (mxikCode == null) {
            missing.add("ИКПУ/MXIK");
        }
        if (packageCode == null) {
            missing.add("package code");
        }
        if (fiscalUnitCode == null) {
            missing.add("fiscal unit code");
        }
        if (fiscalName == null) {
            missing.add("fiscal name");
        }
        return List.copyOf(missing);
    }

    /**
     * This classification, or {@code fallback} where this one says nothing.
     *
     * <p>Used only across a modifier option's link to a variant, where the two
     * describe the same physical good — an extra shot that is itself a sellable
     * product does not need classifying twice, and classifying it twice is how
     * one thing reaches a receipt under two codes that can be corrected
     * independently.
     *
     * <p>Field by field rather than all-or-nothing, so a modifier that overrides
     * only the fiscal name — the same coffee shot, sold as an addition — keeps
     * the linked variant's ИКПУ instead of losing it.
     *
     * <p>The descriptive fields inherit where this one is silent. The three
     * constraints — marking, excise, and the age gate — take the stricter of the
     * two instead, because they are the fields where inheriting the looser value
     * has a legal consequence: a modifier linked to a marked or age-restricted
     * variant is that same physical good, and a modifier row left at the default
     * would otherwise say the good is unmarked and unrestricted.
     */
    public FiscalClassification orInherited(FiscalClassification fallback) {
        if (fallback == null || fallback.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return fallback;
        }
        boolean marking = markingRequired || fallback.markingRequired();
        MarkingScheme scheme = markingRequired
                ? markingScheme
                : (fallback.markingRequired() ? fallback.markingScheme() : MarkingScheme.NONE);
        return new FiscalClassification(
                mxikCode != null ? mxikCode : fallback.mxikCode(),
                packageCode != null ? packageCode : fallback.packageCode(),
                fiscalUnitCode != null ? fiscalUnitCode : fallback.fiscalUnitCode(),
                fiscalName != null ? fiscalName : fallback.fiscalName(),
                barcode != null ? barcode : fallback.barcode(),
                marking, scheme,
                excisable || fallback.excisable(),
                alcoholByVolumeBasisPoints != null
                        ? alcoholByVolumeBasisPoints : fallback.alcoholByVolumeBasisPoints(),
                stricterAge(ageRestrictionYears, fallback.ageRestrictionYears()));
    }

    private static Integer stricterAge(Integer own, Integer inherited) {
        if (own == null) {
            return inherited;
        }
        return inherited == null ? own : Math.max(own, inherited);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
