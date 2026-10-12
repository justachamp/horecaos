package uz.horecaos.platform.marketing.infrastructure.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.marketing.api.AttributionClickSeries;

/** {@link AttributionClickSeries} over {@code marketing.attribution_link_clicks_day} (ADR 0167). */
@Service
public class JdbcAttributionClickSeries implements AttributionClickSeries {

    private final JdbcAttributionLinkStore store;

    public JdbcAttributionClickSeries(JdbcAttributionLinkStore store) {
        this.store = store;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LinkClicks> clicksBetween(UUID tenantId, @Nullable UUID brandId, LocalDate from, LocalDate to) {
        return store.clicksBetween(tenantId, brandId, from, to).stream()
                .map(row -> new LinkClicks(
                        row.linkId(), row.brandId(), row.label(), row.channel(), row.status(), row.clicks()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countOlderThan(LocalDate cutoff) {
        return store.countClickSeriesOlderThan(cutoff);
    }

    @Override
    @Transactional
    public int deleteOlderThan(LocalDate cutoff) {
        return store.deleteClickSeriesOlderThan(cutoff);
    }
}
