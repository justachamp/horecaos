package uz.horecaos.platform.fulfillment.infrastructure.persistence;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.boot.jackson.JacksonMixin;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Action;

/**
 * Names the document's fallback {@code "default"} on the wire (ADR 0142 "The document").
 *
 * <p>{@code default} is a Java keyword, so the record's component is {@code fallback}; the stored JSON, the
 * HTTP body and the ADR's example all say {@code default}. The domain imports no serialisation types, so the
 * rename sits here, at the boundary, and Spring Boot registers it on the application's mapper -- the one the
 * policy resolver, the policy author and the web layer share -- by scanning for {@link JacksonMixin}.
 *
 * <p>A mapper built by hand (a unit test, a codec) must register it itself with {@code
 * addMixIn(DispatchRulesDocument.class, DispatchRulesDocumentMixin.class)}.
 */
@JacksonMixin(DispatchRulesDocument.class)
public abstract class DispatchRulesDocumentMixin {

    @JsonProperty("default")
    public abstract Action fallback();
}
