package uz.horecaos.platform.assistant.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import uz.horecaos.platform.assistant.api.AssistantModelResponse;
import uz.horecaos.platform.assistant.api.RetrievedFact;

/**
 * Proving, after the fact, that a reply says only what its facts say (ADR 0069:
 * "a stated price must be a price the platform would actually charge on that
 * channel at that location ... an invented price is worse than no answer, and
 * this is the decision that keeps it out").
 *
 * <p>The posture asks the model to copy and to cite. This class is why that is
 * not taken on trust. A reply is sendable only if every one of these holds:
 *
 * <ol>
 *   <li>the model did not itself refuse;
 *   <li>it cites at least one fact, and every id it cites is a fact this turn
 *       retrieved -- a reply composed from nothing, or from a fact belonging to no
 *       one, is memory, not grounding;
 *   <li>every figure of two or more digits in the reply appears in a <em>cited</em>
 *       fact, and every amount of money in it -- a figure of four digits or more,
 *       or one next to a currency word -- appears in a cited fact exactly. A model
 *       that "rounds 45 000 to 45 500", or states a price for a dish whose fact it
 *       did not cite, fails here, whatever its prose sounds like.
 * </ol>
 *
 * <p>Single digits are free: "1 portion" and "9:00" are prose, and a price is
 * never one digit. Figures are compared as digits with grouping spaces removed
 * and leading zeros dropped, so "45 000" and "45000" are one number and "09:00"
 * and "9:00" are one time.
 */
public final class GroundingVerifier {

    /** Digits, optionally grouped in threes by a space, a no-break space or a narrow no-break space. */
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[ \\u00A0\\u202F]\\d{3})*");

    private static final Pattern CURRENCY_WORD = Pattern.compile(
            "(?iu)^\\s*(?:сум|сумов|сума|so['\\u2018\\u2019\\u02BB`]?m|som|sum|uzs|usd|\\$|руб|₽|eur|€)");

    private static final Pattern CURRENCY_BEFORE = Pattern.compile("(?iu)(?:uzs|usd|\\$|€|₽)\\s*$");

    private GroundingVerifier() {}

    public static GroundingVerdict verify(
            AssistantModelResponse response, List<RetrievedFact> facts, int maxReplyCharacters) {
        if (response.refusal()) {
            return GroundingVerdict.refused(RefusalReason.MODEL_REFUSED);
        }
        String reply = response.reply();
        if (reply.isBlank() || reply.length() > maxReplyCharacters) {
            return GroundingVerdict.refused(RefusalReason.UNGROUNDED_REPLY);
        }
        if (response.citations().isEmpty()) {
            return GroundingVerdict.refused(RefusalReason.UNGROUNDED_REPLY);
        }

        Map<String, RetrievedFact> byId = new java.util.HashMap<>();
        for (RetrievedFact fact : facts) {
            byId.put(fact.id(), fact);
        }
        Set<String> cited = new HashSet<>();
        Set<String> allowed = new HashSet<>();
        for (String id : response.citations()) {
            RetrievedFact fact = byId.get(id);
            if (fact == null) {
                return GroundingVerdict.refused(RefusalReason.UNGROUNDED_REPLY);
            }
            cited.add(id);
            for (String value : fact.attributes().values()) {
                allowed.addAll(figuresIn(value));
            }
        }

        Matcher matcher = NUMBER.matcher(reply);
        while (matcher.find()) {
            String figure = canonical(matcher.group());
            boolean money = figure.length() >= 4
                    || CURRENCY_WORD.matcher(reply.substring(matcher.end())).find()
                    || CURRENCY_BEFORE
                            .matcher(reply.substring(0, matcher.start()))
                            .find();
            boolean twoOrMoreDigits = figure.length() >= 2;
            if ((money || twoOrMoreDigits) && !allowed.contains(figure)) {
                return GroundingVerdict.refused(RefusalReason.UNGROUNDED_REPLY);
            }
        }
        return GroundingVerdict.ok(cited);
    }

    /** Every figure in a fact value, canonicalised. */
    static Set<String> figuresIn(String text) {
        Set<String> figures = new HashSet<>();
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            figures.add(canonical(matcher.group()));
        }
        return figures;
    }

    private static String canonical(String figure) {
        String digits = figure.replaceAll("[ \\u00A0\\u202F]", "");
        String stripped = digits.replaceFirst("^0+(?=\\d)", "");
        return stripped.toLowerCase(Locale.ROOT);
    }
}
