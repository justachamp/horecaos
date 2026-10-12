package uz.horecaos.platform.content.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The typed documents (ADR 0157, "The typed documents"): strict decoding of what an author sent into
 * the document that is stored.
 *
 * <p>Every kind has a closed grammar. Unknown keys are refused at every level; an omitted optional
 * value takes a stated default and is never an error; every language key is canonicalised to a stored
 * BCP 47 tag ({@code uz} is read as {@code uz-Latn} and never stored); strings are NFC-normalised
 * and stripped; and the result is a <em>normalised</em> tree -- defaults filled in, locales
 * canonical, ids lower-case -- so two drafts that say the same thing hash alike
 * ({@link ContentHash}). What the document refers to outside itself comes out beside it as
 * {@link DocumentFacts}, extracted by the same code that validated it, so the gates, the
 * {@code revision_media} rows and the read model cannot disagree with the grammar about what the
 * document uses.
 *
 * <p>This is grammar only. Whether the assets exist and are public, whether the targets are in the
 * menu, whether a host is on the allowlist and whether the brand's default language has text are
 * publication gates, run by the service against other modules' answers.
 */
public final class ContentDocuments {

    /** The size ceiling of a stored document; the table's CHECK is the same figure. */
    public static final int MAX_BYTES = 262_144;

    private static final double BANNER_RATIO = 3.0;
    private static final double CARD_RATIO = 1.5;
    private static final Pattern CLOCK = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    private static final Pattern EMAIL =
            Pattern.compile("^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9][0-9 ()-]{5,22}[0-9]$");

    private ContentDocuments() {}

    /** A document that is the grammar, normalised, with what it refers to. */
    public record Validated(ObjectNode document, DocumentFacts facts) {}

    /**
     * @throws DocumentProblems.InvalidDocumentException when the document is not the grammar of its kind
     */
    public static Validated validate(ContentKind kind, @Nullable JsonNode raw) {
        DocumentContext ctx = new DocumentContext();
        ObjectNode out =
                switch (kind) {
                    case BANNER, PROMO_CARD -> card(kind, raw, ctx);
                    case PRODUCT_GROUP -> group(raw, ctx);
                    case STORY_GROUP -> story(raw, ctx);
                    case POPUP -> popup(raw, ctx);
                    case NEWS, RECIPE -> article(raw, ctx);
                    case GALLERY_ALBUM -> album(raw, ctx);
                    case VACANCY -> vacancy(raw, ctx);
                };
        if (ctx.problems.isEmpty() && CanonicalJson.jsonbTextBytes(out) > MAX_BYTES) {
            ctx.problems.add("", "TOO_LARGE", "A document is at most " + MAX_BYTES + " bytes");
        }
        if (!ctx.problems.isEmpty()) {
            throw new DocumentProblems.InvalidDocumentException(ctx.problems.all());
        }
        return new Validated(out, ctx.facts());
    }

    // ===================================================== BANNER, PROMO_CARD

    private static ObjectNode card(ContentKind kind, @Nullable JsonNode raw, DocumentContext ctx) {
        ObjectReader doc = new ObjectReader(raw, "", ctx.problems);
        ObjectNode out = start(doc);
        double ratio = kind == ContentKind.BANNER ? BANNER_RATIO : CARD_RATIO;
        int minWidth = kind == ContentKind.BANNER ? 960 : 600;

        ObjectNode texts = out.putObject("texts");
        for (Map.Entry<String, JsonNode> entry : doc.localeMap("texts").entrySet()) {
            String path = "texts." + entry.getKey();
            ObjectReader text = new ObjectReader(entry.getValue(), path, ctx.problems);
            String title = text.string("title", 80, true, false);
            String subtitle = text.string("subtitle", 160, false, false);
            String ctaLabel = text.string("ctaLabel", 24, false, false);
            String alt = text.string("alt", 140, false, false);
            source(text);
            UUID mediaAssetId = text.uuid("mediaAssetId", false);
            text.finish();
            if (title == null) {
                continue;
            }
            ctx.locale(entry.getKey());
            ObjectNode node = texts.putObject(entry.getKey());
            node.put("title", title);
            putNullable(node, "subtitle", subtitle);
            putNullable(node, "ctaLabel", ctaLabel);
            putNullable(node, "alt", alt);
            node.put("source", "AUTHORED");
            putNullable(node, "mediaAssetId", mediaAssetId);
            if (mediaAssetId != null) {
                ctx.asset(mediaAssetId, MediaRole.PRIMARY, 0, path + ".mediaAssetId", null, null, ratio, minWidth);
            }
        }

        ObjectReader media = doc.object("media", false);
        UUID primary = media.uuid("primaryAssetId", false);
        UUID poster = media.uuid("posterAssetId", false);
        media.finish();
        ObjectNode mediaOut = out.putObject("media");
        putNullable(mediaOut, "primaryAssetId", primary);
        putNullable(mediaOut, "posterAssetId", poster);
        if (primary != null) {
            ctx.asset(primary, MediaRole.PRIMARY, 0, "media.primaryAssetId", poster, null, ratio, minWidth);
        } else if (poster != null) {
            ctx.problems.add("media.posterAssetId", "POSTER_WITHOUT_MEDIA", "A poster belongs to a video");
        }

        out.set("link", link(doc, "link", ctx));
        doc.finish();
        return out;
    }

