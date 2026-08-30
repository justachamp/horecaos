package uz.horecaos.platform.integration.camel.sms;

/**
 * The request bodies smsgw.vas.uz takes, and the one rule that governs all of
 * them (ADR 0028, ADR 0029).
 *
 * <p><strong>A request body on this provider must never be logged, at any level,
 * on any path, including failure.</strong> {@code ProviderHttpClient} already
 * bounds and scrubs the <em>response</em>; nothing bounds a request, because on
 * every other provider in this build a request body is business data. Here it is
 * a credential twice over: {@code key} is the account's secret, and on
 * {@link Send} the {@code text} is the live one-time code, beside the phone
 * number it is being sent to.
 *
 * <p>Which is why these are records with a redacted {@code toString} rather than
 * the {@code Map} the other adapters build. A map prints itself, and the places
 * that print things here are not all ours: Camel writes exchange bodies into
 * route logs and into the message of an exception it wraps, and a record's
 * generated {@code toString} would put the whole credential into both.
 *
 * <p>These types are serialised by Jackson from their component names, so a
 * component name is a wire field name. Renaming one changes the request.
 */
final class SmsGateBody {

    private SmsGateBody() {
    }

    /**
     * {@code POST /send}.
     *
     * <p><strong>{@code weight} is deliberately absent.</strong> The provider
     * documents it as a priority in {@code [0,10]} defaulting to {@code 10} and
     * never says which end is urgent; every example that sets it uses {@code 5}.
     * A verification code sent at the wrong end of an undocumented priority scale
     * is a code that arrives after its own expiry, and the default is the only
     * value whose behaviour the account is already living with. Add it when
     * somebody has asked the provider which direction it runs — not before.
     *
     * <p>{@code seq} is absent for the same class of reason: it exists on
     * {@code /send_msgs}, not here, and the document does not say the provider
     * deduplicates on it anywhere.
     */
    record Send(String login, String key, String sender, String phone, String text) {

        @Override
        public String toString() {
            return "SmsGateBody.Send[REDACTED]";
        }
    }

    /**
     * {@code POST /search}, the uncertainty resolver.
     *
     * <p>Carries the credential and a destination, so it is redacted on the same
     * terms even though it has no message text of its own — the <em>answer</em>
     * does, since the provider returns the text it stored.
     *
     * @param date a unix timestamp naming the day to search. Sent explicitly
     *             rather than left to default, because the default is "the
     *             current day" in a timezone the document does not state, and
     *             this platform's clock is UTC while the gateway's is not
     *             necessarily
     */
    record Search(String login, String key, String phone, long date) {

        @Override
        public String toString() {
            return "SmsGateBody.Search[REDACTED]";
        }
    }
}
