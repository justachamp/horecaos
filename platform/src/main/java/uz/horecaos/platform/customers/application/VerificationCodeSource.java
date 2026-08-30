package uz.horecaos.platform.customers.application;

/**
 * Where the one-time code for a destination comes from (ADR 0015, ADR 0051).
 *
 * <p>A seam with exactly one reason to exist: a developer, or the owner of this
 * platform, has to be able to sign in on a laptop where no SMS gateway is
 * configured and no message can be sent. Every other way of arranging that is
 * worse. Reading the code out of a log puts a live credential in a log file, which
 * ADR 0028 forbids in as many words. Returning it in the response makes the
 * verification endpoint an oracle and the six digits decoration. Wiring a
 * "development transport" that swallows the message makes an unconfigured
 * deployment look identical to a working one, which is precisely what
 * {@code VerificationTransportGuard} exists to prevent.
 *
 * <p>So instead: one implementation draws from a CSPRNG and asks for the message
 * to be sent, and a second — which cannot exist outside a local profile, and whose
 * configuration a non-local profile refuses to start with — answers a single
 * configured number with a single configured code and asks for nothing to be sent.
 *
 * <p><strong>Read the guard before adding a third.</strong> A fixed one-time code
 * that survived into production would not be a weakened control; it would be a
 * total authentication bypass for every customer of every tenant, reachable by
 * anybody who could type a phone number. That is why the local binding is not the
 * only lock on it.
 */
public interface VerificationCodeSource {

    /**
     * The code to send to this destination, and whether it must actually travel.
     *
     * @param destination E.164, already canonicalised by the caller, so that a
     *                    preset number cannot be missed by being typed a second
     *                    way
     */
    Code codeFor(String destination);

    /**
     * @param value            the six digits the customer will be asked for
     * @param requiresDelivery false only for a preset code, which the customer
     *                         already knows. The caller must not call a transport
     *                         for one — there is nothing to send, and on a laptop
     *                         there is nothing to send it with
     */
    record Code(String value, boolean requiresDelivery) {

        /**
         * Deliberately overridden. A record's generated {@code toString} prints
         * every component, so one incautious log line would put a live one-time
         * code into a log file.
         */
        @Override
        public String toString() {
            return "Code[requiresDelivery=" + requiresDelivery + "]";
        }
    }
}