    // ============================================================ PRODUCT_GROUP

    private static ObjectNode group(@Nullable JsonNode raw, DocumentContext ctx) {
        ObjectReader doc = new ObjectReader(raw, "", ctx.problems);
        ObjectNode out = start(doc);

        ObjectNode texts = out.putObject("texts");
        for (Map.Entry<String, JsonNode> entry : doc.localeMap("texts").entrySet()) {
            ObjectReader text = new ObjectReader(entry.getValue(), "texts." + entry.getKey(), ctx.problems);
            String title = text.string("title", 60, true, false);
            text.finish();
            if (title != null) {
                ctx.locale(entry.getKey());
                texts.putObject(entry.getKey()).put("title", title);
            }
        }

        GroupMode mode = doc.enumeration("mode", GroupMode.class, true, null);
        out.put("mode", mode == null ? GroupMode.MANUAL.name() : mode.name());

        ArrayNode members = out.putArray("members");
        List<JsonNode> rawMembers = doc.array("members", 40);
        Set<UUID> seen = new LinkedHashSet<>();
        int index = 0;
        for (JsonNode member : rawMembers) {
            ObjectReader reader = new ObjectReader(member, doc.at("members[" + index + "]"), ctx.problems);
            index++;
            String type = reader.string("type", 16, false, false);
            if (type != null && !type.equals("PRODUCT")) {
                ctx.problems.add(reader.at("type"), "UNKNOWN_VALUE", "Only PRODUCT members are supported");
            }
            UUID id = reader.uuid("id", true);
            reader.finish();
            if (id != null && seen.add(id)) {
                ObjectNode node = members.addObject();
                node.put("type", "PRODUCT");
                node.put("id", id.toString());
                ctx.product(id);
            }
        }
        UUID categoryId = doc.uuid("categoryId", false);
        GroupOrder order = doc.enumeration("order", GroupOrder.class, false, null);
        Integer limit = doc.optionalInteger("limit", 1, 40);
        if (mode == GroupMode.MANUAL) {
            if (categoryId != null || order != null || limit != null) {
                ctx.problems.add("mode", "NOT_FOR_MODE", "categoryId, order and limit belong to BY_CATEGORY");
            }
            out.putNull("categoryId");
            out.putNull("order");
            out.putNull("limit");
        } else if (mode == GroupMode.BY_CATEGORY) {
            if (!rawMembers.isEmpty()) {
                ctx.problems.add("members", "NOT_FOR_MODE", "members belong to MANUAL");
            }
            if (categoryId == null) {
                ctx.problems.add("categoryId", "REQUIRED", "A by-category group names its category");
            } else {
                ctx.category(categoryId);
            }
            putNullable(out, "categoryId", categoryId);
            out.put("order", order == null ? GroupOrder.MENU_ORDER.name() : order.name());
            out.put("limit", limit == null ? 8 : limit);
        } else {
            putNullable(out, "categoryId", categoryId);
            out.putNull("order");
            out.putNull("limit");
        }
        doc.finish();
        return out;
    }

    // ============================================================= STORY_GROUP

