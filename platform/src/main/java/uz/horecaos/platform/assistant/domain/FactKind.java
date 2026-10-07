package uz.horecaos.platform.assistant.domain;

/** The kind written on a {@link uz.horecaos.platform.assistant.api.RetrievedFact}. */
public enum FactKind {
    PRICE,
    AVAILABILITY,
    BRANCH,
    HOURS,
    COVERAGE,
    ORDER,
    KNOWLEDGE;

    /** Whether quoting this fact states something about money or stock the platform will be held to. */
    public boolean bindsThePlatform() {
        return this == PRICE || this == AVAILABILITY || this == COVERAGE || this == ORDER;
    }
}
