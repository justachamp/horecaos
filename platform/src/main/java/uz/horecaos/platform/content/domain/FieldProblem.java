package uz.horecaos.platform.content.domain;

/**
 * One thing wrong, at one place, with a stable code (ADR 0031).
 *
 * <p>Used for the three things the platform says about content: a document that is not the grammar
 * (a 400 on save), a gate that refuses a publication (a 422 with the closed list of codes), and a
 * warning the console shows beside a draft or a live item ({@code problems[]}, {@code health}).
 * The message is for a developer or an author reading it; it never quotes the text an author wrote,
 * because a refusal is logged and an audit row is the wrong place for a second copy of a title.
 *
 * @param field a path into the document or the request, such as {@code texts.ru.title}
 * @param code  a stable code: a grammar code (UNKNOWN_KEY, TOO_LONG, ...) or a publication gate code
 *              ({@code CONTENT_MEDIA_NOT_DISPLAYABLE}, ...)
 */
public record FieldProblem(String field, String code, String message) {

    /** Publication gate and health codes: the closed list of ADR 0157, Publication gates. */
    public static final class Codes {
        public static final String DEFAULT_LOCALE_MISSING = "CONTENT_DEFAULT_LOCALE_MISSING";
        public static final String LOCALE_INCOMPLETE = "CONTENT_LOCALE_INCOMPLETE";
        public static final String MEDIA_NOT_DISPLAYABLE = "CONTENT_MEDIA_NOT_DISPLAYABLE";
        public static final String MEDIA_ASPECT_MISMATCH = "CONTENT_MEDIA_ASPECT_MISMATCH";
        public static final String TARGET_NOT_FOUND = "CONTENT_TARGET_NOT_FOUND";
        public static final String PROMOTION_NOT_FOUND = "CONTENT_PROMOTION_NOT_FOUND";
        public static final String LINK_HOST_NOT_ALLOWED = "CONTENT_LINK_HOST_NOT_ALLOWED";
        public static final String CHANNEL_NOT_A_CONTENT_SURFACE = "CONTENT_CHANNEL_NOT_A_CONTENT_SURFACE";
        public static final String PLACEMENT_FULL = "CONTENT_PLACEMENT_FULL";
        public static final String NOTHING_TO_PUBLISH = "CONTENT_NOTHING_TO_PUBLISH";
        public static final String VIDEO_LIMIT = "CONTENT_VIDEO_LIMIT";
        public static final String POSTER_REQUIRED = "CONTENT_POSTER_REQUIRED";
        public static final String REVISION_MEDIA_RELEASED = "CONTENT_REVISION_MEDIA_RELEASED";

        /** A kind the brand has not switched on (entitlement and policy both must say yes). */
        public static final String KIND_NOT_ENABLED = "CONTENT_KIND_NOT_ENABLED";

        /**
         * A warning on a draft and on health, never a refusal of a read or a takedown: the item carries a video and the
         * tenant's plan no longer includes {@code content.video.enabled}. The storefront stops returning the item, and a
         * new publication of it is refused with {@code ENTITLEMENT_REQUIRED} (403); everything else about it -- listing,
         * reading, ordering, withdrawing, archiving -- keeps working so the console is never locked out of its own item.
         */
        public static final String VIDEO_NOT_ENTITLED = "CONTENT_VIDEO_NOT_ENTITLED";

        /** Health only: a live item bound to a promotion that is not live, so the storefront drops it. */
        public static final String PROMOTION_NOT_LIVE = "CONTENT_PROMOTION_NOT_LIVE";

        /** Health only: a live item whose image has not rendered yet, so the storefront does not show it. */
        public static final String MEDIA_NOT_RENDERED = "CONTENT_MEDIA_NOT_RENDERED";

        private Codes() {}
    }
}
