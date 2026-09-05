import { ChangeDetectionStrategy, Component, type OnInit, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { IconComponent } from '../../shared/icon/icon.component';
import { LangService } from '../../services/lang.service';
import { MenuService, type PublishedProduct } from '../../services/menu.service';
import { OrdersService, type ApiOrder } from '../../services/orders.service';
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
 * <h2>Repeat, honestly</h2>
 *
 * `OrderResponse.lines` (`OrderLineResponse`) carries `productName` and
 * `variantName` as free text and **no variant id** -- the wire simply does not
 * expose one on an order line. So there is no way to silently rebuild the
 * exact original cart line. {@link repeat} instead matches each line's name
 * against the *current* published menu by product name, adds that product's
 * first orderable variant (never the original modifiers, which cannot be
 * resolved back to option ids either), and reports what it could and could
 * not do -- `orders.repeatAddedAll` / `repeatAddedPartial` / `repeatFailed` --
 * rather than claiming a perfect replay it cannot deliver.
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
  private readonly menu = inject(MenuService);
  private readonly lang = inject(LangService);
  protected readonly cart = inject(UiCartService);
  private readonly router = inject(Router);

  protected readonly state = signal<LoadState>('loading');
  protected readonly orders = signal<readonly ApiOrder[]>([]);
  protected readonly repeatingId = signal<number | string | null>(null);
  protected readonly repeatMessage = signal<RepeatMessage | null>(null);

  protected readonly active = computed(() =>
    this.orders().find((order) => ACTIVE_STATUSES.has(order.status?.id ?? '')) ?? null,
  );

  protected readonly history = computed(() => {
    const active = this.active();
    return this.orders().filter((order) => order !== active);
  });

  ngOnInit(): void {
    void this.refresh();
  }

  protected async refresh(): Promise<void> {
    this.state.set('loading');
    try {
      const orders = await firstValueFrom(this.ordersService.getOrders([], 50));
      this.orders.set(orders);
      this.state.set('ready');
    } catch {
      this.state.set('error');
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
   * Best-effort repeat: matches each line by product name against the current
   * menu, adds the first orderable variant found, and reports what happened.
   * See the class doc comment for why an exact replay is not possible.
   */
  protected async repeat(order: ApiOrder): Promise<void> {
    this.repeatingId.set(order.id);
    this.repeatMessage.set(null);
    try {
      const [detail, menu] = await Promise.all([
        firstValueFrom(this.ordersService.getOrderDetail(order.id)),
        this.menu.menu(this.lang.langId()),
      ]);

      let added = 0;
      let skipped = 0;
      for (const line of detail.items ?? []) {
        const name = (line.name ?? '').trim();
        const product = menu.products.find(
          (candidate) => name === candidate.name || name.startsWith(`${candidate.name} `),
        );
        const variant = product ? firstOrderableVariant(product) : null;
        if (!variant) {
          skipped += 1;
          continue;
        }
        await this.cart.add(variant.variantId, line.quantity ?? 1);
        added += 1;
      }

      if (added === 0) {
        this.repeatMessage.set({ key: 'orders.repeatFailed' });
        return;
      }
      this.repeatMessage.set(
        skipped === 0
          ? { key: 'orders.repeatAddedAll', params: { count: added } }
          : { key: 'orders.repeatAddedPartial', params: { added, skipped } },
      );
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

function firstOrderableVariant(product: PublishedProduct): PublishedProduct['variants'][number] | null {
  return product.variants.find((variant) => variant.orderable) ?? product.variants[0] ?? null;
}
