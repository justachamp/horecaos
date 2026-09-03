import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { I18nService } from '../../core/i18n/i18n.service';

/**
 * IA 1.2 Alerts & incidents -- not built.
 *
 * `ControlPlaneAlertController`'s own Javadoc names exactly this gap: it is
 * "v1: {@link ControlPlaneAlertPort}'s log line and counter" -- a signal
 * raised from `ops/control_band_watch.py` is logged and counted, and nothing
 * else. There is no persisted alert record anywhere in the schema and no
 * query surface: a control-plane screen cannot list "active alerts with
 * tenant/provider blast radius and on-call routing" when nothing durable
 * exists to list. Storing and querying alerts is a genuine new subsystem --
 * not a small addition on top of a fire-and-forget log line -- so it stays
 * unbuilt this wave.
 */
@Component({
  selector: 'app-alerts-incidents',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1 class="q-title">{{ i18n.t('nav.alertsIncidents') }}</h1>
    <section class="notice">
      <h2 class="q-subhead">{{ i18n.t('state.notBuilt.title') }}</h2>
      <p class="q-body-sm body">{{ i18n.t('alertsIncidents.notBuilt.body') }}</p>
    </section>
  `,
  styles: `
    .notice {
      margin-top: 24px;
      background: var(--q-canvas);
      border: 1px solid var(--q-hairline);
      padding: 24px;
      max-width: 640px;
    }

    .body {
      color: var(--q-ink-muted);
      margin-top: 8px;
    }
  `,
})
export class AlertsIncidents {
  protected readonly i18n = inject(I18nService);
}
