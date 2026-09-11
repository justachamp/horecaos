package uz.horecaos.platform.courier.domain;

import uz.horecaos.platform.iam.api.protection.DataClass;

/**
 * One protected field of a courier's compliance file (IA 3.3, ADR 0029 over ADR
 * 0042).
 *
 * <p>The enum is the single place that says which columns exist, what each is
 * classified as, and — by existing at all — which strings may be interpolated
 * into a column position. Every write and every reveal below goes through it, so
 * a new field is added once and is protected, revealed, audited and refused to
 * the list read without anybody editing four places.
 *
 * <p>The classification split is the one ADR 0029 draws and is not cosmetic: the
 * key is per tenant <em>and per class</em>, so filing a passport number under the
 * ordinary-personal key would store it below its classification. A passport, a
 * ПИНФЛ, a driving licence and a vehicle registration certificate are
 * government-issued identifiers of a named person; a plate, a home address, an
 * emergency contact, a referral and a manager's notes are ordinary personal
 * data. The notes are in that second group rather than out of the file
 * altogether because free text is the field that eventually holds a diagnosis or
 * a debt, and a column protected only while nobody misuses it is not protected.
 */
public enum ComplianceField {

    /** Passport series and number. */
    PASSPORT("protected_passport", DataClass.PERSONAL_SENSITIVE),

    /** The fourteen-digit ПИНФЛ. Never on a list read — see IA 3.3's own trap note. */
    PINFL("protected_pinfl", DataClass.PERSONAL_SENSITIVE),

    /** Licence number and class, held together inside one envelope. */
    DRIVING_LICENCE("protected_driving_licence", DataClass.PERSONAL_SENSITIVE),

    /** The vehicle registration certificate number. */
    VEHICLE_REGISTRATION("protected_vehicle_registration", DataClass.PERSONAL_SENSITIVE),

    /** The plate the vehicle carries. */
    VEHICLE_PLATE("protected_vehicle_plate", DataClass.PERSONAL),

    /** Where the courier lives. */
    ADDRESS("protected_address", DataClass.PERSONAL),

    /** A name and a number belonging to somebody who never signed up for this system. */
    EMERGENCY_CONTACT("protected_emergency_contact", DataClass.PERSONAL),

    /** Who brought this courier in. */
    REFERRAL("protected_referral", DataClass.PERSONAL),

    /** Free text a manager wrote about a person. */
    NOTES("protected_notes", DataClass.PERSONAL);

    private final String column;
    private final DataClass dataClass;

    ComplianceField(String column, DataClass dataClass) {
        this.column = column;
        this.dataClass = dataClass;
    }

    /**
     * The column this field is stored in.
     *
     * <p>Read by the store when it builds a statement. Because the only source
     * of a column name is this enum, no caller-supplied string ever reaches a
     * column position — which is what makes a per-field update safe to assemble
     * rather than writing nine near-identical statements.
     */
    public String column() {
        return column;
    }

    public DataClass dataClass() {
        return dataClass;
    }
}
