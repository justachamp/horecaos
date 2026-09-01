package uz.horecaos.platform.conversations.domain;

/**
 * What tapping a {@link FlowButton} does. {@code URL} opens a link — the
 * channel never tells the engine a URL button was tapped, so it never causes a
 * state transition. {@code CALLBACK} is answered by the channel adapter with
 * an inbound button-tap event the engine resolves back to {@link
 * FlowButton#next()}.
 */
public enum FlowButtonKind {
    URL,
    CALLBACK
}
