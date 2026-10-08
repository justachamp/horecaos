package uz.horecaos.platform.printing.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.printing.domain.PrintDocument;

/**
 * A document as canonical JSON, and the digest of it (ADR 0154, Specification).
 *
 * <p>Canonical means the same document is always the same bytes: properties sorted, map entries sorted.
 * The digest is the SHA-256 of that text and is kept after the stored document is purged, so a dead job a
 * month later still says what it was. The retry, delayed and copy lines are render inputs and never appear
 * here, so the digest is the same whether a job printed at once, was retried, or waited behind an offline
 * printer.
 */
@Component
public class PrintDocumentCodec {

    private final ObjectMapper canonical = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public String toJson(PrintDocument document) {
        try {
            return canonical.writeValueAsString(document);
        } catch (JacksonException failure) {
            throw new IllegalStateException("A print document could not be written", failure);
        }
    }

    public PrintDocument fromJson(String json) {
        try {
            return canonical.readValue(json, PrintDocument.class);
        } catch (JacksonException failure) {
            throw new IllegalStateException("A stored print document could not be read", failure);
        }
    }

    /** Lower-case hex SHA-256 of the canonical JSON. */
    public String digest(PrintDocument document) {
        try {
            byte[] hash =
                    MessageDigest.getInstance("SHA-256").digest(toJson(document).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is part of every Java platform", impossible);
        }
    }
}
