import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnInit,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';

import { firstPage } from '../../../core/api/page';
import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { formatMoney } from '../../../core/format/money';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { Combobox, ComboboxOption } from '../../../shared/ui/combobox';
import { DeniedState } from '../../../shared/ui/denied-state';
import { NumberStepper } from '../../../shared/ui/number-stepper';
import { Toasts } from '../../../shared/ui/toast';
import {
  CreateCustomerDialog,
  CreateCustomerSubmission,
} from '../../customers/create-customer-dialog';
import { CustomerOrderSummary, CustomersApi } from '../../customers/customers-api';
import { ChannelView, SalesChannelsApi } from '../../settings/sales-channels/sales-channels-api';
import { accessRefusal, describeApiError } from '../order-errors';
import { ItemModifierDialog, ModifierDialogConfirmation } from './item-modifier-dialog';
import {
  CustomerLookupCandidate,
  MenuCategory,
  MenuModifierGroup,
  MenuProduct,
  MenuVariant,
  NewOrderApi,
  PlaceOrderLine,
  StorefrontMenu,
} from './new-order-api';
import { BasketLine, computeBasketTotal } from './new-order-total';

/**
 * The tenant's operator/call-centre channel is `tenant.sales_channels` data
 * (ADR 0036) — its `code` is chosen per tenant, only its `systemType` is
 * fixed to `CALL_CENTRE`. Resolving it needs `CHANNEL_READ`, which neither
 * `LOCATION_STAFF` nor `LOCATION_MANAGER` holds (`PlatformRole.java`) — the
 * same scope gap `new-order-total.ts` documents for pricing. So this screen
 * tries the real read (a manager persona with broader scope may hold it) and
 * falls back to this fixed code otherwise, exactly the graceful-degradation
 * shape `drafts-page.ts` already uses for the same API
 * (`this.channelsApi.list(scope).catch(() => [])`). Flagged in the wave
 * report as an open capability-model gap, not silently patched over by
 * widening a role bundle this wave was not asked to touch.
 */
const FALLBACK_OPERATOR_CHANNEL_CODE = 'call-centre';

let lineKeySequence = 0;
function nextLineKey(): string {
  lineKeySequence += 1;
  return `line-${lineKeySequence}-${Date.now()}`;
}

interface PendingModifierSelection {
  readonly product: MenuProduct;
  readonly variant: MenuVariant;
  readonly groups: readonly MenuModifierGroup[];
}

/**
 * New order — orders.md §5, the call-centre order-entry screen (wave P13).
 *
 * **Built this wave.** The three-pane composer: phone lookup and
 * create-on-miss (§5.3, reusing `CreateCustomerDialog`/`CustomersApi` from
 * the Customers section rather than a second create-customer form), item
 * search over the published menu plus the category grid fallback (§5.5),
 * modifier selection (`q-item-modifier-dialog`), a running total, and
 * `Создать` — `POST .../orders` with an `Idempotency-Key`, routing straight
 * to the created order (§5.6). Fulfilment is `PICKUP` only: `DELIVERY` needs
 * the address pane wave `P14` owns, and offering it here would mean
 * inventing an address flow this wave's brief explicitly says not to build.
 *
 * **Not built, honestly.** No address pane (`P14`), no pre-order time
 * (deferred), no promo code or change-due (`P14`'s `1.3e`), no «Повторить» —
 * the phone lookup's history peek shows only what
 * `OperatorCustomerLookupService` already returns (`lastOrderAt`,
 * `recentOrderCount`); the itemized last-orders popover with a working
 * repeat button needs `P14`'s staff-capability reorder wrapper
 * (`GET .../customers/{accountId}/orders/{orderId}/reorder`, `orders.md`
 * §5.3 point 4) and, for the popover's own list, a location-reachable order
 * history read — `CustomerOrderHistoryController` is `ORDER_READ` at
 * `BRAND` scope, which `LOCATION_STAFF` does not hold either. The header's
 * draft timer and quote-expiry states (§5.7) do not apply to this backend
 * shape at all: `OperatorOrderingService.place` opens the cart, prices it and
 * checks out in one atomic call, so there is no server-side draft cart that
 * can expire out from under the operator the way a storefront cart can.
 */
