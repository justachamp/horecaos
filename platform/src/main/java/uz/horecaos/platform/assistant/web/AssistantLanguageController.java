package uz.horecaos.platform.assistant.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.assistant.api.AssistantConfigurationKeys;
import uz.horecaos.platform.assistant.domain.ReplyLocale;
import uz.horecaos.platform.tenancy.api.PlatformLocales;

/**
 * The languages the assistant answers in, as the assistant itself names them (ADR 0069, ADR 0149
 * Decision 3).
 *
 * <p>The assistant's notes and its disclosure sentence are written in the languages its detector has
 * a vocabulary for ({@link ReplyLocale#SUPPORTED}), which are not the platform's registry and are
 * not a tenant's choice: a language beyond them is answered in the brand's default until somebody
 * writes its word lists. The console used to carry that list itself, in two pages, which is the
 * mirror ADR 0149 removes everywhere else. This is the same kind of read as {@code GET
 * /api/v1/operations/locales}: a read of code that names nobody, answered to any signed-in
 * principal, with no capability because there is nothing tenant-owned in it. It sits outside the
 * tenant-scoped assistant routes on purpose: a brand manager, whose grant stops at the brand, reads
 * the notes of their brand and needs the same list to write one, and a tenant-scoped capability
 * would refuse exactly them.
 */
@RestController
@RequestMapping("/api/v1/operations/assistant")
@Tag(name = "Assistant languages", description = "The languages the grounded assistant answers in")
public class AssistantLanguageController {

    @GetMapping("/languages")
    @Operation(
            summary = "The languages the assistant answers in",
            description = "The languages the assistant's detector has a vocabulary for, each with the platform "
                    + "language it is and the configuration key that holds the disclosure wording in it. A note "
                    + "and a disclosure can be written in exactly these. A read of code, so the console offers "
                    + "what the detector knows and not a list of its own.")
    AssistantLanguagesResponse languages() {
        return AssistantLanguagesResponse.current();
    }

    /** The languages the assistant answers in; a read of {@link ReplyLocale#SUPPORTED}. */
    public record AssistantLanguagesResponse(List<AssistantLanguageResponse> languages) {

        static AssistantLanguagesResponse current() {
            return new AssistantLanguagesResponse(ReplyLocale.SUPPORTED.stream()
                    .map(AssistantLanguageResponse::of)
                    .toList());
        }
    }

    /**
     * @param code the assistant's own stored code for the language: an ISO 639 code ({@code ru}, {@code uz},
     *     {@code en}), which is what a note's {@code locale} holds and why a bare {@code uz} is right here
     *     (ADR 0149, Decision 3: not a platform locale)
     * @param locale the platform language it is, as the registry tags it ({@code uz} is {@code uz-Latn}), for a
     *     picker's name of it
     * @param disclosureKey the configuration key that holds the disclosure sentence in this language
     */
    public record AssistantLanguageResponse(String code, String locale, String disclosureKey) {

        static AssistantLanguageResponse of(String code) {
            return new AssistantLanguageResponse(
                    code,
                    PlatformLocales.parse(code).map(entry -> entry.tag()).orElse(code),
                    AssistantConfigurationKeys.disclosureKeyFor(code).code());
        }
    }
}
