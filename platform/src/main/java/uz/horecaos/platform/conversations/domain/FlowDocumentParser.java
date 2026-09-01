package uz.horecaos.platform.conversations.domain;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * YAML text to a {@link FlowDocument} — parsing only. {@link
 * FlowDocumentValidator} runs second and checks the whole-document invariants
 * (every transition target exists, no cycle without an exit) that a single
 * state cannot see on its own.
 *
 * <p>Every problem this class finds is collected rather than thrown on first
 * sight, so an author fixing an unknown block type also learns about the
 * missing {@code field} on the state below it in the same submission.
 */
public final class FlowDocumentParser {

    private FlowDocumentParser() {}

    /** @throws FlowDocumentException carrying every problem found, if any */
    public static FlowDocument parse(String yamlText) {
        Object root;
        try {
            root = new Yaml().load(yamlText);
        } catch (YAMLException malformed) {
            throw new FlowDocumentException("The document is not valid YAML: " + malformed.getMessage());
        }
        if (!(root instanceof Map<?, ?> rawRoot)) {
            throw new FlowDocumentException("The document must be a YAML mapping at the top level");
        }
        Map<String, Object> map = asStringKeyedMap(rawRoot);
        List<String> problems = new ArrayList<>();

        String flowKey = requireString(map, "flowKey", problems);
        String name = requireString(map, "name", problems);
        String startState = requireString(map, "startState", problems);

        Map<String, FlowState> states = new LinkedHashMap<>();
        Object rawStates = map.get("states");
        if (!(rawStates instanceof Map<?, ?> statesMap) || statesMap.isEmpty()) {
            problems.add("\"states\" must be a non-empty mapping of state id to block");
        } else {
            for (Map.Entry<?, ?> entry : statesMap.entrySet()) {
                String stateId = String.valueOf(entry.getKey());
                if (!(entry.getValue() instanceof Map<?, ?> rawState)) {
                    problems.add("State \"%s\" must be a mapping".formatted(stateId));
                    continue;
                }
                try {
                    FlowBlock block = parseBlock(stateId, asStringKeyedMap(rawState));
                    states.put(stateId, new FlowState(stateId, block));
                } catch (FlowDocumentException stateProblem) {
                    problems.addAll(stateProblem.problems());
                }
            }
        }

        if (!problems.isEmpty()) {
            throw new FlowDocumentException(problems);
        }
        // Every field above is required, so an empty problems list means
        // none of these three is null — the constructor's own guarantee
        // NullAway cannot see across a collected-problems list.
        return new FlowDocument(
                Objects.requireNonNull(flowKey),
                Objects.requireNonNull(name),
                Objects.requireNonNull(startState),
                Map.copyOf(states));
    }

    private static FlowBlock parseBlock(String stateId, Map<String, Object> block) {
        String type = String.valueOf(block.get("type"));
        return switch (type) {
            case MessageBlock.TYPE -> parseMessage(stateId, block);
            case ButtonsBlock.TYPE -> parseButtons(stateId, block);
            case InputToFieldBlock.TYPE -> parseInputToField(stateId, block);
            case DelayBlock.TYPE -> parseDelay(stateId, block);
            case ConditionBlock.TYPE -> parseCondition(stateId, block);
            case OperatorHandoffBlock.TYPE ->
                new OperatorHandoffBlock(optionalString(block, "message"), optionalString(block, "next"));
            default ->
                throw new FlowDocumentException(
                        "State \"%s\" has an unknown block type \"%s\" — expected one of ".formatted(stateId, type)
                                + "message, buttons, input-to-field, delay, condition, operator-handoff");
        };
    }

    private static MessageBlock parseMessage(String stateId, Map<String, Object> block) {
        List<String> problems = new ArrayList<>();
        String text = requireString(block, "text", problems, stateId);
        failIfAny(problems);
        return new MessageBlock(Objects.requireNonNull(text), optionalString(block, "next"));
    }

    private static ButtonsBlock parseButtons(String stateId, Map<String, Object> block) {
        List<String> problems = new ArrayList<>();
        String text = requireString(block, "text", problems, stateId);
        List<FlowButton> buttons = new ArrayList<>();
        Object rawButtons = block.get("buttons");
        if (!(rawButtons instanceof List<?> list) || list.isEmpty()) {
            problems.add("State \"%s\" (buttons) needs a non-empty \"buttons\" list".formatted(stateId));
        } else {
            int index = 0;
            for (Object rawButton : list) {
                buttons.add(parseButton(stateId, index++, rawButton, problems));
            }
        }
        failIfAny(problems);
        return new ButtonsBlock(Objects.requireNonNull(text), List.copyOf(buttons));
    }

