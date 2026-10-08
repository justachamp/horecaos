package uz.horecaos.platform.content.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.content.application.ContentHomeAssembler.Source;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The storefront's content read and the console's preview of it (ADR 0157, Decision 9).
 *
 * <p>Both go through {@link ContentHomeAssembler}; the only difference is the source. The live read
 * is computed per request from two indexed content queries and one bounded batch each of the
 * catalogue, pricing and media lookups. <strong>No cache is registered</strong>: a 30-second
 * in-process entry is added only if the measured budget is missed (ADR 0033), and the response is
 * made cheap to revalidate by a body-digest {@code ETag} instead.
 */
@Service
public class ContentHomeService {

    private final ContentEnvironment environment;
    private final ContentHomeAssembler assembler;
    private final Clock clock;

    public ContentHomeService(ContentEnvironment environment, ContentHomeAssembler assembler, Clock clock) {
        this.environment = environment;
        this.assembler = assembler;
        this.clock = clock;
    }

    /**
     * What customers see right now.
     *
     * @return empty for an unknown brand, a brand of another tenant, or an unknown channel: one
     *         answer for all three, so the endpoint cannot be used to probe what exists
     */
    @Transactional(readOnly = true)
    public Optional<HomeDocument> home(UUID tenantId, UUID brandId, String channelCode, @Nullable String locale) {
        if (!environment.brand().brandIsActive(tenantId, brandId)) {
            return Optional.empty();
        }
        return environment
                .channel(tenantId, channelCode)
                .map(channel -> assembler.assemble(tenantId, brandId, channel, locale, clock.instant(), Source.LIVE));
    }

    /** What customers would see at {@code asOf} if every draft were published: the same assembler, drafts overlaid. */
    @Transactional(readOnly = true)
    public HomeDocument preview(
            UUID tenantId, UUID brandId, String channelCode, @Nullable String locale, @Nullable Instant asOf) {
        SalesChannel channel = environment
                .channel(tenantId, channelCode)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such channel"));
        return assembler.assemble(
                tenantId, brandId, channel, locale, asOf == null ? clock.instant() : asOf, Source.DRAFT_OVERLAY);
    }
}
