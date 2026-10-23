import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import type { MessageKey } from '../../core/i18n/message-keys';
import { TPipe } from '../../core/i18n/t.pipe';
import { DutyStore, type DutyIndicator } from '../../core/telemetry/duty.store';

const SENTENCES: Readonly<Record<Exclude<DutyIndicator, 'off'>, MessageKey>> = {
  tracking: 'duty.indicator.tracking',
  searching: 'duty.indicator.searching',
  denied: 'duty.indicator.denied',
  break: 'duty.indicator.break',
  unreachable: 'duty.indicator.unreachable',
};

/**
 * The visible on-duty indicator ADR 0045 requires: on every screen, for as long as a duty session
 * is open, saying in words whether the courier's location is really being shared -- and when it is
 * not, why. A courier must never be tracked without being able to see it, and must never believe
 * they are shared when the screen has gone to sleep.
 */
@Component({
  selector: 'app-duty-indicator',
  imports: [TPipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (sentence(); as key) {
      <div role="status">
        <a routerLink="/shift" class="indicator" [class.ok]="healthy()" [class.warn]="!healthy()">
          <span class="dot" aria-hidden="true"></span>
          {{ key | t }}
        </a>
      </div>
    }
  `,
  styleUrl: './duty-indicator.css',
})
export class DutyIndicatorBar {
  private readonly duty = inject(DutyStore);

  protected readonly sentence = computed(() => {
    const state = this.duty.indicator();
    return state === 'off' ? null : SENTENCES[state];
  });

  protected readonly healthy = computed(() => this.duty.indicator() === 'tracking');
}