@Component({
  selector: 'q-new-order-page',
  imports: [TPipe, Combobox, NumberStepper, CreateCustomerDialog, ItemModifierDialog, DeniedState],
  templateUrl: './new-order-page.html',
  styleUrl: './new-order-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NewOrderPage implements OnInit {
  private readonly api = inject(NewOrderApi);
  private readonly customersApi = inject(CustomersApi);
  private readonly channelsApi = inject(SalesChannelsApi);
  private readonly location = inject(CurrentLocation);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly toasts = inject(Toasts);
  protected readonly i18n = inject(I18n);

  private readonly phoneInput = viewChild<ElementRef<HTMLInputElement>>('phoneInput');

  /**
   * ADR 0064: set only when this screen was opened from a claimed screen-pop
   * card — the shell's call bar and `call-centre-page.ts`'s own "start
   * order" link both navigate here with `?callEventId=...`. `submit()`
   * links the placed order to it, write-once, once it exists.
   */
  private readonly callEventId = this.route.snapshot.queryParamMap.get('callEventId');

  // ------------------------------------------------------------- bootstrap

  protected readonly locationDenied = signal(false);
  protected readonly menu = signal<StorefrontMenu | null>(null);
  protected readonly menuLoading = signal(true);
  protected readonly menuError = signal<string | null>(null);
  protected readonly channelCode = signal<string>(FALLBACK_OPERATOR_CHANNEL_CODE);

  async ngOnInit(): Promise<void> {
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.locationDenied.set(this.location.denied());
      this.menuLoading.set(false);
      return;
    }
    const locale = this.menuLocale();
    const channels = await this.channelsApi.list(scope).catch(() => [] as readonly ChannelView[]);
    const callCentre = channels.find(
      (channel) => channel.systemType === 'CALL_CENTRE' && channel.status === 'ACTIVE',
    );
    this.channelCode.set(callCentre?.code ?? FALLBACK_OPERATOR_CHANNEL_CODE);

    try {
      this.menu.set(await this.api.menu(scope, this.channelCode(), locale));
    } catch (error) {
      this.menuError.set(
        error instanceof ApiError
          ? describeApiError(error, (key, values) => this.i18n.t(key, values))
          : this.i18n.t('error.unknown.noReference'),
      );
    } finally {
      this.menuLoading.set(false);
    }

    queueMicrotask(() => this.phoneInput()?.nativeElement.focus());
  }

  private menuLocale(): string {
    // The menu endpoint's locale is `ru`/`uz`/`en`; the console's own
    // `uz-Latn` locale maps to the menu's `uz`, same mapping `stop-list-page.ts`
    // already applies for the identical reason.
    return this.i18n.locale() === 'uz-Latn' ? 'uz' : this.i18n.locale();
  }

  private readonly variantIndex = computed(() => {
    const index = new Map<string, { product: MenuProduct; variant: MenuVariant }>();
    for (const product of this.menu()?.products ?? []) {
      for (const variant of product.variants) {
        index.set(variant.variantId, { product, variant });
      }
    }
    return index;
  });

  private readonly modifierGroupIndex = computed(
    () =>
      new Map((this.menu()?.modifierGroups ?? []).map((group) => [group.modifierGroupId, group])),
  );

  protected modifierGroupsFor(product: MenuProduct): readonly MenuModifierGroup[] {
    const index = this.modifierGroupIndex();
    return product.modifierGroupIds
      .map((id) => index.get(id))
      .filter((group): group is MenuModifierGroup => group !== undefined);
  }

  /** The category grid's own row — orders.md §5.5's fallback for a caller browsing aloud. */
  protected productsIn(category: MenuCategory, menu: StorefrontMenu): readonly MenuProduct[] {
    const byId = new Map(menu.products.map((product) => [product.productId, product]));
    return category.productIds
      .map((id) => byId.get(id))
      .filter((product): product is MenuProduct => product !== undefined);
  }

  /** A struck-through стоп chip, orders.md §5.5: visible and not addable, never hidden. */
  protected isAnyVariantOrderable(product: MenuProduct): boolean {
    return product.variants.some((variant) => variant.orderable);
  }

  /** `LARGE, EXTRA_SHOT×3` — a compact summary of one basket line's chosen modifiers. */
  protected modifierSummary(line: BasketLine): string {
    return line.modifiers
      .map((modifier) =>
        modifier.quantity > 1 ? `${modifier.code}×${modifier.quantity}` : modifier.code,
      )
      .join(', ');
  }

  // -------------------------------------------------------------- §5.3 customer

  protected readonly phone = signal('');
  protected readonly customerCandidates = signal<readonly CustomerLookupCandidate[]>([]);
  protected readonly customerLookupBusy = signal(false);
  protected readonly customerLookupError = signal<string | null>(null);
  protected readonly customerSearched = signal(false);
  protected readonly selectedCustomer = signal<{ accountId: string; label: string } | null>(null);
  protected readonly nonContactableNotice = signal(false);

  protected readonly createDialogOpen = signal(false);
  protected readonly createBusy = signal(false);
  protected readonly createError = signal<string | null>(null);

  protected readonly historyOpen = signal(false);
  protected readonly historyLoading = signal(false);
  protected readonly historyOrders = signal<readonly CustomerOrderSummary[]>([]);

  private phoneLookupTimer: ReturnType<typeof setTimeout> | null = null;

  protected onPhoneInput(value: string): void {
    this.phone.set(value);
    if (this.phoneLookupTimer !== null) {
      clearTimeout(this.phoneLookupTimer);
    }
    const digitCount = value.replace(/\D/g, '').length;
    if (digitCount < 9) {
      this.customerCandidates.set([]);
      this.customerSearched.set(false);
      return;
    }
    this.phoneLookupTimer = setTimeout(() => void this.lookupPhone(), 400);
  }

  private async lookupPhone(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.customerLookupBusy.set(true);
    this.customerLookupError.set(null);
    try {
      const candidates = await this.api.lookupCustomerByPhone(scope, this.phone().trim());
      this.customerCandidates.set(candidates);
      this.customerSearched.set(true);
    } catch (error) {
      this.customerLookupError.set(
        error instanceof ApiError
          ? describeApiError(error, (key, values) => this.i18n.t(key, values))
          : this.i18n.t('error.unknown.noReference'),
      );
    } finally {
      this.customerLookupBusy.set(false);
    }
  }

  protected selectCandidate(candidate: CustomerLookupCandidate): void {
    this.selectedCustomer.set({
      accountId: candidate.accountId,
      label: candidate.maskedDisplayName ?? this.i18n.t('orders.newOrder.customer.unnamed'),
    });
    this.nonContactableNotice.set(false);
    this.historyOpen.set(false);
  }

  protected changeCustomer(): void {
    this.selectedCustomer.set(null);
    this.nonContactableNotice.set(false);
    this.historyOpen.set(false);
  }

  protected openCreateDialog(): void {
    this.createError.set(null);
    this.createDialogOpen.set(true);
  }

  protected onCreateDismiss(): void {
    this.createDialogOpen.set(false);
  }

  protected async onCreateSubmit(submission: CreateCustomerSubmission): Promise<void> {
    const scope = this.location.scope();
    if (!scope || this.createBusy()) {
      return;
    }
    this.createBusy.set(true);
    this.createError.set(null);
    try {
      const accountId = await this.customersApi.create(scope, {
        brandId: scope.brandId,
        phone: submission.phone,
        displayName: submission.displayName || null,
      });
      this.selectedCustomer.set({
        accountId,
        label: submission.displayName || submission.phone,
      });
      this.nonContactableNotice.set(true);
      this.createDialogOpen.set(false);
    } catch (error) {
      this.createError.set(
        error instanceof ApiError
          ? describeApiError(error, (key, values) => this.i18n.t(key, values))
          : this.i18n.t('error.unknown.noReference'),
      );
    } finally {
      this.createBusy.set(false);
    }
  }

  protected async toggleHistory(): Promise<void> {
    const selected = this.selectedCustomer();
    const scope = this.location.scope();
    if (!selected || !scope) {
      return;
    }
    const next = !this.historyOpen();
    this.historyOpen.set(next);
    if (!next || this.historyOrders().length > 0) {
      return;
    }
    this.historyLoading.set(true);
    try {
      const page = await this.customersApi.ordersPage(scope, selected.accountId, firstPage(5));
      this.historyOrders.set(page.items);
    } catch {
      // orders.md §5.3 point 4 is a convenience peek, not the record of
      // truth — a failed read leaves the popover honestly empty rather than
      // blocking the screen the operator is trying to finish a call on.
      this.historyOrders.set([]);
    } finally {
      this.historyLoading.set(false);
    }
  }

  protected formatHistoryTotal(order: CustomerOrderSummary): string {
    return formatMoney(
      { amountMinor: order.totalMinor, currency: order.currency },
      this.i18n.locale(),
    );
  }

  // ------------------------------------------------------------ §5.5 menu/basket

  protected readonly itemQuery = signal('');
  protected readonly itemOptions = signal<readonly ComboboxOption[]>([]);
  protected readonly itemSearching = signal(false);
  protected readonly itemSearchError = signal<string | null>(null);

  protected readonly basket = signal<readonly BasketLine[]>([]);
  protected readonly pendingModifiers = signal<PendingModifierSelection | null>(null);

  protected onItemQueryChange(value: string): void {
    this.itemQuery.set(value);
  }

  protected async onItemSearch(query: string): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.itemSearching.set(true);
    this.itemSearchError.set(null);
    try {
      const page = await this.api.searchItems(scope, firstPage(20), query, this.menuLocale());
      this.itemOptions.set(
        page.items.map((row) => ({
          id: row.variantId,
          label: row.productName ?? row.variantId,
          sublabel: row.available
            ? row.category
            : `${row.category ? row.category + ' · ' : ''}${this.i18n.t('orders.newOrder.menu.stopped')}`,
        })),
      );
    } catch (error) {
      this.itemSearchError.set(
        error instanceof ApiError
          ? describeApiError(error, (key, values) => this.i18n.t(key, values))
          : this.i18n.t('error.unknown.noReference'),
      );
    } finally {
      this.itemSearching.set(false);
    }
  }

  protected onItemSelected(option: ComboboxOption): void {
    const found = this.variantIndex().get(option.id);
    if (!found) {
      // The menu had not loaded yet, or loaded after the search result did —
      // orders.md §5.5 has no seam for adding an item this screen cannot
      // price, so the honest answer is to say so rather than guess a price.
      this.itemSearchError.set(this.i18n.t('orders.newOrder.menu.unpricedResult'));
      return;
    }
    this.selectVariant(found.product, found.variant);
    this.itemQuery.set('');
    this.itemOptions.set([]);
  }

  protected selectProductFromGrid(product: MenuProduct): void {
    const defaultVariant =
      product.variants.find((variant) => variant.isDefault) ?? product.variants[0];
    if (!defaultVariant) {
      return;
    }
    this.selectVariant(product, defaultVariant);
  }

  private selectVariant(product: MenuProduct, variant: MenuVariant): void {
    if (!variant.orderable) {
      this.toasts.show({ message: this.i18n.t('orders.newOrder.menu.itemStopped'), tone: 'error' });
      return;
    }
    const groups = this.modifierGroupsFor(product);
    if (groups.length === 0) {
      this.addToBasket(product, variant, []);
      return;
    }
    this.pendingModifiers.set({ product, variant, groups });
  }

  protected onModifierConfirm(confirmation: ModifierDialogConfirmation): void {
    const pending = this.pendingModifiers();
    if (!pending) {
      return;
    }
    this.addToBasket(pending.product, pending.variant, confirmation.selections);
    this.pendingModifiers.set(null);
  }

  protected onModifierDismiss(): void {
    this.pendingModifiers.set(null);
  }

  private addToBasket(
    product: MenuProduct,
    variant: MenuVariant,
    modifiers: BasketLine['modifiers'],
  ): void {
    const line: BasketLine = {
      lineKey: nextLineKey(),
      variantId: variant.variantId,
      productName: product.name,
      quantity: 1,
      unitAmountMinor: variant.amountMinor,
      modifiers,
      customerNote: null,
      orderable: variant.orderable,
    };
    this.basket.set([...this.basket(), line]);
  }

  protected setLineQuantity(lineKey: string, quantity: number): void {
    this.basket.set(
      this.basket().map((line) => (line.lineKey === lineKey ? { ...line, quantity } : line)),
    );
  }

  protected setLineNote(lineKey: string, note: string): void {
    this.basket.set(
      this.basket().map((line) =>
        line.lineKey === lineKey
          ? { ...line, customerNote: note.trim() === '' ? null : note }
          : line,
      ),
    );
  }

  protected removeLine(lineKey: string): void {
    this.basket.set(this.basket().filter((line) => line.lineKey !== lineKey));
  }

  protected readonly total = computed(() =>
    computeBasketTotal(this.basket(), this.menu()?.currency ?? null),
  );

  protected formattedTotal(): string {
    const total = this.total();
    if (total.currency === null) {
      return '—';
    }
    return formatMoney(
      { amountMinor: total.subtotalMinor, currency: total.currency },
      this.i18n.locale(),
    );
  }

  // ------------------------------------------------------------------ §5.6 submit

  protected readonly submitting = signal(false);
  protected readonly submitError = signal<string | null>(null);
  protected readonly submitDenied = signal(false);
  protected readonly unavailableItemIds = signal<readonly string[]>([]);

  protected readonly canSubmit = computed(
    () =>
      this.basket().length > 0 &&
      this.selectedCustomer() !== null &&
      this.total().allAvailable &&
      !this.submitting(),
  );

  protected async submit(): Promise<void> {
    const scope = this.location.scope();
    const customer = this.selectedCustomer();
    if (!scope || !customer || !this.canSubmit()) {
      return;
    }
    this.submitting.set(true);
    this.submitError.set(null);
    this.unavailableItemIds.set([]);
    try {
      const lines: PlaceOrderLine[] = this.basket().map((line) => ({
        variantId: line.variantId,
        quantity: line.quantity,
        modifierOptionIds: flattenModifiers(line),
        customerNote: line.customerNote,
      }));
      const result = await this.api.placeOrder(scope, {
        customerAccountId: customer.accountId,
        channelCode: this.channelCode(),
        fulfillmentMode: 'PICKUP',
        lines,
        paymentMethodCode: 'CASH',
      });
      if (this.callEventId) {
        try {
          await this.api.recordCallProvenance(scope, result.orderId, this.callEventId);
        } catch {
          // The order already exists and is worth keeping either way — a
          // lost provenance link is an operator-KPI gap, not a reason to
          // treat an order that already succeeded as a failure.
        }
      }
      this.toasts.show({
        message: this.i18n.t('orders.newOrder.order.created', { number: result.publicOrderNumber }),
        tone: 'success',
      });
      void this.router.navigate(['/orders', result.orderId]);
    } catch (error) {
      if (error instanceof ApiError) {
        const refusal = accessRefusal(error);
        if (refusal?.kind === 'denied') {
          this.submitDenied.set(true);
        } else {
          const unavailable = error.problem?.['unavailableItems'];
          if (Array.isArray(unavailable)) {
            this.unavailableItemIds.set(
              unavailable.filter((id): id is string => typeof id === 'string'),
            );
          }
          this.submitError.set(describeApiError(error, (key, values) => this.i18n.t(key, values)));
        }
      } else {
        this.submitError.set(this.i18n.t('error.unknown.noReference'));
      }
    } finally {
      this.submitting.set(false);
    }
  }

  protected cancel(): void {
    void this.router.navigate(['/orders']);
  }
}

/** Repeats an option's id once per selected quantity — the exact shape `CartService#requireSelectionRules` counts against. */
function flattenModifiers(line: BasketLine): readonly string[] {
  const ids: string[] = [];
  for (const modifier of line.modifiers) {
    for (let i = 0; i < modifier.quantity; i += 1) {
      ids.push(modifier.optionId);
    }
  }
  return ids;
}
