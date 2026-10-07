import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';

import { TimeZone, formatClock } from '../core/format/datetime';
import { I18n } from '../core/i18n/i18n';
import { TPipe } from '../core/i18n/t.pipe';
import { LatenessPolicy } from '../core/lateness-policy';
import { VduTicketResponse } from '../features/kitchen/kitchen-api';
import { computeTicketSeverity } from '../features/kitchen/kitchen-ticket';

export type WallboardVduFreshness = 'loading' | 'fresh' | 'aging' | 'stale';

/** What the board area says before it has tickets to show. */
export type VduWallPhase = 'loading' | 'ready' | 'denied';

/**
 * The rendering of a kitchen wall display (ADR 0041 rollout step 4, ADR 0151): the tickets, painted by
 * the tenant's own lateness policy, from across a room, with no control of any kind.
 *
 * **Rendering only, and that is the point.** Two hosts draw this and they load their data in entirely
 * different ways, which is why it takes everything as an input rather than reading anything itself: the
 * staff-session preview ({@link WallboardVduPage}, for a manager previewing a wall on a laptop) reads
 * through the staff client with a station `<select>`, the realtime stream and the stations list; the
 * device shell's wall mode reads *only* the device's own record and the VDU projection, with the device's
 * own bearer, because a wall display holds one capability and a read added to it later would fail
 * quietly on a 403 instead of in a spec. Neither host's data loading belongs in the picture they share.
 *
 * Controls of the host (a station filter, a live badge) and its banners (connection state) are projected
 * into the two named slots; this component has no button, link or input of its own.
 */
@Component({
  selector: 'q-vdu-wall',
  imports: [TPipe],
  templateUrl: './vdu-wall.html',
  styleUrl: './vdu-wall.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VduWall {
  private readonly i18n = inject(I18n);

  readonly tickets = input.required<readonly VduTicketResponse[]>();
  readonly policy = input.required<LatenessPolicy>();
  /** The branch's own IANA zone. The preview has none of its own and passes the console's placeholder. */
  readonly timeZone = input.required<TimeZone>();
  readonly phase = input.required<VduWallPhase>();
  readonly freshness = input.required<WallboardVduFreshness>();
  readonly freshnessLabel = input.required<string>();
  /** The wall clock, ticked by the host each second so severity is re-evaluated against it. */
  readonly now = input.required<number>();
  /** The station this wall shows, named for the room; null for the whole branch. */
  readonly stationLabel = input<string | null>(null);

  protected readonly lateColour = computed(() => this.policy().lateColour ?? null);

  protected targetReadyLabel(ticket: VduTicketResponse): string | null {
    return ticket.targetReadyAt
      ? formatClock(new Date(ticket.targetReadyAt), this.timeZone())
      : null;
  }

  protected courierEtaLabel(ticket: VduTicketResponse): string | null {
    return ticket.courierEtaAt
      ? this.i18n.t('kitchen.ticket.courierEta', {
          time: formatClock(new Date(ticket.courierEtaAt), this.timeZone()),
        })
      : null;
  }

  /** A breached ticket takes the tenant's late colour; an at-risk one keeps the platform's amber. */
  protected lateColourFor(ticket: VduTicketResponse): string | null {
    return this.severityTone(ticket) === 'danger' ? this.lateColour() : null;
  }

  protected severityTone(ticket: VduTicketResponse): 'danger' | 'warning' | 'none' {
    return computeTicketSeverity(
      {
        targetReadyAt: ticket.targetReadyAt ? new Date(ticket.targetReadyAt) : null,
        createdAt: new Date(ticket.createdAt),
        fulfilmentMode: ticket.fulfilmentMode,
      },
      new Date(this.now()),
      this.policy(),
    ).tone;
  }
}
