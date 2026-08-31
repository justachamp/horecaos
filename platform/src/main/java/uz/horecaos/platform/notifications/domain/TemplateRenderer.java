package uz.horecaos.platform.notifications.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Substitution, and nothing else (ADR 0020).
 *
 * <p>ADR 0020 rejected a general-purpose template engine with object access,
 * because a template that can walk an object graph is a path from a marketing
 * message to a customer's address. What is left is deliberately weak: a
 * {@code {{name}}} placeholder is replaced by a string from a map whose keys were
 * allowlisted by the version's schema. There is no property access, no expression,
 * no loop, no include, and no way to reach anything the caller did not put in the
 * map.
 *
 * <p>Two rules do the work.
 *
 * <p>Every placeholder must be declared. An undeclared one fails
 * {@link #validate}, which runs when a version is authored — so a typo is a
 * refused draft rather than a message that goes out reading "Заказ {{orderNumbr}}
 * принят".
 *
 * <p>Substitution is single-pass. A value containing {@code {{something}}} is
 * emitted literally rather than resolved again, so a variable whose content came
 * from a customer cannot smuggle another variable into the output. That is the
 * template-injection case ADR 0020's test list names, and a naive loop over
 * {@code String.replace} has it.
 */
public final class TemplateRenderer {

    /**
     * Names only: letters, digits, and underscore, starting with a letter. Nothing
     * that could be read as a path, an index, or a call.
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_]*)\\s*}}");

    private TemplateRenderer() {}

    /**
     * Checks a template against its declared variables.
     *
     * <p>Called at authoring time. Rendering also fails on a missing value, but by
     * then the wording has been approved and a customer is waiting.
     *
     * @throws TemplateContractException when the template names a variable the
     *         schema does not declare
     */
    public static void validate(@Nullable String template, Set<String> declaredVariables) {
        if (template == null) {
            return;
        }
        List<String> undeclared = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!declaredVariables.contains(name) && !undeclared.contains(name)) {
                undeclared.add(name);
            }
        }
        if (!undeclared.isEmpty()) {
            throw new TemplateContractException(
                    "The template uses variables that its schema does not declare: " + undeclared);
        }
    }

    /** Every placeholder the template names, in first-appearance order. */
    public static Set<String> variablesUsedIn(@Nullable String template) {
        Set<String> used = new LinkedHashSet<>();
        if (template == null) {
            return used;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            used.add(matcher.group(1));
        }
        return used;
    }

    /**
     * Renders one template with one set of values.
     *
     * <p>A single pass over the input, appending replacements to the output. The
     * output is never re-scanned, which is what makes a value containing
     * {@code {{...}}} inert.
     *
     * @throws TemplateContractException when a placeholder has no value. Sending
     *         the literal {@code {{orderNumber}}} to a customer is worse than not
     *         sending, and silently emptying it produces a sentence with a hole
     */
    public static @Nullable String render(@Nullable String template, Map<String, String> values) {
        if (template == null) {
            return null;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder();
        int cursor = 0;

        while (matcher.find()) {
            String name = matcher.group(1);
            String value = values.get(name);
            if (value == null) {
                throw new TemplateContractException("No value was supplied for the template variable " + name);
            }
            rendered.append(template, cursor, matcher.start()).append(value);
            cursor = matcher.end();
        }
        rendered.append(template, cursor, template.length());
        return rendered.toString();
    }

    /** A template and its variables disagree. Never reaches a provider. */
    public static class TemplateContractException extends RuntimeException {

        public TemplateContractException(String message) {
            super(message);
        }
    }
}
