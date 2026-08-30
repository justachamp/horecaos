package uz.horecaos.platform.payments.settlement;

/**
 * The life of one tender (ADR 0046).
 *
 * <pre>
 * PLANNED -&gt; RESERVED -&gt; SETTLED -&gt; REVERSED
 * PLANNED -&gt; RESERVED -&gt; RELEASED
 * PLANNED -&gt; FAILED
 * </pre>
 */
public enum TenderStatus {

    PLANNED,

    /** A balance tender holding points, or an external tender with an initiated intent. */
    RESERVED,

    SETTLED,

    /** Reserved and then given back, because the settlement never completed. */
    RELEASED,

    REVERSED,

    FAILED
}
