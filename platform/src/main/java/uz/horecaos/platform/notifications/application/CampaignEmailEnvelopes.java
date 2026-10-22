package uz.horecaos.platform.notifications.application;

import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.notifications.api.NotificationDispatch.EmailEnvelope;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.NotificationRow;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore;
import uz.horecaos.platform.tenancy.api.BrandDisplayNames;

/**
 * The envelope of a campaign email (ADR 0182, D13): the brand's name, a token that unsubscribes
 * exactly this customer from exactly this purpose, and the sentence that tells them so in the
 * language of the message.
 *
 * <p>The link points at the platform's own public origin, not at a storefront: a mail client's
 * unsubscribe button posts to it with no session and no particular storefront in mind, and the
 * origin is one deployment setting where a storefront's hostname is one per tenant.
 */
@Component
public class CampaignEmailEnvelopes implements EmailEnvelopes {

    private final EmailUnsubscribeService unsubscribes;
    private final BrandDisplayNames brands;
    private final JdbcTemplateStore templates;
    private final String publicApiOrigin;

    public CampaignEmailEnvelopes(
            EmailUnsubscribeService unsubscribes,
            BrandDisplayNames brands,
            JdbcTemplateStore templates,
            @Value("${horecaos.public-api-origin:http://localhost:8080}") String publicApiOrigin) {
        this.unsubscribes = unsubscribes;
        this.brands = brands;
        this.templates = templates;
        this.publicApiOrigin = publicApiOrigin.endsWith("/")
                ? publicApiOrigin.substring(0, publicApiOrigin.length() - 1)
                : publicApiOrigin;
    }

    @Override
    public Optional<EmailEnvelope> forMessage(NotificationRow row) {
        if (!NotificationChannel.EMAIL.name().equals(row.channel())
                || row.recipientAccountId() == null
                || row.brandId() == null
                || row.templateId() == null) {
            return Optional.empty();
        }
        // The purpose the message was sent under is the template's: it is what the eligibility gate
        // read the customer's consent against, so it is what an unsubscribe must withdraw.
        Optional<String> purpose =
                templates.template(row.tenantId(), row.templateId()).map(JdbcTemplateStore.TemplateRow::consentPurpose);
        if (purpose.isEmpty()) {
            return Optional.empty();
        }
        String brandName = brands.displayName(row.tenantId(), row.brandId()).orElse("");
        String token = unsubscribes.issue(
                row.tenantId(), row.brandId(), row.recipientAccountId(), row.id(), purpose.get(), row.locale());
        String url = publicApiOrigin + "/providers/mail/unsubscribe/" + token;
        return Optional.of(new EmailEnvelope(brandName, url, footer(row.locale(), brandName, url)));
    }

    /** The sentence under the message, in its language. Russian is the fallback, as everywhere here. */
    static String footer(@Nullable String locale, String brandName, String url) {
        String brand = brandName.isBlank() ? "HorecaOS" : brandName;
        return switch (locale == null ? "ru" : locale.toLowerCase(Locale.ROOT)) {
            case "uz-latn", "uz" ->
                "Siz %s xabarlarini olishga rozilik bergansiz. Obunani bekor qilish uchun oching: %s"
                        .formatted(brand, url);
            case "en" ->
                "You are receiving this because you agreed to messages from %s. To stop them, open: %s"
                        .formatted(brand, url);
            default ->
                "Вы получили это письмо, потому что согласились на рассылки от %s. Чтобы отказаться, откройте: %s"
                        .formatted(brand, url);
        };
    }
}