    private static ObjectNode story(@Nullable JsonNode raw, DocumentContext ctx) {
        ObjectReader doc = new ObjectReader(raw, "", ctx.problems);
        ObjectNode out = start(doc);

        ObjectNode texts = out.putObject("texts");
        for (Map.Entry<String, JsonNode> entry : doc.localeMap("texts").entrySet()) {
            ObjectReader text = new ObjectReader(entry.getValue(), "texts." + entry.getKey(), ctx.problems);
            String title = text.string("title", 40, true, false);
            text.finish();
            if (title != null) {
                ctx.locale(entry.getKey());
                texts.putObject(entry.getKey()).put("title", title);
            }
        }
        UUID avatar = doc.uuid("avatarAssetId", false);
        putNullable(out, "avatarAssetId", avatar);
        if (avatar != null) {
            ctx.asset(avatar, MediaRole.AVATAR, 0, "avatarAssetId", null, "IMAGE", 1.0, 0);
        }

        ArrayNode slides = out.putArray("slides");
        List<JsonNode> rawSlides = doc.array("slides", 10);
        int index = 0;
        for (JsonNode rawSlide : rawSlides) {
            String path = doc.at("slides[" + index + "]");
            int position = index + 1;
            index++;
            ObjectReader slide = new ObjectReader(rawSlide, path, ctx.problems);
            // The position an author sent is read and replaced by the slide's place in the list: an
            // order is the order of the array, and a number that can disagree with it is a bug waiting.
            slide.integer("position", 1, 10, position);
            ObjectNode node = slides.addObject();
            node.put("position", position);

            ObjectReader media = slide.object("media", true);
            SlideKind kind = media.enumeration("kind", SlideKind.class, false, SlideKind.IMAGE);
            UUID assetId = media.uuid("assetId", true);
            UUID poster = media.uuid("posterAssetId", false);
            media.finish();
            ObjectNode mediaOut = node.putObject("media");
            mediaOut.put("kind", kind == null ? "IMAGE" : kind.name());
            putNullable(mediaOut, "assetId", assetId);
            putNullable(mediaOut, "posterAssetId", poster);
            if (assetId != null) {
                ctx.asset(
                        assetId,
                        MediaRole.SLIDE,
                        position,
                        path + ".media.assetId",
                        poster,
                        kind == null ? "IMAGE" : kind.name(),
                        0,
                        720);
            }
            Integer duration = slide.integer("durationSeconds", 3, 15, 5);
            node.put("durationSeconds", duration == null ? 5 : duration);

            ObjectNode captions = node.putObject("texts");
            for (Map.Entry<String, JsonNode> entry : slide.localeMap("texts").entrySet()) {
                ObjectReader text = new ObjectReader(entry.getValue(), path + ".texts." + entry.getKey(), ctx.problems);
                String caption = text.string("caption", 140, false, false);
                text.finish();
                if (caption != null) {
                    captions.putObject(entry.getKey()).put("caption", caption);
                }
            }
            node.set("link", link(slide, "link", ctx));
            slide.finish();
        }
        doc.finish();
        return out;
    }

    // ===================================================================== POPUP

