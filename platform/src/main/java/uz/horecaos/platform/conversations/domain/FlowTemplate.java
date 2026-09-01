package uz.horecaos.platform.conversations.domain;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code {{variable}}} substitution the ADR's own context uses to describe
 * a captured field ("a feedback branch capturing free text into a field"),
 * applied to a block's authored text or URL. Deliberately not a templating
 * engine: one pattern, one lookup, an unresolved placeholder left verbatim
 * rather than thrown on — a document typo should not take down a live flow
 * turn, and the authoring-time validator is where a typo gets caught.
 */
public final class FlowTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*\\}\\}");

    private FlowTemplate() {}

    public static String render(String text, Map<String, String> variables) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String value = variables.get(matcher.group(1));
            matcher.appendReplacement(result, Matcher.quoteReplacement(value == null ? matcher.group(0) : value));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
