import { ChangeDetectionStrategy, Component, type OnInit, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { IconComponent } from '../../shared/icon/icon.component';
import { LangService } from '../../services/lang.service';
import {
  OrdersService,
  type ApiOrder,
  type ReorderPlanResponse,
} from '../../services/orders.service';
import { TranslatePipe } from '../../shared/translate/translate.pipe';
import { UiCartService } from '../../services/ui-cart.service';

type LoadState = 'loading' | 'ready' | 'error';

/**
 * The real twelve-status vocabulary, grouped for this screen only. Never the
 * legacy `new`/`accepted`/`cooking` tokens `OrdersService.PLATFORM_STATUSES`
 * exists to translate *away from* -- this screen reads
 * `OrderSummaryResponse.status` directly.
 */
const ACTIVE_STATUSES: ReadonlySet<string> = new Set([
  'RECEIVED',
  'PAYMENT_AUTHORIZING',
  'AWAITING_APPROVAL',
  'CONFIRMED',
  'PREPARING',
  'READY',
  'FULFILLING',
]);

const ENDED_BADLY: ReadonlySet<string> = new Set([
  'CANCELLED',
  'REJECTED',
  'EXPIRED',
  'PAYMENT_FAILED',
]);

/** Where an active order sits on the four-step progress rail the design draws. */
function stageIndex(status: string): number {
  if (status === 'READY' || status === 'FULFILLING') {
    return 2;
  }
  if (status === 'CONFIRMED' || status === 'PREPARING') {
    return 1;
  }
  return 0;
}

/**
 * Buyurtmalar: the active order's real status, order history, and a
 * best-effort repeat.
 *
 * <h2>What the design shows that this screen does not build</h2>
 *
 * **The live courier card** (name, vehicle, plate, a call button). There is no
 * storefront endpoint that names who is carrying an order -- `FULFILLING`
 * is a status, not an identity -- so it is left out rather than shown with
 * invented details.
 *
 * **Per-order rating from the history card.** The design's "Baholash" button
 * lives here; this wave puts the rating flow on the Profile screen instead
 * (see `ProfileComponent`'s own doc comment), so history rows only ever link
 * to repeat and never to a rating sheet.
 *
 * <h2>Repeat, resolved rather than guessed</h2>
 *
 * This screen used to repeat an order by matching each line's *name* against
 * the current menu and adding the first orderable variant it found -- a
 * renamed dish silently failed, a two-variant dish got whichever came first,
 * and the modifiers were lost. ADR 0074 replaced that with
 * `GET /orders/{id}/reorder`, which resolves the ids the order actually stored
 * against the menu as it stands now, including this location's offerings and
 * the kitchen's 86 list -- neither of which a storefront can see.
 *
 * So {@link repeat} rebuilds the exact line: same variant, same modifiers, same
 * quantity. No name matching remains, and `MenuService` is no longer read here
 * at all.
 *
 * <h2>Why one button and not one per row</h2>
 *
 * The plan is a request per order, and the history list is fifty rows deep.
 * A button that must be hidden unless every line is available cannot be
 * rendered before its plan arrives, so rendering fifty of them means fifty
 * requests before the screen settles. The repeat button therefore belongs to
 * the newest order in the history -- the one a customer actually repeats --
 * and older rows carry none. Widening that is a plan request per row, not a
 * change of contract.
 *
 * <h2>Hidden, not greyed</h2>
 *
 * The button appears only on `READY`. `PARTIAL` -- some lines available -- is
 * treated as not offerable here, by the platform owner's own instruction:
 * a repeat that quietly drops a dish is not a repeat. The verdict carries all
 * three values, so this is one line to change if that policy moves.
 */
@Component({
  selector: 'app-orders',
  standalone: true,
  imports: [IconComponent, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './orders.component.html',
  styleUrl: './orders.component.scss',
})
export class OrdersComponent implements OnInit {
  private readonly ordersService = inject(OrdersService);
  private readonly lang = inject(LangService);
  protected readonly cart = inject(UiCartService);
  private readonly router = inject(Router);

  protected readonly state = signal<LoadState>('loading');
  protected readonly orders = signal<readonly ApiOrder[]>([]);
  protected readonly repeatingId = signal<number | string | null>(null);
  protected readonly repeatMessage = signal<RepeatMessage | null>(null);

  /**
   * The plan for the newest history order, or null while it is in flight, has
   * failed, or says the order cannot be repeated.
   *
   * Null and "not READY" render identically -- no button -- deliberately: a
   * plan request that failed must not leave a button that would fail too.
   */
  protected readonly repeatablePlan = signal<ReorderPlanResponse | null>(null);

  protected readonly active = computed(() =>
    this.orders().find((order) => ACTIVE_STATUSES.has(order.status?.id ?? '')) ?? null,
  );

  protected readonly history = computed(() => {
    const active = this.active();
    return this.orders().filter((order) => order !== active);
  });

  /** True for the one row the repeat button belongs to, once its plan says READY. */
  protected canRepeat(order: ApiOrder): boolean {
    const plan = this.repeatablePlan();
    return plan !== null && plan.verdict === 'READY' && String(order.id) === plan.orderId;
  }

  ngOnInit(): void {
    void this.refresh();
  }

  protected async refresh(): Promise<void> {
    this.state.set('loading');
    try {
      const orders = await firstValueFrom(this.ordersService.getOrders([], 50));
      this.orders.set(orders);
      this.state.set('ready');
      void this.loadRepeatablePlan();
    } catch {
      this.state.set('error');
    }
  }

  /**
   * Asks whether the newest past order can be repeated.
   *
   * Deliberately not awaited by {@link refresh}: the history renders on the
   * order list, and the button appears a moment later when the platform has
   * answered. A failure leaves the plan null, which renders as no button --
   * the safe direction, and the reason this swallows rather than surfaces.
   */
  private async loadRepeatablePlan(): Promise<void> {
    this.repeatablePlan.set(null);
    const newest = this.history()[0];
    if (!newest) {
      return;
    }
    try {
      this.repeatablePlan.set(await firstValueFrom(this.ordersService.getReorderPlan(newest.id)));
    } catch {
      this.repeatablePlan.set(null);
    }
  }

  protected stage(order: ApiOrder): number {
    return stageIndex(order.status?.id ?? '');
  }

  protected isEndedBadly(order: ApiOrder): boolean {
    return ENDED_BADLY.has(order.status?.id ?? '');
  }

  protected statusKey(order: ApiOrder): string {
    return `orders.platformStatus.${order.status?.id ?? ''}`;
  }

  protected dateLabel(order: ApiOrder): string {
    const raw = order.created_date;
    if (!raw) {
      return '';
    }
    const date = new Date(raw);
    if (Number.isNaN(date.getTime())) {
      return '';
    }
    return date.toLocaleDateString(this.lang.langId() === 'uz' ? 'uz-UZ' : this.lang.langId(), {
      day: 'numeric',
      month: 'long',
    });
  }

  protected totalLabel(order: ApiOrder): string {
    const amount = order.total_price ?? order.total;
    return amount != null ? this.cart.formatPrice(amount) : '';
  }

  protected goHome(): void {
    void this.router.navigate(['/home']);
  }

  /**
   * Rebuilds the order from its plan.
   *
   * Every line is added with the variant and modifier ids the platform
   * resolved, so this is the same basket and not an approximation of it. It
   * runs only where {@link canRepeat} is true, which means the plan said READY
   * -- every line available -- so there is no partial outcome to report and no
   * "3 of 4 added" message.
   *
   * The plan is still a snapshot. A dish 86'd between reading it and this call
   * makes the add fail, and `UiCartService` surfaces that; pricing refuses
   * afterwards regardless. Narrowing that window is what the plan is for;
   * closing it is not possible from a client.
   */
  protected async repeat(order: ApiOrder): Promise<void> {
    const plan = this.repeatablePlan();
    if (!plan || plan.verdict !== 'READY' || String(order.id) !== plan.orderId) {
      return;
    }

    this.repeatingId.set(order.id);
    this.repeatMessage.set(null);
    try {
      for (const line of plan.lines) {
        await this.cart.add(line.variantId, line.quantity, undefined, line.modifierOptionIds);
      }
      this.repeatMessage.set({ key: 'orders.repeatAddedAll', params: { count: plan.lines.length } });
      await this.router.navigate(['/cart']);
    } catch {
      this.repeatMessage.set({ key: 'errors.generic' });
    } finally {
      this.repeatingId.set(null);
    }
  }
}

/** A repeat outcome, as the template renders it: a key plus its interpolation values. */
export interface RepeatMessage {
  readonly key: string;
  readonly params?: Record<string, string | number>;
}