    private static ObjectNode popup(@Nullable JsonNode raw, DocumentContext ctx) {
        ObjectReader doc = new ObjectReader(raw, "", ctx.problems);
        ObjectNode out = start(doc);

        ObjectNode texts = out.putObject("texts");
        for (Map.Entry<String, JsonNode> entry : doc.localeMap("texts").entrySet()) {
            ObjectReader text = new ObjectReader(entry.getValue(), "texts." + entry.getKey(), ctx.problems);
            String title = text.string("title", 80, true, false);
            String body = text.string("body", 240, false, true);
            String ctaLabel = text.string("ctaLabel", 24, false, false);
            String alt = text.string("alt", 140, false, false);
            text.finish();
            if (title == null) {
                continue;
            }
            ctx.locale(entry.getKey());
            ObjectNode node = texts.putObject(entry.getKey());
            node.put("title", title);
            putNullable(node, "body", body);
            putNullable(node, "ctaLabel", ctaLabel);
            putNullable(node, "alt", alt);
        }
        ObjectReader media = doc.object("media", false);
        UUID primary = media.uuid("primaryAssetId", false);
        UUID poster = media.uuid("posterAssetId", false);
        media.finish();
        ObjectNode mediaOut = out.putObject("media");
        putNullable(mediaOut, "primaryAssetId", primary);
        putNullable(mediaOut, "posterAssetId", poster);
        if (primary != null) {
            ctx.asset(primary, MediaRole.PRIMARY, 0, "media.primaryAssetId", poster, null, CARD_RATIO, 600);
        }
        out.set("link", link(doc, "link", ctx));

        Integer after = doc.integer("showAfterSeconds", 3, 60, 3);
        out.put("showAfterSeconds", after == null ? 3 : after);

        JsonNode dailyRaw = doc.child("daily");
        if (dailyRaw == null) {
            out.putNull("daily");
        } else {
            ObjectReader daily = new ObjectReader(dailyRaw, "daily", ctx.problems);
            String from = clock(daily, "from");
            String until = clock(daily, "until");
            List<JsonNode> rawDays = daily.array("daysOfWeek", 7);
            Set<Integer> days = new java.util.TreeSet<>();
            int index = 0;
            for (JsonNode day : rawDays) {
                if (!day.isIntegralNumber() || !day.canConvertToInt() || day.intValue() < 1 || day.intValue() > 7) {
                    ctx.problems.add(daily.at("daysOfWeek[" + index + "]"), "OUT_OF_RANGE", "1 (Monday) to 7 (Sunday)");
                } else {
                    days.add(day.intValue());
                }
                index++;
            }
            if (from != null && from.equals(until)) {
                ctx.problems.add("daily", "EMPTY_WINDOW", "The window opens and closes at the same time");
            }
            daily.finish();
            ObjectNode dailyOut = out.putObject("daily");
            putNullable(dailyOut, "from", from);
            putNullable(dailyOut, "until", until);
            ArrayNode dayNodes = dailyOut.putArray("daysOfWeek");
            if (days.isEmpty()) {
                for (int d = 1; d <= 7; d++) {
                    dayNodes.add(d);
                }
            } else {
                days.forEach(dayNodes::add);
            }
        }

        ObjectReader cap = doc.object("cap", false);
        CapMode mode = cap.enumeration("mode", CapMode.class, false, CapMode.ONCE_PER_DAY);
        Integer n = cap.integer("n", 1, 30, 1);
        cap.finish();
        ObjectNode capOut = out.putObject("cap");
        capOut.put("mode", mode == null ? CapMode.ONCE_PER_DAY.name() : mode.name());
        capOut.put("n", n == null ? 1 : n);

        String dismiss = doc.string("dismiss", 16, false, false);
        if (dismiss != null && !dismiss.equals("DISMISSIBLE")) {
            ctx.problems.add("dismiss", "UNKNOWN_VALUE", "Only DISMISSIBLE is supported");
        }
        out.put("dismiss", "DISMISSIBLE");
        doc.finish();
        return out;
    }

    private static @Nullable String clock(ObjectReader reader, String key) {
        String value = reader.string(key, 5, true, false);
        if (value != null && !CLOCK.matcher(value).matches()) {
            reader.problem(key, "BAD_TIME", "Expected a time as HH:mm");
            return null;
        }
        return value;
    }

    // ============================================================ EDITORIAL

    private static ObjectNode article(@Nullable JsonNode raw, DocumentContext ctx) {
        ObjectReader doc = new ObjectReader(raw, "", ctx.problems);
        ObjectNode out = start(doc);
        titles(doc, out, ctx);
        UUID cover = doc.uuid("coverAssetId", false);
        putNullable(out, "coverAssetId", cover);
        if (cover != null) {
            ctx.asset(cover, MediaRole.PRIMARY, 0, "coverAssetId", null, "IMAGE", 0, 0);
        }
        bodies(doc, out, ctx);
        doc.finish();
        return out;
    }

