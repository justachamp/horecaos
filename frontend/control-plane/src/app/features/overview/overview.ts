import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';

import { APP_CONFIG } from '../../core/config/app-config';
import { AuthService } from '../../core/auth/auth.service';
import { SessionContextService } from '../../core/auth/session-context.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';

/**
 * The landing section.
 *
 * Deliberately not a dashboard. The real overview is specified in the platform
 * repository and belongs to whoever builds it; what is here is a diagnostic
 * panel showing that the shell, the session and the API client are wired, and
 * it is deleted when the real screen arrives. A half-built dashboard would be
 * harder to delete and would be mistaken for a specification.
 */
@Component({
  selector: 'app-overview',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './overview.html',
  styleUrl: './overview.css',
})
export class Overview {
  protected readonly i18n = inject(I18nService);
  private readonly auth = inject(AuthService);
  private readonly session = inject(SessionContextService);
  private readonly config = inject(APP_CONFIG);

  protected readonly rows = computed(() => [
    { labelKey: 'overview.foundations.auth' as MessageKey, value: this.i18n.t(`auth.${this.auth.status()}` as MessageKey) },
    { labelKey: 'overview.foundations.api' as MessageKey, value: this.config.apiBaseUrl },
    {
      labelKey: 'overview.foundations.capabilities' as MessageKey,
      value: this.session.loaded()
        ? String(this.session.current()?.capabilities.length ?? 0)
        : this.i18n.t('overview.foundations.capabilitiesUnknown'),
    },
    { labelKey: 'overview.foundations.locale' as MessageKey, value: this.i18n.locale() },
    { labelKey: 'overview.foundations.timeZone' as MessageKey, value: this.config.displayTimeZone },
  ]);
}
