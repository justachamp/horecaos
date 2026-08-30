package uz.horecaos.platform.fiscal.domain;

import java.time.DateTimeException;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The branch's own calendar day, and what to do when the branch names a timezone
 * this JVM does not know (ADR 0038).
 *
 * <p>Uzbekistan is UTC+5 with no DST, and branches trade past midnight. A UTC day
 * therefore rolls over at 05:00 in Tashkent, in the middle of a night service, so
 * every date this module decides anything by — the reporting backstop, the date a
 * legal-entity assignment is resolved on — is the branch's date and never the
 * server's.
 *
 * <p>A tzdb name that will not parse is a data problem in one location's row.
 * Refusing to proceed because of it would let one bad row hide every other
 * tenant's missing receipts, which is strictly worse than treating that one
 * branch as though it kept the platform's day.
 */
public final class BusinessZone {

    private static final Logger log = LoggerFactory.getLogger(BusinessZone.class);

    private BusinessZone() {}

    /**
     * @param named    the branch's timezone as the row holds it, possibly null
     * @param about    what the caller is resolving a zone for, so the warning names
     *                 the row somebody has to fix
     */
    public static ZoneId resolve(String named, ZoneId fallback, Object about) {
        if (named == null) {
            return fallback;
        }
        try {
            return ZoneId.of(named);
        } catch (DateTimeException unknown) {
            log.warn(
                    "{} resolves timezone '{}', which is not a known zone; {} is used for its "
                            + "business date instead.",
                    about,
                    named,
                    fallback);
            return fallback;
        }
    }
}