    private static ObjectNode album(@Nullable JsonNode raw, DocumentContext ctx) {
        ObjectReader doc = new ObjectReader(raw, "", ctx.problems);
        ObjectNode out = start(doc);
        titles(doc, out, ctx);
        UUID cover = doc.uuid("coverAssetId", false);
        putNullable(out, "coverAssetId", cover);
        if (cover != null) {
            ctx.asset(cover, MediaRole.PRIMARY, 0, "coverAssetId", null, "IMAGE", 0, 0);
        }
        ArrayNode images = out.putArray("albumImages");
        List<JsonNode> rawImages = doc.array("albumImages", 30);
        int index = 0;
        for (JsonNode rawImage : rawImages) {
            String path = doc.at("albumImages[" + index + "]");
            int position = index + 1;
            index++;
            ObjectReader image = new ObjectReader(rawImage, path, ctx.problems);
            UUID asset = image.uuid("assetId", true);
            ObjectNode node = images.addObject();
            putNullable(node, "assetId", asset);
            ObjectNode captions = node.putObject("captions");
            for (Map.Entry<String, JsonNode> entry : image.localeMap("captions").entrySet()) {
                JsonNode value = entry.getValue();
                if (!value.isString()) {
                    ctx.problems.add(path + ".captions." + entry.getKey(), "NOT_A_STRING", "Expected a caption");
                    continue;
                }
                String caption = TextRules.normalize(value.stringValue());
                if (TextRules.length(caption) > 140) {
                    ctx.problems.add(path + ".captions." + entry.getKey(), "TOO_LONG", "At most 140 characters");
                } else if (TextRules.hasForbiddenCharacter(caption, false)) {
                    ctx.problems.add(
                            path + ".captions." + entry.getKey(),
                            "FORBIDDEN_CHARACTER",
                            "Contains a control or direction-override character");
                } else if (!caption.isEmpty()) {
                    captions.put(entry.getKey(), caption);
                }
            }
            image.finish();
            if (asset != null) {
                ctx.asset(asset, MediaRole.ALBUM_IMAGE, position, path + ".assetId", null, "IMAGE", 0, 0);
            }
        }
        doc.finish();
        return out;
    }

    private static ObjectNode vacancy(@Nullable JsonNode raw, DocumentContext ctx) {
        ObjectReader doc = new ObjectReader(raw, "", ctx.problems);
        ObjectNode out = start(doc);
        titles(doc, out, ctx);
        bodies(doc, out, ctx);

        ObjectReader vacancy = doc.object("vacancy", true);
        EmploymentType employment = vacancy.enumeration("employmentType", EmploymentType.class, true, null);
        ObjectReader apply = vacancy.object("apply", true);
        ApplyType type = apply.enumeration("type", ApplyType.class, true, null);
        String value = apply.string("value", 2048, true, false);
        apply.finish();
        vacancy.finish();
        ObjectNode vacancyOut = out.putObject("vacancy");
        vacancyOut.put("employmentType", employment == null ? EmploymentType.FULL_TIME.name() : employment.name());
        ObjectNode applyOut = vacancyOut.putObject("apply");
        applyOut.put("type", type == null ? ApplyType.PHONE.name() : type.name());
        if (type != null && value != null) {
            switch (type) {
                case PHONE -> {
                    int digits = (int) value.chars().filter(Character::isDigit).count();
                    if (!PHONE.matcher(value).matches() || digits < 7 || digits > 15) {
                        ctx.problems.add("vacancy.apply.value", "NOT_A_PHONE", "Expected a phone number");
                    }
                }
                case EMAIL -> {
                    if (!EMAIL.matcher(value).matches()) {
                        ctx.problems.add("vacancy.apply.value", "NOT_AN_EMAIL", "Expected an e-mail address");
                    }
                }
                case TELEGRAM, URL -> {
                    Optional<ExternalUrls.Parsed> parsed = ExternalUrls.parseHttps(value);
                    if (parsed.isEmpty()) {
                        ctx.problems.add("vacancy.apply.value", "NOT_AN_HTTPS_ADDRESS", "Expected an https address");
                    } else if (type == ApplyType.TELEGRAM
                            && !(parsed.get().host().equals("t.me")
                                    || parsed.get().host().equals("telegram.me"))) {
                        ctx.problems.add("vacancy.apply.value", "NOT_A_TELEGRAM_ADDRESS", "Expected a t.me address");
                    } else {
                        ctx.ref("vacancy.apply.value", parsed.get());
                        value = parsed.get().url();
                    }
                }
            }
        }
        putNullable(applyOut, "value", value);
        doc.finish();
        return out;
    }

    private static void titles(ObjectReader doc, ObjectNode out, DocumentContext ctx) {
        ObjectNode texts = out.putObject("texts");
        for (Map.Entry<String, JsonNode> entry : doc.localeMap("texts").entrySet()) {
            ObjectReader text = new ObjectReader(entry.getValue(), "texts." + entry.getKey(), ctx.problems);
            String title = text.string("title", 120, true, false);
            String summary = text.string("summary", 300, false, true);
            text.finish();
            if (title == null) {
                continue;
            }
            ctx.locale(entry.getKey());
            ObjectNode node = texts.putObject(entry.getKey());
            node.put("title", title);
            putNullable(node, "summary", summary);
        }
    }

