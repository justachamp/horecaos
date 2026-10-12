package uz.horecaos.platform.fiscal.application;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Which receipt links HorecaOS will show a customer as «the tax authority's page for your receipt»
 * (ADR 0166).
 *
 * <p>A link a provider reports comes from the provider's own API. A link an operator types does not, and
 * the one place an operator types one is the attachment of a fiscal agent's reference, which becomes a
 * document the customer's order page offers as an anchor. Left open, that is a form that lets one person put
 * any address at all in front of a customer under the heading of a legal receipt. So the host has to be the
 * tax authority's: {@code https}, no credentials in the address, the default port, and a host that is the
 * configured domain or a subdomain of it, compared label by label so that {@code ofd.soliq.uz.example.com}
 * and {@code evilsoliq.uz} are not it.
 *
 * <p>The domains are deployment configuration ({@code horecaos.fiscal.receipt-link-host-suffixes},
 * {@code soliq.uz} by default), never a tenant's: a tenant that could widen the list could add its own site
 * to it.
 */
@Component
public class FiscalReceiptLinks {

    private final List<String> hostSuffixes;

    public FiscalReceiptLinks(
            @Value("${horecaos.fiscal.receipt-link-host-suffixes:soliq.uz}") List<String> hostSuffixes) {
        this.hostSuffixes = hostSuffixes.stream()
                .map(suffix -> suffix.strip().toLowerCase(Locale.ROOT))
                .filter(suffix -> !suffix.isEmpty())
                .toList();
    }

    /** Whether the link is an https page of the tax authority's own site. */
    public boolean isTaxAuthorityLink(@Nullable String link) {
        return hostOf(link).map(this::onAllowedDomain).orElse(false);
    }

    /** The host of an https link with no credentials and no unusual port; empty for anything else. */
    public Optional<String> hostOf(@Nullable String link) {
        if (link == null || link.isBlank() || link.chars().anyMatch(Character::isWhitespace)) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = URI.create(link);
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getRawUserInfo() != null
                || uri.getHost() == null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            return Optional.empty();
        }
        return Optional.of(uri.getHost().toLowerCase(Locale.ROOT));
    }

    private boolean onAllowedDomain(String host) {
        return hostSuffixes.stream().anyMatch(suffix -> host.equals(suffix) || host.endsWith("." + suffix));
    }
}
