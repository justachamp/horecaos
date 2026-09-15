import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { ConnectionState } from '../../core/realtime/realtime-client';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';

/**
 * "A board that stopped updating looks exactly like a quiet shift — no
 * staleness marker, no connection banner" (`X.34`'s own gap text, wave P08).
 *
 * Renders nothing for `connecting`/`open` — the ordinary states, which need
 * no announcement — and a quiet, dismissable-by-nature (it disappears the
 * moment the state changes) `q-inline-alert`-styled band for `reconnecting`/
 * `unavailable`. Never `role="alert"`: reconnecting is not an emergency, and
 * every screen this feeds keeps working from its own 10s poll regardless —
 * see `RealtimeClient`'s own doc for why this is honestly informational
 * rather than a warning that something is broken.
 */
@Component({
  selector: 'q-connection-state-banner',
  imports: [TPipe],
  templateUrl: './connection-state-banner.html',
  styleUrl: './connection-state-banner.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConnectionStateBanner {
  readonly state = input.required<ConnectionState>();

  protected readonly visible = computed(
    () => this.state() === 'reconnecting' || this.state() === 'unavailable',
  );

  protected readonly messageKey = computed<MessageKey>(() =>
    this.state() === 'unavailable'
      ? 'ui.connectionState.unavailable'
      : 'ui.connectionState.reconnecting',
  );
}
