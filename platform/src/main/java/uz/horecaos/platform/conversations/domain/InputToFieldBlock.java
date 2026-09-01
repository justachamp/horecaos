package uz.horecaos.platform.conversations.domain;

/**
 * Prompts, then waits for the customer's next free-text message and captures
 * it into {@code field} before advancing — the welcome series' feedback
 * capture, generalised.
 *
 * <p>The captured value is envelope-encrypted (ADR 0059's PII posture) and
 * held on {@code conversations.flow_runs.captured_fields_protected} for the
 * life of the run. Where it lands beyond that — customer data, or a fact a
 * future owning module governs — is named remaining work, not built here (see
 * the ADR's own flag that {@code helpcenter} has no owning ADR to route into).
 *
 * @param prompt the message sent before waiting
 * @param field the key the captured text is stored under, and the key a later
 *              {@code {{field}}} placeholder or {@link ConditionBlock} reads
 * @param next the state to advance to once text is captured
 */
public record InputToFieldBlock(String prompt, String field, String next) implements FlowBlock {

    public static final String TYPE = "input-to-field";

    @Override
    public String type() {
        return TYPE;
    }
}
