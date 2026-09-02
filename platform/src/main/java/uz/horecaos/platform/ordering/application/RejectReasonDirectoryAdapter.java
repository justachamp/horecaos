package uz.horecaos.platform.ordering.application;

import java.util.List;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.ordering.api.RejectReasonDirectory;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcRejectReasonStore.ReasonRow;

/**
 * The {@code ordering.api} face of {@link RejectReasonQueryService#listActive()}
 * (wave 24). A translation layer only — see {@link RejectReasonDirectory}'s own
 * doc for why it exists.
 */
@Component
public class RejectReasonDirectoryAdapter implements RejectReasonDirectory {

    private final RejectReasonQueryService reasons;

    public RejectReasonDirectoryAdapter(RejectReasonQueryService reasons) {
        this.reasons = reasons;
    }

    @Override
    public List<Option> topOptions() {
        return reasons.listActive().stream()
                .filter(reason -> !reason.requiresNote())
                .map(this::toOption)
                .toList();
    }

    private Option toOption(ReasonRow row) {
        return new Option(row.code(), row.labels());
    }
}
