package uz.horecaos.platform.integration.provider.voice.asterisk;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The AMI connection details for one installation, read from {@code
 * integration.installations.non_sensitive_config}.
 *
 * <p>Not {@code provider_environments.base_url}: that column is constrained to
 * {@code ^https?://} (V0013's {@code ck_provider_environment_url}), and AMI is
 * a raw TCP protocol, not HTTP. {@code base_url} stays a descriptive
 * placeholder for an Asterisk installation's environment row; the host, port,
 * and username an adapter actually dials live here instead — the same
 * non-sensitive-configuration column every other installation already has for
 * exactly this kind of provider-specific detail.
 */
public record AsteriskConnectionConfig(String host, int port, String username) {

    private static final int DEFAULT_PORT = 5038;

    public static AsteriskConnectionConfig fromJson(ObjectMapper objectMapper, String nonSensitiveConfigJson) {
        var node = objectMapper.readTree(nonSensitiveConfigJson);
        String host = textOrThrow(node, "amiHost");
        int port = node.has("amiPort") ? node.get("amiPort").asInt() : DEFAULT_PORT;
        String username = textOrThrow(node, "amiUsername");
        return new AsteriskConnectionConfig(host, port, username);
    }

    private static String textOrThrow(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).asString().isBlank()) {
            throw new IllegalArgumentException("Asterisk installation config is missing \"" + field + "\"");
        }
        return node.get(field).asString();
    }
}
