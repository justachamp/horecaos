/**
 * What {@code integration} may hold of the assistant: the model provider port
 * with its normalized request and response, and the configuration keys the
 * platform resolves for it. Nothing here names a provider, a model id, or a
 * wire type -- "no provider DTO, model id, or {@code if (provider == X)} reaches
 * core code" (ADR 0069).
 */
@org.springframework.modulith.NamedInterface("api")
package uz.horecaos.platform.assistant.api;
