package uz.horecaos.platform.content.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.content.domain.ContentDocuments.Validated;
import uz.horecaos.platform.content.domain.DocumentProblems.InvalidDocumentException;

/**
 * The typed documents' grammar (ADR 0157): strict decoding, stated defaults, canonical languages,
 * and what each document refers to outside itself.
 *
 * <p>Bodies are the JSON the console sends -- only the keys it fills in -- because a missing optional
 * value must never be an error (Jackson 3 refuses a missing primitive with a 400, a defect this
 * platform has met twice).
 */
class ContentDocumentsTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID ASSET = UUID.fromString("018f9b20-4000-7000-8000-0000000000a1");
    private static final UUID OTHER_ASSET = UUID.fromString("018f9b20-4000-7000-8000-0000000000a2");
    private static final UUID CATEGORY = UUID.fromString("018f9b20-4000-7000-8000-0000000000b1");
    private static final UUID PRODUCT = UUID.fromString("018f9b20-4000-7000-8000-0000000000b2");
    private static final UUID PROMOTION = UUID.fromString("018f9b20-4000-7000-8000-0000000000b3");

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    private static Validated ok(ContentKind kind, String text) {
        return ContentDocuments.validate(kind, json(text));
    }

    private static List<FieldProblem> refused(ContentKind kind, String text) {
        InvalidDocumentException failure =
                org.junit.jupiter.api.Assertions.assertThrows(InvalidDocumentException.class, () -> ok(kind, text));
        return failure.problems();
    }

    private static void assertRefusedWith(ContentKind kind, String text, String field, String code) {
        assertThat(refused(kind, text))
                .as("%s should be refused at %s with %s", kind, field, code)
                .anySatisfy(problem -> {
                    assertThat(problem.field()).isEqualTo(field);
                    assertThat(problem.code()).isEqualTo(code);
                });
    }

    private static final String BANNER_MINIMAL =
            "{\"texts\":{\"ru\":{\"title\":\"Скидка\"}},\"media\":{\"primaryAssetId\":\"" + ASSET + "\"}}";

    // ===================================================== omitted optional keys

    @Test
    @DisplayName("a banner with every optional key omitted is accepted and takes the stated defaults")
    void everyOptionalKeyMayBeOmitted() {
        Validated banner = ok(ContentKind.BANNER, BANNER_MINIMAL);

        JsonNode document = banner.document();
        assertThat(document.get("schemaVersion").intValue()).isEqualTo(1);
        JsonNode ru = document.get("texts").get("ru");
        assertThat(ru.get("title").stringValue()).isEqualTo("Скидка");
        assertThat(ru.get("subtitle").isNull()).isTrue();
        assertThat(ru.get("ctaLabel").isNull()).isTrue();
        assertThat(ru.get("source").stringValue()).isEqualTo("AUTHORED");
        assertThat(document.get("link").get("type").stringValue()).isEqualTo("NONE");
        assertThat(document.get("link").get("targetId").isNull()).isTrue();
        assertThat(banner.facts().locales()).containsExactly("ru");
    }

    @Test
    @DisplayName(
            "a pop-up and a story with every optional key omitted are accepted, with defaults for the caps and the timing")
    void popupAndStoryDefaults() {
        Validated popup = ok(ContentKind.POPUP, "{\"texts\":{\"en\":{\"title\":\"Hello\"}}}");
        assertThat(popup.document().get("showAfterSeconds").intValue()).isEqualTo(3);
        assertThat(popup.document().get("cap").get("mode").stringValue()).isEqualTo("ONCE_PER_DAY");
        assertThat(popup.document().get("cap").get("n").intValue()).isEqualTo(1);
        assertThat(popup.document().get("dismiss").stringValue()).isEqualTo("DISMISSIBLE");
        assertThat(popup.document().get("daily").isNull()).isTrue();

        Validated story = ok(
                ContentKind.STORY_GROUP,
                "{\"texts\":{\"en\":{\"title\":\"New\"}},\"slides\":[{\"media\":{\"assetId\":\"" + ASSET + "\"}}]}");
        JsonNode slide = story.document().get("slides").get(0);
        assertThat(slide.get("position").intValue()).isEqualTo(1);
        assertThat(slide.get("durationSeconds").intValue()).isEqualTo(5);
        assertThat(slide.get("media").get("kind").stringValue()).isEqualTo("IMAGE");
    }

    // ================================================================ strictness

    @Test
    @DisplayName("an unknown key is refused at every level, by its path")
    void unknownKeysAreRefused() {
        assertRefusedWith(ContentKind.BANNER, "{\"texts\":{},\"surprise\":1}", "surprise", "UNKNOWN_KEY");
        assertRefusedWith(
                ContentKind.BANNER,
                "{\"texts\":{\"ru\":{\"title\":\"x\",\"html\":\"<b>x</b>\"}}}",
                "texts.ru.html",
                "UNKNOWN_KEY");
        assertRefusedWith(
                ContentKind.BANNER, "{\"media\":{\"url\":\"https://x.example/a.jpg\"}}", "media.url", "UNKNOWN_KEY");
        assertRefusedWith(
                ContentKind.BANNER, "{\"link\":{\"type\":\"CART\",\"onclick\":\"x\"}}", "link.onclick", "UNKNOWN_KEY");
    }

    @Test
    @DisplayName(
            "a source other than AUTHORED is refused: no text is machine translated until the owner picks a provider")
    void onlyAuthoredTextExists() {
        assertThat(refused(ContentKind.BANNER, "{\"texts\":{\"ru\":{\"title\":\"x\",\"source\":\"MACHINE\"}}}"))
                .anySatisfy(problem -> assertThat(problem.field()).isEqualTo("texts.ru.source"));
        ok(ContentKind.BANNER, "{\"texts\":{\"ru\":{\"title\":\"x\",\"source\":\"AUTHORED\"}}}");
    }

    @Test
    @DisplayName("a document that is not an object, or a field of the wrong type, is refused rather than coerced")
    void wrongShapesAreRefused() {
        assertThat(refused(ContentKind.BANNER, "[]"))
                .anySatisfy(p -> assertThat(p.code()).isEqualTo("NOT_AN_OBJECT"));
        assertRefusedWith(ContentKind.BANNER, "{\"texts\":{\"ru\":{\"title\":5}}}", "texts.ru.title", "NOT_A_STRING");
        assertRefusedWith(ContentKind.BANNER, "{\"texts\":[]}", "texts", "NOT_AN_OBJECT");
        assertRefusedWith(
                ContentKind.BANNER, "{\"media\":{\"primaryAssetId\":\"nope\"}}", "media.primaryAssetId", "NOT_A_UUID");
        assertRefusedWith(ContentKind.POPUP, "{\"showAfterSeconds\":3.5}", "showAfterSeconds", "NOT_AN_INTEGER");
        assertRefusedWith(ContentKind.POPUP, "{\"showAfterSeconds\":\"3\"}", "showAfterSeconds", "NOT_AN_INTEGER");
        assertRefusedWith(ContentKind.POPUP, "{\"showAfterSeconds\":2}", "showAfterSeconds", "OUT_OF_RANGE");
        assertRefusedWith(ContentKind.POPUP, "{\"showAfterSeconds\":61}", "showAfterSeconds", "OUT_OF_RANGE");
        assertRefusedWith(ContentKind.BANNER, "{\"schemaVersion\":2}", "schemaVersion", "OUT_OF_RANGE");
    }

    // ================================================================ languages

    @Test
    @DisplayName(
            "a bare uz is read as uz-Latn and never stored; two spellings of one language are refused; a language the platform does not speak is refused")
    void languagesAreCanonical() {
        Validated uz = ok(ContentKind.BANNER, "{\"texts\":{\"uz\":{\"title\":\"Chegirma\"}}}");
        assertThat(uz.document().get("texts").propertyNames()).containsExactly("uz-Latn");
        assertThat(uz.facts().locales()).containsExactly("uz-Latn");

        assertThat(refused(ContentKind.BANNER, "{\"texts\":{\"uz\":{\"title\":\"a\"},\"uz-Latn\":{\"title\":\"b\"}}}"))
                .anySatisfy(problem -> assertThat(problem.code()).isEqualTo("DUPLICATE_LOCALE"));
        assertRefusedWith(ContentKind.BANNER, "{\"texts\":{\"fr\":{\"title\":\"a\"}}}", "texts.fr", "UNKNOWN_LOCALE");
        // Declared in the registry and live in no tier: it costs nothing and is not accepted.
        assertRefusedWith(ContentKind.BANNER, "{\"texts\":{\"kk\":{\"title\":\"a\"}}}", "texts.kk", "UNKNOWN_LOCALE");
    }

    // ================================================================== text

    @Test
    @DisplayName("lengths are counted in characters an author sees: 80 is accepted and 81 is not, and an emoji is one")
    void lengthsAreCodePoints() {
        String eighty = "a".repeat(80);
        ok(ContentKind.BANNER, "{\"texts\":{\"en\":{\"title\":\"" + eighty + "\"}}}");
        assertRefusedWith(
                ContentKind.BANNER,
                "{\"texts\":{\"en\":{\"title\":\"" + eighty + "a\"}}}",
                "texts.en.title",
                "TOO_LONG");
        // 80 emoji are 160 UTF-16 units and 80 characters.
        String emoji = new String(Character.toChars(0x1F355)).repeat(80);
        ok(ContentKind.BANNER, "{\"texts\":{\"en\":{\"title\":\"" + emoji + "\"}}}");
        assertRefusedWith(
                ContentKind.BANNER,
                "{\"texts\":{\"en\":{\"subtitle\":\"" + "b".repeat(161) + "\",\"title\":\"t\"}}}",
                "texts.en.subtitle",
                "TOO_LONG");
        assertRefusedWith(
                ContentKind.BANNER,
                "{\"texts\":{\"en\":{\"ctaLabel\":\"" + "c".repeat(25) + "\",\"title\":\"t\"}}}",
                "texts.en.ctaLabel",
                "TOO_LONG");
    }

    @Test
    @DisplayName(
            "a title refuses a line break, a control character and the direction overrides that make one string display as another")
    void invisibleCharactersAreRefused() {
        String rightToLeftOverride = new String(Character.toChars(0x202E));
        String isolate = new String(Character.toChars(0x2067));
        String lineSeparator = new String(Character.toChars(0x2028));
        assertRefusedWith(
                ContentKind.BANNER,
                "{\"texts\":{\"en\":{\"title\":\"a\\nb\"}}}",
                "texts.en.title",
                "FORBIDDEN_CHARACTER");
        assertRefusedWith(
                ContentKind.BANNER,
                "{\"texts\":{\"en\":{\"title\":\"a\\u0000b\"}}}",
                "texts.en.title",
                "FORBIDDEN_CHARACTER");
        assertRefusedWith(
                ContentKind.BANNER,
                "{\"texts\":{\"en\":{\"title\":\"a" + rightToLeftOverride + "b\"}}}",
                "texts.en.title",
                "FORBIDDEN_CHARACTER");
        assertRefusedWith(
                ContentKind.BANNER,
                "{\"texts\":{\"en\":{\"title\":\"a" + isolate + "b\"}}}",
                "texts.en.title",
                "FORBIDDEN_CHARACTER");
        assertRefusedWith(
                ContentKind.BANNER,
                "{\"texts\":{\"en\":{\"title\":\"a" + lineSeparator + "b\"}}}",
                "texts.en.title",
                "FORBIDDEN_CHARACTER");
    }

    @Test
    @DisplayName("a title is stripped and normalised to NFC, so two ways of typing a word are one word")
    void textIsNormalised() {
        // 'é' as e + combining acute versus the precomposed character.
        Validated decomposed = ok(ContentKind.BANNER, "{\"texts\":{\"en\":{\"title\":\"  caf\\u0065\\u0301  \"}}}");
        Validated composed = ok(ContentKind.BANNER, "{\"texts\":{\"en\":{\"title\":\"caf\\u00e9\"}}}");
        assertThat(decomposed.document()).isEqualTo(composed.document());
    }

    // ================================================================== links

    @Test
    @DisplayName("a link is a closed union: each type names what it needs and nothing it does not")
    void linkTypes() {
        String base = "{\"texts\":{\"en\":{\"title\":\"t\"}},\"link\":";
        assertRefusedWith(ContentKind.BANNER, base + "{\"type\":\"PRODUCT\"}}", "link.targetId", "REQUIRED");
        assertRefusedWith(
                ContentKind.BANNER,
                base + "{\"type\":\"CART\",\"targetId\":\"" + PRODUCT + "\"}}",
                "link.targetId",
                "NOT_FOR_TYPE");
        assertRefusedWith(
                ContentKind.BANNER,
                base + "{\"type\":\"CART\",\"url\":\"https://shop.example.uz\"}}",
                "link.url",
                "NOT_FOR_TYPE");
        assertRefusedWith(
                ContentKind.BANNER,
                base + "{\"type\":\"COLLECTION\",\"targetId\":\"" + PRODUCT + "\"}}",
                "link.type",
                "UNKNOWN_VALUE");
        assertRefusedWith(ContentKind.BANNER, base + "{\"type\":\"EXTERNAL\"}}", "link.url", "REQUIRED");

        Validated product = ok(ContentKind.BANNER, base + "{\"type\":\"PRODUCT\",\"targetId\":\"" + PRODUCT + "\"}}");
        assertThat(product.facts().productIds()).containsExactly(PRODUCT);
        Validated category =
                ok(ContentKind.BANNER, base + "{\"type\":\"CATEGORY\",\"targetId\":\"" + CATEGORY + "\"}}");
        assertThat(category.facts().categoryIds()).containsExactly(CATEGORY);
        Validated promotion =
                ok(ContentKind.PROMO_CARD, base + "{\"type\":\"PROMOTION\",\"targetId\":\"" + PROMOTION + "\"}}");
        assertThat(promotion.facts().promotionIds()).containsExactly(PROMOTION);
        Validated cart = ok(ContentKind.BANNER, base + "{\"type\":\"CART\"}}");
        assertThat(cart.document().get("link").get("type").stringValue()).isEqualTo("CART");
    }

    @Test
    @DisplayName(
            "an external link is https with no credentials and no port but the default, its host lower-cased and handed to the allowlist")
    void externalLinks() {
        String base = "{\"texts\":{\"en\":{\"title\":\"t\"}},\"link\":{\"type\":\"EXTERNAL\",\"url\":\"";
        Validated good = ok(ContentKind.BANNER, base + "https://Shop.Example.UZ/menu?x=1#top\"}}");
        assertThat(good.document().get("link").get("url").stringValue())
                .isEqualTo("https://shop.example.uz/menu?x=1#top");
        assertThat(good.facts().externalRefs()).singleElement().satisfies(ref -> {
            assertThat(ref.host()).isEqualTo("shop.example.uz");
            assertThat(ref.field()).isEqualTo("link.url");
        });
        ok(ContentKind.BANNER, base + "https://shop.example.uz:443/\"}}");

        for (String bad : new String[] {
            "http://shop.example.uz",
            "javascript:alert(1)",
            "data:text/html,hello",
            "//shop.example.uz/x",
            "/relative/path",
            "https://user:secret@shop.example.uz/",
            "https://shop.example.uz:8443/",
            "https://shop.example.uz/ with space",
            "https://localhost/",
            "https://münchen.example/",
            "ftp://shop.example.uz/"
        }) {
            assertRefusedWith(ContentKind.BANNER, base + bad + "\"}}", "link.url", "URL_NOT_ALLOWED");
        }
    }

    // =================================================================== media

    @Test
    @DisplayName(
            "every asset a banner uses comes out as a fact with the ratio and width its placement wants, per language where it differs")
    void bannerAssets() {
        Validated banner = ok(
                ContentKind.BANNER,
                "{\"texts\":{\"ru\":{\"title\":\"a\"},\"uz\":{\"title\":\"b\",\"mediaAssetId\":\"" + OTHER_ASSET
                        + "\"}}," + "\"media\":{\"primaryAssetId\":\"" + ASSET + "\"}}");

        assertThat(banner.facts().assets())
                .extracting(use -> use.assetId() + ":" + use.field())
                .containsExactlyInAnyOrder(
                        OTHER_ASSET + ":texts.uz-Latn.mediaAssetId", ASSET + ":media.primaryAssetId");
        assertThat(banner.facts().assets()).allSatisfy(use -> {
            assertThat(use.role()).isEqualTo(MediaRole.PRIMARY);
            assertThat(use.ratio()).isEqualTo(3.0);
            assertThat(use.minWidthPx()).isEqualTo(960);
        });

        Validated card = ok(ContentKind.PROMO_CARD, BANNER_MINIMAL);
        assertThat(card.facts().assets()).singleElement().satisfies(use -> {
            assertThat(use.ratio()).isEqualTo(1.5);
            assertThat(use.minWidthPx()).isEqualTo(600);
        });
    }

    @Test
    @DisplayName(
            "a poster belongs to a video: it is a fact of its own beside the asset it posters, and alone it is refused")
    void posters() {
        Validated video = ok(
                ContentKind.BANNER,
                "{\"texts\":{\"en\":{\"title\":\"t\"}},\"media\":{\"primaryAssetId\":\"" + ASSET
                        + "\",\"posterAssetId\":\"" + OTHER_ASSET + "\"}}");
        assertThat(video.facts().assets())
                .extracting(use -> use.role() + ":" + use.assetId())
                .containsExactlyInAnyOrder("PRIMARY:" + ASSET, "POSTER:" + OTHER_ASSET);
        assertRefusedWith(
                ContentKind.BANNER,
                "{\"media\":{\"posterAssetId\":\"" + OTHER_ASSET + "\"}}",
                "media.posterAssetId",
                "POSTER_WITHOUT_MEDIA");
    }

    // ================================================================== groups

    @Test
    @DisplayName(
            "a manual group lists its products once each, at most forty; a by-category group names a category, an order and a limit; neither borrows the other's fields")
    void productGroups() {
        Validated manual = ok(
                ContentKind.PRODUCT_GROUP,
                "{\"texts\":{\"ru\":{\"title\":\"Популярное\"}},\"mode\":\"MANUAL\",\"members\":["
                        + "{\"type\":\"PRODUCT\",\"id\":\"" + PRODUCT + "\"},{\"id\":\"" + PRODUCT + "\"}]}");
        assertThat(manual.document().get("members")).hasSize(1);
        assertThat(manual.facts().productIds()).containsExactly(PRODUCT);
        assertThat(manual.document().get("categoryId").isNull()).isTrue();

        Validated byCategory = ok(
                ContentKind.PRODUCT_GROUP,
                "{\"texts\":{\"ru\":{\"title\":\"Пицца\"}},\"mode\":\"BY_CATEGORY\",\"categoryId\":\"" + CATEGORY
                        + "\"}");
        assertThat(byCategory.document().get("order").stringValue()).isEqualTo("MENU_ORDER");
        assertThat(byCategory.document().get("limit").intValue()).isEqualTo(8);
        assertThat(byCategory.facts().categoryIds()).containsExactly(CATEGORY);

        assertRefusedWith(
                ContentKind.PRODUCT_GROUP, "{\"texts\":{},\"mode\":\"BY_CATEGORY\"}", "categoryId", "REQUIRED");
        assertRefusedWith(
                ContentKind.PRODUCT_GROUP,
                "{\"texts\":{},\"mode\":\"BY_CATEGORY\",\"categoryId\":\"" + CATEGORY + "\",\"members\":[{\"id\":\""
                        + PRODUCT + "\"}]}",
                "members",
                "NOT_FOR_MODE");
        assertRefusedWith(
                ContentKind.PRODUCT_GROUP,
                "{\"texts\":{},\"mode\":\"MANUAL\",\"categoryId\":\"" + CATEGORY + "\"}",
                "mode",
                "NOT_FOR_MODE");
        assertRefusedWith(
                ContentKind.PRODUCT_GROUP,
                "{\"texts\":{},\"mode\":\"BY_CATEGORY\",\"categoryId\":\"" + CATEGORY + "\",\"limit\":41}",
                "limit",
                "OUT_OF_RANGE");
        assertRefusedWith(ContentKind.PRODUCT_GROUP, "{\"texts\":{}}", "mode", "REQUIRED");

        StringBuilder many = new StringBuilder("{\"texts\":{},\"mode\":\"MANUAL\",\"members\":[");
        for (int i = 0; i < 41; i++) {
            many.append(i == 0 ? "" : ",")
                    .append("{\"id\":\"")
                    .append(UUID.randomUUID())
                    .append("\"}");
        }
        many.append("]}");
        assertRefusedWith(ContentKind.PRODUCT_GROUP, many.toString(), "members", "TOO_MANY");
    }

    // =================================================================== stories

    @Test
    @DisplayName(
            "a story has at most ten slides of 3 to 15 seconds, numbered by their place in the list however the author numbered them")
    void stories() {
        Validated story = ok(
                ContentKind.STORY_GROUP,
                "{\"texts\":{\"en\":{\"title\":\"s\"}},\"avatarAssetId\":\"" + OTHER_ASSET + "\",\"slides\":["
                        + "{\"position\":9,\"media\":{\"kind\":\"VIDEO\",\"assetId\":\"" + ASSET
                        + "\",\"posterAssetId\":\"" + PRODUCT + "\"},"
                        + "\"durationSeconds\":15,\"texts\":{\"en\":{\"caption\":\"c\"}},\"link\":{\"type\":\"CART\"}},"
                        + "{\"media\":{\"assetId\":\"" + OTHER_ASSET + "\"}}]}");
        assertThat(story.document().get("slides").get(0).get("position").intValue())
                .isEqualTo(1);
        assertThat(story.document().get("slides").get(1).get("position").intValue())
                .isEqualTo(2);
        assertThat(story.facts().assets())
                .anySatisfy(use -> {
                    assertThat(use.role()).isEqualTo(MediaRole.SLIDE);
                    assertThat(use.declaredKind()).isEqualTo("VIDEO");
                    assertThat(use.posterAssetId()).isEqualTo(PRODUCT);
                    assertThat(use.minWidthPx()).isEqualTo(720);
                })
                .anySatisfy(use -> {
                    assertThat(use.role()).isEqualTo(MediaRole.AVATAR);
                    assertThat(use.ratio()).isEqualTo(1.0);
                });

        assertRefusedWith(
                ContentKind.STORY_GROUP,
                "{\"slides\":[{\"media\":{\"assetId\":\"" + ASSET + "\"},\"durationSeconds\":16}]}",
                "slides[0].durationSeconds",
                "OUT_OF_RANGE");
        assertRefusedWith(
                ContentKind.STORY_GROUP,
                "{\"slides\":[{\"media\":{\"assetId\":\"" + ASSET + "\"},\"durationSeconds\":2}]}",
                "slides[0].durationSeconds",
                "OUT_OF_RANGE");
        StringBuilder eleven = new StringBuilder("{\"slides\":[");
        for (int i = 0; i < 11; i++) {
            eleven.append(i == 0 ? "" : ",")
                    .append("{\"media\":{\"assetId\":\"")
                    .append(ASSET)
                    .append("\"}}");
        }
        eleven.append("]}");
        assertRefusedWith(ContentKind.STORY_GROUP, eleven.toString(), "slides", "TOO_MANY");
        assertRefusedWith(ContentKind.STORY_GROUP, "{\"slides\":[{}]}", "slides[0].media", "REQUIRED");
    }

    // =================================================================== popups

    @Test
    @DisplayName(
            "a pop-up's daily window is HH:mm to HH:mm on days 1 to 7, and its cap is one of three modes of 1 to 30")
    void popups() {
        Validated popup = ok(
                ContentKind.POPUP,
                "{\"texts\":{\"en\":{\"title\":\"t\",\"body\":\"line one\\nline two\"}},"
                        + "\"daily\":{\"from\":\"09:00\",\"until\":\"21:30\",\"daysOfWeek\":[7,1,1,3]},"
                        + "\"cap\":{\"mode\":\"EVERY_N_DAYS\",\"n\":7},\"showAfterSeconds\":10}");
        assertThat(popup.document().get("daily").get("daysOfWeek"))
                .extracting(JsonNode::intValue)
                .containsExactly(1, 3, 7);
        assertThat(popup.document().get("cap").get("n").intValue()).isEqualTo(7);
        assertThat(popup.document().get("texts").get("en").get("body").stringValue())
                .contains("\n");

        Validated everyDay = ok(ContentKind.POPUP, "{\"daily\":{\"from\":\"09:00\",\"until\":\"10:00\"}}");
        assertThat(everyDay.document().get("daily").get("daysOfWeek")).hasSize(7);

        assertRefusedWith(
                ContentKind.POPUP, "{\"daily\":{\"from\":\"9:00\",\"until\":\"10:00\"}}", "daily.from", "BAD_TIME");
        assertRefusedWith(
                ContentKind.POPUP, "{\"daily\":{\"from\":\"09:00\",\"until\":\"24:00\"}}", "daily.until", "BAD_TIME");
        assertRefusedWith(
                ContentKind.POPUP, "{\"daily\":{\"from\":\"09:00\",\"until\":\"09:00\"}}", "daily", "EMPTY_WINDOW");
        assertRefusedWith(
                ContentKind.POPUP,
                "{\"daily\":{\"from\":\"09:00\",\"until\":\"10:00\",\"daysOfWeek\":[8]}}",
                "daily.daysOfWeek[0]",
                "OUT_OF_RANGE");
        assertRefusedWith(ContentKind.POPUP, "{\"cap\":{\"mode\":\"ALWAYS\"}}", "cap.mode", "UNKNOWN_VALUE");
        assertRefusedWith(ContentKind.POPUP, "{\"cap\":{\"n\":31}}", "cap.n", "OUT_OF_RANGE");
        assertRefusedWith(ContentKind.POPUP, "{\"dismiss\":\"STICKY\"}", "dismiss", "UNKNOWN_VALUE");
    }

    // ================================================================ editorial

    @Test
    @DisplayName(
            "a vacancy carries one outbound contact and nothing of a candidate: a phone, a t.me or https address, or an e-mail")
    void vacancies() {
        String texts = "\"texts\":{\"ru\":{\"title\":\"Повар\"}},";
        ok(
                ContentKind.VACANCY,
                "{" + texts
                        + "\"vacancy\":{\"employmentType\":\"SHIFT\",\"apply\":{\"type\":\"PHONE\",\"value\":\"+998 90 123 45 67\"}}}");
        ok(
                ContentKind.VACANCY,
                "{" + texts
                        + "\"vacancy\":{\"employmentType\":\"FULL_TIME\",\"apply\":{\"type\":\"EMAIL\",\"value\":\"jobs@shop.example.uz\"}}}");
        Validated telegram = ok(
                ContentKind.VACANCY,
                "{" + texts
                        + "\"vacancy\":{\"employmentType\":\"PART_TIME\",\"apply\":{\"type\":\"TELEGRAM\",\"value\":\"https://T.me/shopjobs\"}}}");
        assertThat(telegram.facts().externalRefs())
                .singleElement()
                .satisfies(ref -> assertThat(ref.host()).isEqualTo("t.me"));

        assertRefusedWith(
                ContentKind.VACANCY,
                "{" + texts
                        + "\"vacancy\":{\"employmentType\":\"SHIFT\",\"apply\":{\"type\":\"PHONE\",\"value\":\"call me\"}}}",
                "vacancy.apply.value",
                "NOT_A_PHONE");
        assertRefusedWith(
                ContentKind.VACANCY,
                "{" + texts
                        + "\"vacancy\":{\"employmentType\":\"SHIFT\",\"apply\":{\"type\":\"EMAIL\",\"value\":\"nope\"}}}",
                "vacancy.apply.value",
                "NOT_AN_EMAIL");
        assertRefusedWith(
                ContentKind.VACANCY,
                "{" + texts
                        + "\"vacancy\":{\"employmentType\":\"SHIFT\",\"apply\":{\"type\":\"TELEGRAM\",\"value\":\"https://example.com/x\"}}}",
                "vacancy.apply.value",
                "NOT_A_TELEGRAM_ADDRESS");
        assertRefusedWith(
                ContentKind.VACANCY,
                "{" + texts
                        + "\"vacancy\":{\"employmentType\":\"SHIFT\",\"apply\":{\"type\":\"URL\",\"value\":\"http://example.com/x\"}}}",
                "vacancy.apply.value",
                "NOT_AN_HTTPS_ADDRESS");
        // The platform collects no application: there is no field in which a candidate could be named.
        assertRefusedWith(
                ContentKind.VACANCY,
                "{" + texts
                        + "\"vacancy\":{\"employmentType\":\"SHIFT\",\"apply\":{\"type\":\"PHONE\",\"value\":\"+998901234567\"},\"candidateName\":\"x\"}}",
                "vacancy.candidateName",
                "UNKNOWN_KEY");
        assertRefusedWith(
                ContentKind.VACANCY,
                "{" + texts
                        + "\"vacancy\":{\"employmentType\":\"FULL\",\"apply\":{\"type\":\"PHONE\",\"value\":\"+998901234567\"}}}",
                "vacancy.employmentType",
                "UNKNOWN_VALUE");
        assertRefusedWith(ContentKind.VACANCY, "{" + texts + "\"body\":{}}", "vacancy", "REQUIRED");
    }

    @Test
    @DisplayName("a gallery album holds at most thirty images, each once an asset with captions per language")
    void albums() {
        Validated album = ok(
                ContentKind.GALLERY_ALBUM,
                "{\"texts\":{\"en\":{\"title\":\"Hall\"}},\"coverAssetId\":\"" + ASSET + "\",\"albumImages\":["
                        + "{\"assetId\":\"" + OTHER_ASSET + "\",\"captions\":{\"en\":\"Summer\",\"uz\":\"Yoz\"}}]}");
        assertThat(album.facts().assets())
                .extracting(DocumentFacts.AssetUse::role)
                .containsExactlyInAnyOrder(MediaRole.PRIMARY, MediaRole.ALBUM_IMAGE);
        assertThat(album.document().get("albumImages").get(0).get("captions").propertyNames())
                .containsExactlyInAnyOrder("en", "uz-Latn");

        StringBuilder thirtyOne = new StringBuilder("{\"albumImages\":[");
        for (int i = 0; i < 31; i++) {
            thirtyOne
                    .append(i == 0 ? "" : ",")
                    .append("{\"assetId\":\"")
                    .append(UUID.randomUUID())
                    .append("\"}");
        }
        thirtyOne.append("]}");
        assertRefusedWith(ContentKind.GALLERY_ALBUM, thirtyOne.toString(), "albumImages", "TOO_MANY");
    }

    // ================================================================ stability

    @Test
    @DisplayName(
            "what the grammar stores is a fixed point: validating a stored document gives the same document, for every kind")
    void storedDocumentsRevalidateToThemselves() {
        String link = ",\"link\":{\"type\":\"EXTERNAL\",\"url\":\"https://shop.example.uz/a\"}";
        List<Object[]> cases = List.of(
                new Object[] {
                    ContentKind.BANNER,
                    "{\"texts\":{\"ru\":{\"title\":\"a\",\"alt\":\"b\"}},\"media\":{\"primaryAssetId\":\"" + ASSET
                            + "\"}" + link + "}"
                },
                new Object[] {ContentKind.PROMO_CARD, BANNER_MINIMAL},
                new Object[] {
                    ContentKind.PRODUCT_GROUP,
                    "{\"texts\":{\"ru\":{\"title\":\"a\"}},\"mode\":\"MANUAL\",\"members\":[{\"id\":\"" + PRODUCT
                            + "\"}]}"
                },
                new Object[] {
                    ContentKind.PRODUCT_GROUP,
                    "{\"texts\":{\"ru\":{\"title\":\"a\"}},\"mode\":\"BY_CATEGORY\",\"categoryId\":\"" + CATEGORY
                            + "\",\"order\":\"NEWEST\",\"limit\":5}"
                },
                new Object[] {
                    ContentKind.STORY_GROUP,
                    "{\"texts\":{\"en\":{\"title\":\"s\"}},\"slides\":[{\"media\":{\"assetId\":\"" + ASSET
                            + "\"},\"texts\":{\"en\":{\"caption\":\"c\"}}}]}"
                },
                new Object[] {
                    ContentKind.POPUP,
                    "{\"texts\":{\"en\":{\"title\":\"t\",\"body\":\"b\"}},\"daily\":{\"from\":\"09:00\",\"until\":\"10:00\"}}"
                },
                new Object[] {
                    ContentKind.NEWS,
                    "{\"texts\":{\"en\":{\"title\":\"t\"}},\"body\":{\"en\":{\"blocks\":[{\"type\":\"PARAGRAPH\",\"inlines\":[{\"text\":\"x\",\"bold\":true,\"href\":\"https://shop.example.uz/\"}]},{\"type\":\"LIST\",\"items\":[[{\"text\":\"i\"}]]},{\"type\":\"IMAGE\",\"assetId\":\""
                            + ASSET + "\"},{\"type\":\"DIVIDER\"}]}}}"
                },
                new Object[] {
                    ContentKind.GALLERY_ALBUM,
                    "{\"texts\":{\"en\":{\"title\":\"t\"}},\"albumImages\":[{\"assetId\":\"" + ASSET
                            + "\",\"captions\":{\"en\":\"c\"}}]}"
                },
                new Object[] {
                    ContentKind.VACANCY,
                    "{\"texts\":{\"en\":{\"title\":\"t\"}},\"vacancy\":{\"employmentType\":\"SHIFT\",\"apply\":{\"type\":\"PHONE\",\"value\":\"+998901234567\"}}}"
                });

        for (Object[] each : cases) {
            ContentKind kind = (ContentKind) each[0];
            Validated first = ok(kind, (String) each[1]);
            Validated second = ContentDocuments.validate(kind, first.document());
            assertThat(second.document()).as("%s", kind).isEqualTo(first.document());
            assertThat(CanonicalJson.write(second.document())).isEqualTo(CanonicalJson.write(first.document()));
        }
    }

    @Test
    @DisplayName("a document larger than 256 KiB is refused with a code, before it reaches the database check")
    void documentsAreBounded() {
        // Three languages of 50,000 two-byte characters each is 300,000 bytes: every limit the grammar
        // has is respected and the document is still too big to store.
        String run = "ж".repeat(5_000);
        StringBuilder runs = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            runs.append(i == 0 ? "" : ",").append("{\"text\":\"").append(run).append("\"}");
        }
        String locale = "{\"blocks\":[{\"type\":\"PARAGRAPH\",\"inlines\":[" + runs + "]}]}";
        String document = "{\"texts\":{\"ru\":{\"title\":\"t\"}},\"body\":{\"ru\":" + locale + ",\"uz\":" + locale
                + ",\"en\":" + locale + "}}";

        assertThat(refused(ContentKind.NEWS, document)).singleElement().satisfies(problem -> {
            assertThat(problem.field()).isEmpty();
            assertThat(problem.code()).isEqualTo("TOO_LARGE");
        });
        assertThat(ContentDocuments.MAX_BYTES).isEqualTo(262_144);
    }
}
