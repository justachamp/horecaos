package uz.qoida.platform.helpcenter.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.qoida.platform.helpcenter.domain.SupportContent.FaqCategory;
import uz.qoida.platform.helpcenter.domain.SupportContent.SocialLink;
import uz.qoida.platform.helpcenter.infrastructure.persistence.JdbcSupportStore;

/** What a brand publishes for its own customers to read. */
@Service
public class SupportQuery {

    private final JdbcSupportStore store;

    public SupportQuery(JdbcSupportStore store) {
        this.store = store;
    }

    /**
     * @return an empty list when the brand has published no FAQ. Empty and
     *     absent are the same answer on purpose: a brand with nothing to say is
     *     not an error, and a storefront renders it as a screen with no
     *     questions rather than as a failure.
     */
    @Transactional(readOnly = true)
    public List<FaqCategory> faq(UUID tenantId, UUID brandId, String locale) {
        return store.faq(tenantId, brandId, locale);
    }

    @Transactional(readOnly = true)
    public List<SocialLink> socialLinks(UUID tenantId, UUID brandId) {
        return store.socialLinks(tenantId, brandId);
    }
}