    private static @Nullable FlowButton parseButton(
            String stateId, int index, Object rawButton, List<String> problems) {
        if (!(rawButton instanceof Map<?, ?> rawMap)) {
            problems.add("State \"%s\" button #%d must be a mapping".formatted(stateId, index));
            return null;
        }
        Map<String, Object> buttonMap = asStringKeyedMap(rawMap);
        String where = "State \"%s\" button #%d".formatted(stateId, index);
        String label = requireString(buttonMap, "label", problems, where);
        String kindRaw = requireString(buttonMap, "kind", problems, where);
        if (kindRaw == null) {
            return null;
        }
        FlowButtonKind kind =
                switch (kindRaw.toLowerCase(Locale.ROOT)) {
                    case "url" -> FlowButtonKind.URL;
                    case "callback" -> FlowButtonKind.CALLBACK;
                    default -> null;
                };
        if (kind == null) {
            problems.add("%s has an unknown kind \"%s\" — expected \"url\" or \"callback\"".formatted(where, kindRaw));
            return null;
        }
        if (kind == FlowButtonKind.URL) {
            String url = requireString(buttonMap, "url", problems, where);
            return url == null || label == null ? null : FlowButton.url(label, url);
        }
        String key = requireString(buttonMap, "key", problems, where);
        String next = requireString(buttonMap, "next", problems, where);
        return key == null || next == null || label == null ? null : FlowButton.callback(label, key, next);
    }

    private static InputToFieldBlock parseInputToField(String stateId, Map<String, Object> block) {
        List<String> problems = new ArrayList<>();
        String prompt = requireString(block, "prompt", problems, stateId);
        String field = requireString(block, "field", problems, stateId);
        String next = requireString(block, "next", problems, stateId);
        failIfAny(problems);
        return new InputToFieldBlock(
                Objects.requireNonNull(prompt), Objects.requireNonNull(field), Objects.requireNonNull(next));
    }

    private static DelayBlock parseDelay(String stateId, Map<String, Object> block) {
        List<String> problems = new ArrayList<>();
        String durationRaw = requireString(block, "duration", problems, stateId);
        String next = requireString(block, "next", problems, stateId);
        Duration duration = null;
        if (durationRaw != null) {
            try {
                duration = Duration.parse(durationRaw);
            } catch (DateTimeParseException malformed) {
                problems.add("State \"%s\" (delay) has an unparseable duration \"%s\" — use ISO-8601, e.g. \"PT1H\""
                        .formatted(stateId, durationRaw));
            }
        }
        failIfAny(problems);
        return new DelayBlock(Objects.requireNonNull(duration), Objects.requireNonNull(next));
    }

    private static ConditionBlock parseCondition(String stateId, Map<String, Object> block) {
        List<String> problems = new ArrayList<>();
        String field = requireString(block, "field", problems, stateId);
        String operatorRaw = requireString(block, "operator", problems, stateId);
        String whenTrue = requireString(block, "whenTrue", problems, stateId);
        String whenFalse = requireString(block, "whenFalse", problems, stateId);
        ConditionOperator operator = null;
        if (operatorRaw != null) {
            operator = switch (operatorRaw.toLowerCase(Locale.ROOT)) {
                case "present" -> ConditionOperator.PRESENT;
                case "absent" -> ConditionOperator.ABSENT;
                case "equals" -> ConditionOperator.EQUALS;
                default -> null;
            };
            if (operator == null) {
                problems.add(
                        "State \"%s\" (condition) has an unknown operator \"%s\" — expected present, absent, or equals"
                                .formatted(stateId, operatorRaw));
            }
        }
        String value = optionalString(block, "value");
        if (operator == ConditionOperator.EQUALS && value == null) {
            problems.add("State \"%s\" (condition) uses \"equals\" and needs a \"value\"".formatted(stateId));
        }
        failIfAny(problems);
        return new ConditionBlock(
                Objects.requireNonNull(field),
                Objects.requireNonNull(operator),
                value,
                Objects.requireNonNull(whenTrue),
                Objects.requireNonNull(whenFalse));
    }

    // ------------------------------------------------------------- YAML helpers

    private static @Nullable String requireString(Map<String, Object> map, String key, List<String> problems) {
        return requireString(map, key, problems, null);
    }

    private static @Nullable String requireString(
            Map<String, Object> map, String key, List<String> problems, @Nullable String where) {
        Object value = map.get(key);
        if (value == null) {
            problems.add(
                    where == null ? "\"%s\" is required".formatted(key) : "%s is missing \"%s\"".formatted(where, key));
            return null;
        }
        String text = String.valueOf(value);
        if (text.isBlank()) {
            problems.add(
                    where == null
                            ? "\"%s\" must not be blank".formatted(key)
                            : "%s has a blank \"%s\"".formatted(where, key));
            return null;
        }
        return text;
    }

    private static @Nullable String optionalString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static void failIfAny(List<String> problems) {
        if (!problems.isEmpty()) {
            throw new FlowDocumentException(problems);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringKeyedMap(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }
}
