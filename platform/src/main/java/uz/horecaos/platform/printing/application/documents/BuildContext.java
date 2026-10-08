package uz.horecaos.platform.printing.application.documents;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.printing.domain.TapeWidth;
import uz.horecaos.platform.printing.render.PrintLabels;

/**
 * What every document is built with, beside the facts it prints (ADR 0154): the one language it is
 * written in, the roll it is for, the branch it names, and the brand's closed receipt profile.
 *
 * @param copy      whether this print is a copy of one the customer may already hold
 * @param logoAssetId the brand's logo, only when the profile asks for it and the brand has one
 * @param footers   the brand's footer lines in this document's language, up to three
 */
public record BuildContext(
        String locale,
        TapeWidth tape,
        String zone,
        boolean copy,
        Branch branch,
        @Nullable String logoAssetId,
        List<String> footers) {

    public BuildContext {
        footers = List.copyOf(footers);
    }

    /** The branch as a paper document names it. */
    public record Branch(
            String name, @Nullable String address, @Nullable String phone) {}

    public PrintLabels labels() {
        return PrintLabels.forLocale(locale);
    }

    /** The footer a profile holds for {@code locale}, or none. */
    public static List<String> footerFor(Map<String, List<String>> footerTexts, String locale) {
        return footerTexts.getOrDefault(locale, List.of());
    }
}
