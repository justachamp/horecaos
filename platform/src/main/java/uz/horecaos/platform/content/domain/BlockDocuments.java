package uz.horecaos.platform.content.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The editorial body: a typed block document, never markup (ADR 0157, Decision 6) -- and the
 * server-side sanitiser that makes that a property of the stored bytes and not of a client's good
 * behaviour.
 *
 * <p>The ADR's alternatives table rejects storing sanitised HTML: allowlist sanitisers have a long
 * history of bypasses by mutation, a client-side sanitiser is not evidence about what arrived, and
 * storing markup ties storage to one renderer. A document of known blocks has nothing to sanitise
 * because HTML, Markdown, SVG, iframes, scripts, {@code data:} and {@code javascript:} links and
 * embedded third-party players are not <em>representable</em>. This class is where that is
 * enforced on write: it accepts a closed set of block types over plain-text runs, rejects every
 * unknown key, bounds the size and the nesting, refuses the characters that spoof text direction,
 * and normalises what it accepts, so the stored document is the sanitiser's output and not the
 * client's input.
 *
 * <pre>
 * BlockDocument  { schemaVersion: 1, blocks: [Block] }          at most 200 blocks
 * Block          PARAGRAPH{inlines} | HEADING{level 2|3, inlines} | QUOTE{inlines}
 *                | LIST{ordered, items: [[Inline]]}              at most 50 items, one level only
 *                | IMAGE{assetId, caption: [Inline]|null} | DIVIDER
 * Inline         { text, bold?, italic?, href? }                 at most 40 runs a block
 * </pre>
 *
 * <p>At most 50,000 characters of text per language (the figure {@code ChannelPageService} already
 * uses). An {@code href} is {@code https}, or {@code tel:}, or {@code mailto:}; {@code http},
 * {@code javascript:}, {@code data:}, protocol-relative and relative links are refused. Whether an
 * {@code https} host is on the brand's allowlist is a publication gate checked at publish and again
 * on every read ({@link DocumentFacts#externalRefs()}); the scheme rule here is the grammar half.
 */
public final class BlockDocuments {

    public static final int MAX_BLOCKS = 200;
    public static final int MAX_RUNS_PER_BLOCK = 40;
    public static final int MAX_LIST_ITEMS = 50;
    public static final int MAX_TEXT_CHARACTERS = 50_000;
    public static final int MAX_RUN_CHARACTERS = 5_000;

    private static final Pattern TEL = Pattern.compile("^tel:\\+?[0-9]{7,15}$");
    private static final Pattern MAILTO =
            Pattern.compile("^mailto:[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$");

    private BlockDocuments() {}

    /**
     * Validates and normalises a block document.
     *
     * @return the normalised document, or empty when it is not one (every problem is on {@code ctx})
     */
    static Optional<ObjectNode> read(@Nullable JsonNode raw, String path, DocumentContext ctx) {
        int before = ctx.problems.all().size();
        ObjectReader document = new ObjectReader(raw, path, ctx.problems);
        if (!document.valid()) {
            return Optional.empty();
        }
        Integer version = document.integer("schemaVersion", 1, 1, 1);
        List<JsonNode> blocks = document.array("blocks", MAX_BLOCKS);
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        ObjectNode out = nodes.objectNode();
        out.put("schemaVersion", version == null ? 1 : version);
        ArrayNode outBlocks = out.putArray("blocks");
        int[] characters = {0};
        int position = 0;
        for (JsonNode block : blocks) {
            String blockPath = document.at("blocks[" + position + "]");
            readBlock(block, blockPath, position, ctx, characters).ifPresent(outBlocks::add);
            position++;
        }
        if (characters[0] > MAX_TEXT_CHARACTERS) {
            ctx.problems.add(path, "TEXT_TOO_LONG", "At most " + MAX_TEXT_CHARACTERS + " characters of text");
        }
        document.finish();
        return ctx.problems.all().size() == before ? Optional.of(out) : Optional.empty();
    }

    private static Optional<ObjectNode> readBlock(
            JsonNode raw, String path, int position, DocumentContext ctx, int[] characters) {
        ObjectReader block = new ObjectReader(raw, path, ctx.problems);
        if (!block.valid()) {
            return Optional.empty();
        }
        BlockType type = block.enumeration("type", BlockType.class, true, null);
        if (type == null) {
            block.finish();
            return Optional.empty();
        }
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        ObjectNode out = nodes.objectNode();
        out.put("type", type.name());
        switch (type) {
            case PARAGRAPH, QUOTE -> out.set("inlines", inlines(block, "inlines", true, ctx, characters));
            case HEADING -> {
                Integer level = block.integer("level", 2, 3, 2);
                out.put("level", level == null ? 2 : level);
                out.set("inlines", inlines(block, "inlines", true, ctx, characters));
            }
            case LIST -> {
                out.put("ordered", block.bool("ordered", false));
                ArrayNode items = out.putArray("items");
                List<JsonNode> rawItems = block.array("items", MAX_LIST_ITEMS);
                int index = 0;
                for (JsonNode item : rawItems) {
                    String itemPath = block.at("items[" + index + "]");
                    items.add(runs(item, itemPath, true, ctx, characters));
                    index++;
                }
                if (rawItems.isEmpty()) {
                    ctx.problems.add(block.at("items"), "REQUIRED", "A list needs at least one item");
                }
            }
            case IMAGE -> {
                UUID asset = block.uuid("assetId", true);
                if (asset != null) {
                    out.put("assetId", asset.toString());
                    ctx.asset(asset, MediaRole.BODY_IMAGE, position, path + ".assetId", null, "IMAGE", 0, 0);
                }
                JsonNode caption = block.child("caption");
                if (caption == null) {
                    out.putNull("caption");
                } else {
                    out.set("caption", runs(caption, block.at("caption"), false, ctx, characters));
                }
            }
            case DIVIDER -> {
                // Nothing but its type.
            }
        }
        block.finish();
        return Optional.of(out);
    }

    private static ArrayNode inlines(
            ObjectReader block, String key, boolean nonEmpty, DocumentContext ctx, int[] characters) {
        JsonNode child = block.child(key);
        if (child == null) {
            if (nonEmpty) {
                ctx.problems.add(block.at(key), "REQUIRED", "Required");
            }
            return JsonNodeFactory.instance.arrayNode();
        }
        return runs(child, block.at(key), nonEmpty, ctx, characters);
    }

    private static ArrayNode runs(JsonNode raw, String path, boolean nonEmpty, DocumentContext ctx, int[] characters) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        if (!raw.isArray()) {
            ctx.problems.add(path, "NOT_AN_ARRAY", "Expected a list of text runs");
            return out;
        }
        if (raw.size() > MAX_RUNS_PER_BLOCK) {
            ctx.problems.add(path, "TOO_MANY", "At most " + MAX_RUNS_PER_BLOCK + " text runs a block");
            return out;
        }
        if (nonEmpty && raw.isEmpty()) {
            ctx.problems.add(path, "REQUIRED", "Needs at least one text run");
        }
        int index = 0;
        for (JsonNode run : raw) {
            String runPath = path + "[" + index + "]";
            index++;
            ObjectReader reader = new ObjectReader(run, runPath, ctx.problems);
            if (!reader.valid()) {
                continue;
            }
            JsonNode textNode = reader.child("text");
            ObjectNode inline = JsonNodeFactory.instance.objectNode();
            if (textNode == null || !textNode.isString()) {
                ctx.problems.add(reader.at("text"), textNode == null ? "REQUIRED" : "NOT_A_STRING", "Expected text");
            } else {
                String text = TextRules.nfc(textNode.stringValue());
                if (TextRules.length(text) > MAX_RUN_CHARACTERS) {
                    ctx.problems.add(
                            reader.at("text"), "TOO_LONG", "At most " + MAX_RUN_CHARACTERS + " characters a run");
                } else if (TextRules.hasForbiddenCharacter(text, true)) {
                    ctx.problems.add(
                            reader.at("text"),
                            "FORBIDDEN_CHARACTER",
                            "Contains a control or direction-override character");
                } else {
                    characters[0] += TextRules.length(text);
                    inline.put("text", text);
                }
            }
            boolean bold = reader.bool("bold", false);
            boolean italic = reader.bool("italic", false);
            if (bold) {
                inline.put("bold", true);
            }
            if (italic) {
                inline.put("italic", true);
            }
            JsonNode href = reader.child("href");
            if (href != null) {
                href(href, reader.at("href"), ctx).ifPresent(value -> inline.put("href", value));
            }
            reader.finish();
            out.add(inline);
        }
        return out;
    }

    /** The normalised href, or empty (with a problem on {@code ctx}) when its scheme or shape is not allowed. */
    static Optional<String> href(JsonNode raw, String path, DocumentContext ctx) {
        if (!raw.isString()) {
            ctx.problems.add(path, "NOT_A_STRING", "Expected a link");
            return Optional.empty();
        }
        String value = raw.stringValue().strip();
        if (value.startsWith("https://") || value.regionMatches(true, 0, "https://", 0, 8)) {
            Optional<ExternalUrls.Parsed> parsed = ExternalUrls.parseHttps(value);
            if (parsed.isEmpty()) {
                ctx.problems.add(path, "HREF_NOT_ALLOWED", "Not an https address this platform will link to");
                return Optional.empty();
            }
            ctx.ref(path, parsed.get());
            return Optional.of(parsed.get().url());
        }
        if (TEL.matcher(value).matches() || MAILTO.matcher(value).matches()) {
            return Optional.of(value);
        }
        ctx.problems.add(path, "HREF_SCHEME_NOT_ALLOWED", "A link is https, tel: or mailto: and nothing else");
        return Optional.empty();
    }

    /** The block types a document is made of; a client skips one it does not know (ADR 0157, Negative). */
    enum BlockType {
        PARAGRAPH,
        HEADING,
        QUOTE,
        LIST,
        IMAGE,
        DIVIDER
    }
}
