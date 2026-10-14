import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';

import { CurrentLocation } from '../../core/auth/current-location';
import { TimeZone, formatDateTime } from '../../core/format/datetime';
import { I18n } from '../../core/i18n/i18n';
import { PropagationBinding, StopsApi } from './stop-scope-api';

/** See `stop-list-page.ts`'s identical constant — no location carries a timezone on this response yet. */
const PLACEHOLDER_TIME_ZONE: TimeZone = 'Asia/Tashkent';

/**
 * What each connected marketplace has and has not been told about the stop list (ADR 0141,
 * "When the partner is down" and "When there is no push API").
 *
 * Honest in the three ways it has to be. A marketplace whose provider has no availability
 * write API says it is **not propagated automatically** and tells the operator to use the
 * partner portal — never a quiet "in sync". A paused reconciler says so. A live one says how
 * many items the platform has not been able to confirm, and since when: "Yandex: 7 items not
 * confirmed since 14:32 — update in the partner portal". Silence is only for a location with no
 * marketplace binding at all.
 *
 * A failed read renders nothing: a banner that guessed would be worse than none, and the stop
 * list itself is unaffected.
 */
@Component({
  selector: 'q-stop-propagation-banner',
  templateUrl: './stop-propagation-banner.html',
  styleUrl: './stop-propagation-banner.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StopPropagationBanner {
  private readonly stops = inject(StopsApi);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  /** Bump to re-read — the page does after every stop it makes or lifts. */
  readonly refreshKey = input(0);

  protected readonly bindings = signal<readonly PropagationBinding[]>([]);

  constructor() {
    effect(() => {
      this.refreshKey();
      untracked(() => void this.load());
    });
  }

  private async load(): Promise<void> {
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    try {
      this.bindings.set(await this.stops.propagation(scope));
    } catch {
      this.bindings.set([]);
    }
  }

  /** The one line a binding reads as. One literal `t` key per case, so a typo is a build error. */
  protected line(binding: PropagationBinding): string {
    const name = binding.displayName || binding.providerType;
    switch (binding.mode) {
      case 'MANUAL':
        return this.i18n.t('kitchen.stopList.propagation.manual', { name });
      case 'SUSPENDED':
        return this.i18n.t('kitchen.stopList.propagation.suspended', { name });
      case 'DRY_RUN':
        return this.i18n.t('kitchen.stopList.propagation.dryRun', {
          name,
          count: binding.wouldPush ?? binding.unconfirmed,
        });
      case 'AUTOMATIC':
        return binding.unconfirmed > 0
          ? this.i18n.t('kitchen.stopList.propagation.pending', {
              name,
              count: binding.unconfirmed,
              since: this.since(binding),
            })
          : this.i18n.t('kitchen.stopList.propagation.inSync', { name });
    }
  }

  protected needsAttention(binding: PropagationBinding): boolean {
    return binding.mode !== 'AUTOMATIC' || binding.unconfirmed > 0;
  }

  /**
   * The open stale-channel incident of a binding, if one is open (ADR 0180 Decision 1): the alert the manager may
   * have missed in the chat, kept in view until the partner confirms again, when it closes by itself.
   */
  protected incident(binding: PropagationBinding): string | null {
    return binding.incidentOpenSince
      ? this.i18n.t('kitchen.stopList.propagation.incident', {
          name: binding.displayName || binding.providerType,
          since: formatDateTime(new Date(binding.incidentOpenSince), PLACEHOLDER_TIME_ZONE),
        })
      : null;
  }

  private since(binding: PropagationBinding): string {
    return binding.oldestUnconfirmedSince
      ? formatDateTime(new Date(binding.oldestUnconfirmedSince), PLACEHOLDER_TIME_ZONE)
      : '—';
  }
}
