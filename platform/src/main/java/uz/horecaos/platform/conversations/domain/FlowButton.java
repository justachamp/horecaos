package uz.horecaos.platform.conversations.domain;

import org.jspecify.annotations.Nullable;

/**
 * One button on a {@link ButtonsBlock}. Exactly one of {@code url} (for
 * {@link FlowButtonKind#URL}) or {@code key}/{@code next} (for {@link
 * FlowButtonKind#CALLBACK}) is set — {@link FlowDocumentValidator} rejects
 * anything else at authoring time.
 *
 * @param label the button's visible text
 * @param kind whether tapping opens a link or answers the flow
 * @param url the link a {@code URL} button opens, possibly carrying {@code
 *            {{variable}}} placeholders (the storefront URL, notably)
 * @param key the stable, engine-local identifier a {@code CALLBACK} tap is
 *            reported back with — never a secret and never trusted to name a
 *            state on its own; the engine resolves it only against the
 *            current run's own current block, so a forged key targeting a
 *            different flow or a different button simply matches nothing
 * @param next the state a {@code CALLBACK} tap advances to
 */
public record FlowButton(
        String label,
        FlowButtonKind kind,
        @Nullable String url,
        @Nullable String key,
        @Nullable String next) {

    public static FlowButton url(String label, String url) {
        return new FlowButton(label, FlowButtonKind.URL, url, null, null);
    }

    public static FlowButton callback(String label, String key, String next) {
        return new FlowButton(label, FlowButtonKind.CALLBACK, null, key, next);
    }
}