    private static void bodies(ObjectReader doc, ObjectNode out, DocumentContext ctx) {
        ObjectNode bodies = out.putObject("body");
        for (Map.Entry<String, JsonNode> entry : doc.localeMap("body").entrySet()) {
            BlockDocuments.read(entry.getValue(), "body." + entry.getKey(), ctx)
                    .ifPresent(block -> bodies.set(entry.getKey(), block));
        }
    }

    // ================================================================= shared

    private static ObjectNode start(ObjectReader doc) {
        doc.integer("schemaVersion", 1, 1, 1);
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("schemaVersion", 1);
        return out;
    }

    private static void source(ObjectReader text) {
        String source = text.string("source", 16, false, false);
        if (source != null && !source.equals("AUTHORED")) {
            // The schema admits no other value until the owner picks a provider and a review rule
            // (ADR 0157, Decision 4): no text is machine translated.
            text.enumeration("source", Source.class, false, Source.AUTHORED);
        }
    }

    /** A link: {@code {type, targetId, url}}, all three always present in the stored form. */
    private static ObjectNode link(ObjectReader parent, String key, DocumentContext ctx) {
        JsonNode raw = parent.child(key);
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        if (raw == null) {
            // Optional everywhere: no link is NONE, which is also what a link degrades to.
            return emptyLink();
        }
        ObjectReader link = new ObjectReader(raw, parent.at(key), ctx.problems);
        LinkType type = link.enumeration("type", LinkType.class, false, LinkType.NONE);
        UUID target = link.uuid("targetId", false);
        String url = link.string("url", ExternalUrls.MAX_LENGTH, false, false);
        link.finish();
        LinkType resolved = type == null ? LinkType.NONE : type;
        out.put("type", resolved.name());
        if (resolved.needsTarget()) {
            if (target == null) {
                ctx.problems.add(link.at("targetId"), "REQUIRED", resolved + " names its target");
            } else {
                switch (resolved) {
                    case PRODUCT -> ctx.product(target);
                    case CATEGORY -> ctx.category(target);
                    case PROMOTION -> ctx.promotion(target);
                    default -> {
                        // not a target-carrying type
                    }
                }
            }
        } else if (target != null) {
            ctx.problems.add(link.at("targetId"), "NOT_FOR_TYPE", resolved + " names no target");
        }
        putNullable(out, "targetId", resolved.needsTarget() ? target : null);
        String normalizedUrl = null;
        if (resolved.needsUrl()) {
            if (url == null) {
                ctx.problems.add(link.at("url"), "REQUIRED", "An external link carries an https address");
            } else {
                Optional<ExternalUrls.Parsed> parsed = ExternalUrls.parseHttps(url);
                if (parsed.isEmpty()) {
                    ctx.problems.add(
                            link.at("url"),
                            "URL_NOT_ALLOWED",
                            "An external link is https, carries no credentials and names a host");
                } else {
                    ctx.ref(link.at("url"), parsed.get());
                    normalizedUrl = parsed.get().url();
                }
            }
        } else if (url != null) {
            ctx.problems.add(link.at("url"), "NOT_FOR_TYPE", resolved + " carries no address");
        }
        putNullable(out, "url", normalizedUrl);
        return out;
    }

    private static ObjectNode emptyLink() {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("type", LinkType.NONE.name());
        out.putNull("targetId");
        out.putNull("url");
        return out;
    }

    private static void putNullable(ObjectNode node, String key, @Nullable Object value) {
        if (value == null) {
            node.putNull(key);
        } else {
            node.put(key, value.toString());
        }
    }

    // ---------------------------------------------------------------- enums

    enum Source {
        AUTHORED
    }

    enum GroupMode {
        MANUAL,
        BY_CATEGORY
    }

    enum GroupOrder {
        MENU_ORDER,
        NEWEST
    }

    enum SlideKind {
        IMAGE,
        VIDEO
    }

    enum CapMode {
        ONCE_EVER,
        ONCE_PER_DAY,
        EVERY_N_DAYS
    }

    enum EmploymentType {
        FULL_TIME,
        PART_TIME,
        SHIFT,
        INTERNSHIP
    }

    enum ApplyType {
        PHONE,
        TELEGRAM,
        URL,
        EMAIL
    }

    /** Used by tests and the read model to enumerate group modes without reaching into the grammar. */
    public static List<String> groupModes() {
        List<String> modes = new ArrayList<>();
        for (GroupMode mode : GroupMode.values()) {
            modes.add(mode.name());
        }
        return modes;
    }
}
