package uz.horecaos.platform.helpcenter.domain;

import java.util.List;
import java.util.UUID;

/**
 * The help a brand publishes for its own customers.
 *
 * <p>Read-only values. Nothing here is personal data and nothing is
 * tenant-sensitive beyond the brand it belongs to, which is why the storefront
 * read is anonymous like the menu: a customer looking for a delivery-hours
 * answer before they have an account is the case this exists for.
 */
public final class SupportContent {

    private SupportContent() {}

    /**
     * @param name resolved in the requested locale, falling back to any
     *     published translation rather than to the code -- a code is an
     *     authoring identifier and showing one is showing a database value.
     */
    public record FaqCategory(UUID categoryId, String code, String name, int sortOrder, List<FaqEntry> entries) {}

    public record FaqEntry(UUID entryId, String code, String question, String answer, int sortOrder) {}

    /**
     * @param platform a checked vocabulary, so a storefront can choose an icon
     *     from it rather than parsing the URL to guess.
     * @param imageUrl null unless an operator overrode the platform's own
     *     artwork with an uploaded asset.
     */
    public record SocialLink(UUID linkId, String platform, String url, String imageUrl, int sortOrder) {}
}
