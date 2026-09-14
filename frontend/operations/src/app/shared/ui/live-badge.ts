import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { ConnectionState } from '../../core/realtime/realtime-client';
import { TPipe } from '../../core/i18n/t.pipe';

/**
 * A small positive marker that the ADR 0045 accelerator is connected (row
 * `X.34`, wave P08).
 *
 * Deliberately silent about everything except "connected". `ConnectionStateBanner`
 * is where `reconnecting`/`unavailable` are said out loud — this renders
 * nothing at all outside `state() === 'open'`, on purpose: a board with the
 * accelerator down still works from its own 10s poll (`RealtimeClient`'s own
 * doc), so absence-of-badge reading as "maybe polling, maybe mid-reconnect,
 * check the banner if you care" is the correct amount of alarm for an
 * ordinary transient state, not a red flag on every screen the instant a
 * deploy drops every stream on the box at once.
 */
@Component({
  selector: 'q-live-badge',
  imports: [TPipe],
  templateUrl: './live-badge.html',
  styleUrl: './live-badge.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LiveBadge {
  readonly state = input.required<ConnectionState>();
}
