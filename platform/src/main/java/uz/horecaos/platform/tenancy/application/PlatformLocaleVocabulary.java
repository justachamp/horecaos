package uz.horecaos.platform.tenancy.application;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.LocaleVocabulary;
import uz.horecaos.platform.tenancy.api.PlatformLocale;
import uz.horecaos.platform.tenancy.api.PlatformLocale.Tier;
import uz.horecaos.platform.tenancy.api.PlatformLocales;

/** Answers identity's {@link LocaleVocabulary} from the registry (ADR 0149): staff interface and messages tiers. */
@Component
public class PlatformLocaleVocabulary implements LocaleVocabulary {

    @Override
    public Optional<String> staffInterface(@Nullable String input) {
        return PlatformLocales.parseActive(input, Tier.STAFF_UI).map(PlatformLocale::tag);
    }

    @Override
    public List<String> staffInterfaceTags() {
        return PlatformLocales.activeTags(Tier.STAFF_UI);
    }

    @Override
    public String message(@Nullable String input) {
        return PlatformLocales.resolve(input, Tier.MESSAGES);
    }
}
