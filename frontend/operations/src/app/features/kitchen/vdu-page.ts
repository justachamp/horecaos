import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { LatenessPolicy, PLATFORM_DEFAULT_LATENESS_POLICY } from '../../core/lateness-policy';
import { LatenessPolicyApi } from '../../core/lateness-policy-api';
import { LatenessPolicyTracker } from '../../core/lateness-policy-tracker';
import { TPipe } from '../../core/i18n/t.pipe';
import { KitchenApi, TicketResponse } from './kitchen-api';
import { computeTicketSeverity, ticketSeverityInput } from './kitchen-ticket';

/** Same cadence as the KDS queue, until ADR 0045 exists. */
const POLL_INTERVAL_MS = 10_000;

/**
 * IA 2.4 — Display board (VDU): a read-only wall display of the combined
 * queue, for customers or staff to glance at from across the room.
 *
 * **Same data as the KDS** (`stream=live` — FIRED/IN_PRODUCTION/READY).
 * Read-only: no start/ready/recall here.
 *
 * **`externalReference` (wave T02, gap map row 2.4) is IA 2.4's one named
 * feature — "provider-assigned external identifiers shown to humans" —
 * finally carried.** Before this wave `TicketResponse` carried none, and
 * this page's own doc used to claim `sequenceLabel` filled that role; it
 * does not — `sequenceLabel` is HorecaOS's own number, and a courier or
 * customer reading an aggregator's code off their own app could not match
 * it to the wall. Both numbers render now: `sequenceLabel` first, since it
 * is what the kitchen itself calls the ticket, and `externalReference`
 * beneath it when the order carries one — a direct order, or a partner
 * order nobody has issued a reference for yet, shows only the first.
 *
 * **Reduced relative to the spec, deliberately, and named where it matters.**
 * IA Part 4's own component-gap list puts a TV-distance wallboard shell
 * beside the operator console and the device/KDS shell — none of the three
 * exist as separate templates yet (see `kitchen-shell.ts`'s own doc for the
 * KDS's identical reduction). This renders inside the same operator console
 * shell, with its own oversized type rather than an off-scale invention, the
 * same trade-off `today-page.ts` documents for the live board's counters.
 * The device class, a station filter, and a dedicated projection are ADR
 * 0041 rollout step 4 — a separate, larger item this wave does not build.
 */
@Component({
  selector: 'q-vdu-page',
  imports: [TPipe],
  templateUrl: './vdu-page.html',
  styleUrl: './vdu-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VduPage implements OnInit {
  private readonly kitchen = inject(KitchenApi);
  private readonly location = inject(CurrentLocation);
  private readonly latenessPolicyApi = inject(LatenessPolicyApi);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly tickets = signal<readonly TicketResponse[]>([]);
  protected readonly firstLoadComplete = signal(false);
  protected readonly denied = signal(false);

  private pollHandle: ReturnType<typeof setInterval> | null = null;
  /**
   * The resolved `ordering.lateness` policy (wave P06). A wall display is opened
   * once and left up, so {@link refreshPolicy} re-reads it on the poll (at most
   * once a minute) instead of holding the start-up copy for the page's lifetime.
   */
  private readonly policies = new LatenessPolicyTracker(this.latenessPolicyApi);
  private latenessPolicy: LatenessPolicy = PLATFORM_DEFAULT_LATENESS_POLICY;

  /** The tenant's `#rrggbb` for a late order (row `X.39`), or null for the design-system `--q-sla-late` token. */
  protected readonly lateColour = signal<string | null>(null);

  ngOnInit(): void {
    this.pollHandle = setInterval(() => void this.refresh(), POLL_INTERVAL_MS);
    this.destroyRef.onDestroy(() => {
      if (this.pollHandle !== null) {
        clearInterval(this.pollHandle);
      }
    });
    void this.start();
  }

  private async start(): Promise<void> {
    await this.location.ensureLoaded();
    await this.refresh();
  }

  /** Never rejects; a failed read leaves the last policy read, or the platform default before the first one. */
  private async refreshPolicy(scope: LocationScope): Promise<void> {
    await this.policies.refresh(scope);
    this.latenessPolicy = this.policies.policy(scope.locationId);
    this.lateColour.set(this.latenessPolicy.lateColour ?? null);
  }

  private async refresh(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.firstLoadComplete.set(true);
      return;
    }
    try {
      const [board] = await Promise.all([
        this.kitchen.board(scope, 'live'),
        this.refreshPolicy(scope),
      ]);
      this.tickets.set(
        [...board.tickets].sort((a, b) => statusRank(a.status) - statusRank(b.status)),
      );
      this.denied.set(false);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      }
      // A wall display swallows other errors rather than showing a stack —
      // it just keeps last-known rows on screen until the next poll succeeds.
    } finally {
      this.firstLoadComplete.set(true);
    }
  }

  /** A breached ticket takes the tenant's late colour; an at-risk one keeps the platform's amber. */
  protected lateColourFor(ticket: TicketResponse): string | null {
    return this.severityTone(ticket) === 'danger' ? this.lateColour() : null;
  }

  protected severityTone(ticket: TicketResponse): 'danger' | 'warning' | 'none' {
    return computeTicketSeverity(ticketSeverityInput(ticket), new Date(), this.latenessPolicy).tone;
  }
}

const STATUS_RANK: Readonly<Record<string, number>> = {
  READY: 0,
  IN_PRODUCTION: 1,
  FIRED: 2,
};

function statusRank(status: string): number {
  return STATUS_RANK[status] ?? 3;
}
