import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnInit,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';

import { LocationScope } from '../../../core/api/operations-paths';
import { firstPage } from '../../../core/api/page';
import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { formatMoney } from '../../../core/format/money';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { Combobox, ComboboxOption } from '../../../shared/ui/combobox';
import { DeniedState } from '../../../shared/ui/denied-state';
import { MoneyInput } from '../../../shared/ui/money-input';
import { NumberStepper } from '../../../shared/ui/number-stepper';
import { Toasts } from '../../../shared/ui/toast';
import {
  CreateCustomerDialog,
  CreateCustomerSubmission,
} from '../../customers/create-customer-dialog';
import {
  CustomerAddressFields,
  CustomerCoordinateSource,
  CustomerOrderSummary,
  CustomersApi,
  ReorderPlan,
  RevealedCustomerAddress,
} from '../../customers/customers-api';
import { ChannelView, SalesChannelsApi } from '../../settings/sales-channels/sales-channels-api';
import { accessRefusal, describeApiError } from '../order-errors';
import { ItemModifierDialog, ModifierDialogConfirmation } from './item-modifier-dialog';
import {
  AggregatorOrderLine,
  CommentPresetOption,
  CustomerLookupCandidate,
  DeliveryFeeQuote,
  MenuCategory,
  MenuModifierGroup,
  MenuProduct,
  MenuVariant,
  NewOrderApi,
  PlaceOrderLine,
  PlaceOrderRequest,
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

/**
 * The inline «+ новый адрес» form's own draft shape (row 1.3b) — every
 * field a plain string, the same reason `customer-detail-pane.ts`'s own
 * `AddressFormState` is: coercing an empty required field to `null` mid-edit
 * would fight `CustomerAddressFields`'s own type. Not imported from that
 * file: it is private there, and duplicating four fields locally is cheaper
 * than exporting another component's internal draft shape.
 */
interface AddressDraft {
  readonly line1: string;
  readonly city: string;
  readonly district: string;
  readonly entrance: string;
  readonly floor: string;
  readonly apartment: string;
  readonly landmark: string;
}

const EMPTY_ADDRESS_DRAFT: AddressDraft = {
  line1: '',
  city: '',
  district: '',
  entrance: '',
  floor: '',
  apartment: '',
  landmark: '',
};

/**
 * `NOT_GEOCODED` when the operator gave no landmark, `LANDMARK_ONLY`
 * otherwise — the two coordinate-free sources this screen can honestly
 * claim, since the pin and the geocoder are deferred behind `X.4` (row
 * 1.3b). Never `GEOCODER`/`*_PIN`/`LEGACY_UNSOURCED`: those claim a point
 * this form has no way to attach.
 */
function coordinateSourceFor(draft: AddressDraft): CustomerCoordinateSource {
  return draft.landmark.trim() === '' ? 'NOT_GEOCODED' : 'LANDMARK_ONLY';
}

/** The identical keys `customer-detail-pane.ts`'s own `COORDINATE_SOURCE_LABEL_KEYS` already registers. */
const COORDINATE_SOURCE_LABEL_KEYS: Record<CustomerCoordinateSource, MessageKey> = {
  NOT_GEOCODED: 'customers.address.coordinateSource.NOT_GEOCODED',
  LANDMARK_ONLY: 'customers.address.coordinateSource.LANDMARK_ONLY',
  GEOCODER: 'customers.address.coordinateSource.GEOCODER',
  CUSTOMER_PIN: 'customers.address.coordinateSource.CUSTOMER_PIN',
  OPERATOR_PIN: 'customers.address.coordinateSource.OPERATOR_PIN',
  LEGACY_UNSOURCED: 'customers.address.coordinateSource.LEGACY_UNSOURCED',
};

function toAddressFields(draft: AddressDraft): CustomerAddressFields {
  return {
    line1: draft.line1.trim(),
    city: draft.city.trim(),
    district: draft.district.trim(),
    entrance: draft.entrance.trim() || null,
    floor: draft.floor.trim() || null,
    apartment: draft.apartment.trim() || null,
    landmark: draft.landmark.trim() || null,
  };
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
 * **Wave P14 adds** the address pane's structured half (§5.4, row `1.3b`):
 * saved addresses through the already-wired `CUSTOMER_PII_REVEAL` reveal, an
 * inline «+ новый адрес» reusing `customer-detail-pane.html`'s own
 * дом/квартира/подъезд/этаж/ориентир fields, saved `NOT_GEOCODED` or
 * `LANDMARK_ONLY` — the pin, the suggest and the geocoder stay deferred
 * behind `X.4`. Payment now reads the operator channel's own matrix instead
 * of a hard-coded CASH (row `1.3e`; see `OperatorOrderingService`'s own
 * doc), promo code is threaded to `CartService.applyPromoCode` (ADR 0072),
 * and change-due is a live client-side computation only — `cash_tendered_expected_minor`
 * is still written after creation through an amendment (`SET_CASH_TENDERED`,
 * deferred), not at creation, so nothing here persists it. «Повторить»
 * (row `1.3f`) calls the staff reorder-plan wrapper and adds every
 * `AVAILABLE` line straight to the basket. A «Заказ агрегатора»
 * toggle (row `1.3g`) records an aggregator's own phoned-through order under
 * its `AGGREGATOR`-type channel with externally-set totals, bypassing the
 * customer pane entirely — ADR 0040 is explicit that a marketplace order
 * never matches a customer account.
 *
 * **This wave (rows 1.3a, 1.3d, plus what row 1.3's own gap-map text still
 * called unbuilt).** Create-on-miss ({@link onCreateSubmit}) now calls {@link
 * NewOrderApi#createCustomer}, a location-scoped endpoint, instead of {@link
 * CustomersApi#create}'s tenant-scoped one — the latter 403'd for
 * LOCATION_STAFF/LOCATION_MANAGER, this screen's own persona, because their
 * grant is at LOCATION scope and that endpoint is declared at TENANT scope
 * with nowhere in its own path for a location to come from (see
 * `OperationsCustomerController`'s own doc for the full account). A «Позже»
 * toggle asks for a promise time instead of now
 * (`requestedFor`); the backend validates it against the branch's own hours
 * and refuses `BRANCH_CLOSED_AT_REQUESTED_TIME_CONFIRM` when it is closed
 * then, which this screen renders as an inline "place anyway?" confirmation
 * rather than a hard error, and `BRANCH_CLOSED_AT_REQUESTED_TIME` (no
 * confirmation offered) when the branch's own policy refuses a pre-order into
 * that slot at all. A delivery order's running total now previews the
 * delivery fee itself (`NewOrderApi.deliveryFeeQuote`, the same unauthenticated
 * preview the storefront's own cart already calls) whenever the chosen address
 * carries a real coordinate — most operator-entered addresses do not yet (row
 * 1.3b's pin is deferred behind X.4), so the preview honestly reads "—" for
 * those rather than a guessed number.
 *
 * <p>**Still not built, honestly.** No map pin, no address suggest, no
 * out-of-brand branch resolution (this screen stays scoped to
 * `CurrentLocation`; "Филиал" shows the current branch with a static «по
 * зоне» caption on a delivery order rather than a real cross-branch
 * resolver). No lead-time limit, repricing checkpoint or payment-authorization
 * timing for a long-lead pre-order — ADR 0019 leaves that policy open, and
 * `requestedFor` only ever asks "is the branch open then", never holds a
 * price or a slot for the wait. The header's draft timer and quote-expiry
 * states (§5.7) still do not apply to this backend shape at all:
 * `OperatorOrderingService.place` opens the cart, prices it and checks out
 * in one atomic call, so there is no server-side draft cart that can expire
 * out from under the operator the way a storefront cart can.
 *
 * <p><b>Wave 10 (rows 1.3f/1.3a).</b> «Повторить» used to call
 * `CustomerOrderHistoryController.reorderPlan` (`ORDER_READ` at `BRAND`
 * scope), which `LOCATION_STAFF` — this screen's own persona — does not
 * hold, 403ing every time. `CustomersApi#reorderPlan` now calls
 * `CustomerOrderReorderController` instead (`operationsPaths.customerOrderReorder`,
 * `ORDER_READ` at `LOCATION`), resolved against {@link CurrentLocation}'s
 * own branch rather than the order's original one — the same honest "this
 * branch, right now" rule §5.4's address pane and §5.6's menu already
 * apply. The Customers section's own order-history tab keeps reading the
 * brand-scoped wrapper for its wider, `LOCATION_MANAGER`-or-broader
 * audience; only this screen's button moved.
 *
 * **Row 2.1b/4.2g (this wave).** `q-item-modifier-dialog` now also offers a
 * product's coded comment presets — see its own doc — carried onto the
 * placed line as `commentPresetCodes` and rendered on the order detail and
 * kitchen ticket the same order creates. Selecting a variant outside its own
 * sale window is refused client-side ({@link selectVariant}) the same way an
 * 86'd one already was; a window that closes after the line was added is
 * caught server-side at `Создать` and shown through {@link
 * describeDeliveryRefusal} rather than silently dropping the line.
 */
@Component({
  selector: 'q-new-order-page',
  imports: [
    TPipe,
    Combobox,
    NumberStepper,
    CreateCustomerDialog,
    ItemModifierDialog,
    DeniedState,
    MoneyInput,
  ],
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

  /**
   * Row 5.2d: set when the customer detail pane's own «Повторить» sent the
   * operator here (`customer-detail-pane.ts`'s `reorder`) — both read
   * together in {@link ngOnInit} to pre-select the customer and resolve the
   * same reorder plan the history popover's own {@link reorder} calls, so
   * the operator lands with the basket already filled.
   */
  private readonly reorderAccountId = this.route.snapshot.queryParamMap.get('reorderAccountId');
  private readonly reorderOrderId = this.route.snapshot.queryParamMap.get('reorderOrderId');

  // ------------------------------------------------------------- bootstrap

  protected readonly locationDenied = signal(false);
  protected readonly menu = signal<StorefrontMenu | null>(null);
  protected readonly menuLoading = signal(true);
  protected readonly menuError = signal<string | null>(null);
  protected readonly channelCode = signal<string>(FALLBACK_OPERATOR_CHANNEL_CODE);
  /** Every channel this tenant has, so row 1.3g's aggregator picker and the §5.4 branch label both read one fetch. */
  protected readonly channels = signal<readonly ChannelView[]>([]);

  /**
   * §5.4/§5.6's "Филиал" — always the current session's own branch: this
   * screen has no cross-branch order creation, so there is nothing here to
   * resolve away from it. See this class's own doc for why «по зоне» is a
   * static caption rather than a real ADR 0037 resolver.
   */
  protected readonly currentLocationName = computed(() => {
    const scope = this.location.scope();
    return (
      this.location.options().find((option) => option.id === scope?.locationId)?.displayName ?? ''
    );
  });

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
    this.channels.set(channels);
    const callCentre = channels.find(
      (channel) => channel.systemType === 'CALL_CENTRE' && channel.status === 'ACTIVE',
    );
    this.channelCode.set(callCentre?.code ?? FALLBACK_OPERATOR_CHANNEL_CODE);
    void this.loadPaymentMethods(scope, callCentre);

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

    if (this.reorderAccountId && this.reorderOrderId) {
      await this.bootstrapReorder(scope, this.reorderAccountId, this.reorderOrderId);
    } else {
      queueMicrotask(() => this.phoneInput()?.nativeElement.focus());
    }
  }

  /**
   * Row 5.2d: pre-selects the customer the detail pane's own «Повторить»
   * named, then resolves the same reorder plan the history popover's own
   * {@link reorder} does — the operator lands here with the basket already
   * filled rather than retyping it from the phone call.
   */
  private async bootstrapReorder(
    scope: LocationScope,
    accountId: string,
    orderId: string,
  ): Promise<void> {
    try {
      const profile = await this.customersApi.profile(scope, accountId);
      this.selectedCustomer.set({
        accountId,
        label: profile.value.displayName ?? this.i18n.t('orders.newOrder.customer.unnamed'),
      });
    } catch {
      // The account may have been merged or erased since the link was made;
      // an empty customer picker lets the operator look the caller up again
      // by phone rather than blocking the whole screen on a stale deep link.
      return;
    }
    await this.applyReorderPlan(accountId, orderId);
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

  /** Row 2.1b: every offered preset across the whole menu, by code — a code means the same preset on every product that offers it. */
  private readonly commentPresetIndex = computed(() => {
    const index = new Map<string, CommentPresetOption>();
    for (const product of this.menu()?.products ?? []) {
      for (const preset of product.commentPresets) {
        index.set(preset.code, preset);
      }
    }
    return index;
  });

  /** The console's own locale label for a checked preset code, matching `item-modifier-dialog.ts`'s own `presetLabel`. */
  private presetLabel(preset: CommentPresetOption): string {
    switch (this.i18n.locale()) {
      case 'ru':
        return preset.labelRu;
      case 'uz-Latn':
        return preset.labelUz;
      default:
        return preset.labelEn;
    }
  }

  /** «Без лука, Поострее» — a basket line's checked presets, resolved to the console's own locale. */
  protected presetSummary(line: BasketLine): string {
    const index = this.commentPresetIndex();
    return line.commentPresetCodes
      .map((code) => {
        const preset = index.get(code);
        return preset ? this.presetLabel(preset) : code;
      })
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
      // Row 1.3a: the location-scoped create, not `customersApi.create` —
      // that one posts to the tenant-scoped `CustomerController`, which
      // 403s for this screen's own LOCATION_STAFF/LOCATION_MANAGER persona.
      // See `NewOrderApi.createCustomer`'s own doc.
      const accountId = await this.api.createCustomer(scope, {
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
      // Row 1.3f/1.3a (major fix): the LOCATION-scoped route, not `ordersPage`
      // — this screen's primary persona, LOCATION_STAFF, holds ORDER_READ
      // only at LOCATION scope and 403s against the BRAND-scoped one, the
      // same reason `reorder` below already calls `reorderPlan`'s own
      // LOCATION-scoped route rather than the Customers section's twin.
      const page = await this.customersApi.ordersPageAtLocation(
        scope,
        selected.accountId,
        firstPage(5),
      );
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

  // ------------------------------------------------------------------ §5.3 «Повторить»

  protected readonly reorderBusy = signal<string | null>(null);
  protected readonly reorderError = signal<string | null>(null);

  /**
   * Row 1.3f: the resolved repeat plan, staffed through the wrapper this
   * wave adds (`CustomerOrderHistoryController.reorderPlan`). `PARTIAL` is
   * offered too — only the plan's own `AVAILABLE` lines are added, so a
   * customer's usual order with one withdrawn dish still repeats the rest
   * rather than refusing the whole basket.
   */
  protected async reorder(order: CustomerOrderSummary): Promise<void> {
    const selected = this.selectedCustomer();
    if (!selected || this.reorderBusy() !== null) {
      return;
    }
    await this.applyReorderPlan(selected.accountId, order.orderId);
  }

  /**
   * The shared body {@link reorder} and row 5.2d's own deep-link bootstrap
   * (`ngOnInit`, `reorderAccountId`/`reorderOrderId`) both call — the only
   * difference between the two call sites is where the order id comes from.
   */
  private async applyReorderPlan(accountId: string, orderId: string): Promise<void> {
    const scope = this.location.scope();
    if (!scope || this.reorderBusy() !== null) {
      return;
    }
    this.reorderBusy.set(orderId);
    this.reorderError.set(null);
    try {
      const plan: ReorderPlan | null = await this.customersApi.reorderPlan(
        scope,
        accountId,
        orderId,
      );
      if (!plan || plan.verdict === 'UNAVAILABLE') {
        this.reorderError.set(this.i18n.t('orders.newOrder.reorder.unavailable'));
        return;
      }
      const available = plan.lines.filter((line) => line.status === 'AVAILABLE');
      const added: BasketLine[] = available.map((line) => ({
        lineKey: nextLineKey(),
        variantId: line.variantId,
        productName: line.productName,
        quantity: line.quantity,
        unitAmountMinor: line.unitAmountMinor,
        modifiers: [],
        // Row 2.1b: `ReorderPlan`'s own line carries no preset codes — a
        // repeat order starts from the product's plain state, same as it
        // already drops the original line's modifiers above.
        commentPresetCodes: [],
        customerNote: null,
        orderable: true,
        // Row 4.2g: `plan.verdict`/`line.status` answer whether the item
        // still exists to reorder, not whether its own sale schedule
        // currently excludes it — `submit`'s server-side check is what
        // actually catches that, the same as every other line here.
        onSaleNow: true,
      }));
      this.basket.set([...this.basket(), ...added]);
      this.historyOpen.set(false);
      if (plan.verdict === 'PARTIAL') {
        this.reorderError.set(this.i18n.t('orders.newOrder.reorder.partial'));
      }
    } catch (error) {
      this.reorderError.set(
        error instanceof ApiError
          ? describeApiError(error, (key, values) => this.i18n.t(key, values))
          : this.i18n.t('error.unknown.noReference'),
      );
    } finally {
      this.reorderBusy.set(null);
    }
  }

  // ---------------------------------------------------------------- §5.4 address pane

  protected readonly fulfillmentMode = signal<'PICKUP' | 'DELIVERY'>('PICKUP');

  protected readonly addresses = signal<readonly RevealedCustomerAddress[]>([]);
  protected readonly addressesLoading = signal(false);
  protected readonly addressesError = signal<string | null>(null);
  protected readonly addressesDenied = signal(false);
  protected readonly selectedAddressId = signal<string | null>(null);
  protected readonly recipientName = signal('');
  protected readonly recipientPhone = signal('');
  protected readonly deliveryNote = signal('');

  protected readonly addingAddress = signal(false);
  protected readonly addressSaving = signal(false);
  protected readonly addressError = signal<string | null>(null);
  protected readonly addressLabel = signal('');
  protected readonly addressDraft = signal<AddressDraft>(EMPTY_ADDRESS_DRAFT);

  protected readonly selectedAddress = computed(
    () => this.addresses().find((address) => address.id === this.selectedAddressId()) ?? null,
  );

  /**
   * Whether the currently chosen address has no pin — `setDestination`
   * refuses `DESTINATION_NOT_LOCATED` for exactly this case (the pin, the
   * suggest and the geocoder are deferred behind `X.4`), so this is shown as
   * an upfront, non-blocking notice rather than letting the operator fill
   * the whole basket before finding out at submit time.
   */
  protected readonly selectedAddressUnlocated = computed(() => {
    const address = this.selectedAddress();
    return (
      address !== null &&
      (address.coordinateSource === 'NOT_GEOCODED' ||
        address.coordinateSource === 'LANDMARK_ONLY' ||
        address.coordinateSource === 'LEGACY_UNSOURCED')
    );
  });

  /** Same two keys `order-queue.ts`'s own `fulfillmentModeLabel` already reads — a dynamically built key does not typecheck against `MessageKey`. */
  protected fulfillmentModeLabel(mode: 'PICKUP' | 'DELIVERY'): string {
    return mode === 'DELIVERY'
      ? this.i18n.t('orders.fulfillmentMode.DELIVERY')
      : this.i18n.t('orders.fulfillmentMode.PICKUP');
  }

  protected setFulfillmentMode(mode: 'PICKUP' | 'DELIVERY'): void {
    this.fulfillmentMode.set(mode);
    if (
      mode === 'DELIVERY' &&
      this.selectedCustomer() &&
      this.addresses().length === 0 &&
      !this.addressesLoading() &&
      !this.addressesDenied()
    ) {
      void this.loadAddresses();
    }
  }

  private async loadAddresses(): Promise<void> {
    const selected = this.selectedCustomer();
    const scope = this.location.scope();
    if (!selected || !scope) {
      return;
    }
    this.addressesLoading.set(true);
    this.addressesError.set(null);
    try {
      const list = await this.customersApi.revealAddresses(
        scope,
        selected.accountId,
        'Operations console: New order screen delivery address (row 1.3b)',
      );
      this.addresses.set(list);
      if (list.length > 0 && this.selectedAddressId() === null) {
        this.selectedAddressId.set(list[0].id);
      }
    } catch (error) {
      if (error instanceof ApiError && accessRefusal(error)?.kind === 'denied') {
        // LOCATION_STAFF does not hold CUSTOMER_PII_REVEAL (see PlatformRole's
        // own comment) — an open capability-model gap this wave flags rather
        // than silently patches by widening a role bundle it was not asked
        // to touch, the identical posture this file's own channel-list
        // fallback already takes.
        this.addressesDenied.set(true);
      } else {
        this.addressesError.set(
          error instanceof ApiError
            ? describeApiError(error, (key, values) => this.i18n.t(key, values))
            : this.i18n.t('error.unknown.noReference'),
        );
      }
    } finally {
      this.addressesLoading.set(false);
    }
  }

  protected selectAddress(addressId: string): void {
    this.selectedAddressId.set(addressId);
    this.addingAddress.set(false);
  }

  protected startAddingAddress(): void {
    this.addingAddress.set(true);
    this.addressLabel.set('');
    this.addressDraft.set(EMPTY_ADDRESS_DRAFT);
    this.addressError.set(null);
  }

  protected cancelAddingAddress(): void {
    this.addingAddress.set(false);
  }

  protected setAddressField(field: keyof AddressDraft, value: string): void {
    this.addressDraft.update((current) => ({ ...current, [field]: value }));
  }

  /**
   * Saves with `coordinateSource: NOT_GEOCODED` or `LANDMARK_ONLY` — never a
   * pin, which this screen has no way to place (row 1.3b, deferred `X.4`).
   */
  protected async saveNewAddress(): Promise<void> {
    const selected = this.selectedCustomer();
    const scope = this.location.scope();
    if (!selected || !scope || this.addressSaving()) {
      return;
    }
    this.addressSaving.set(true);
    this.addressError.set(null);
    try {
      const draft = this.addressDraft();
      const { id } = await this.customersApi.addAddress(scope, selected.accountId, {
        label: this.addressLabel().trim(),
        fields: toAddressFields(draft),
        coordinateSource: coordinateSourceFor(draft),
      });
      await this.loadAddresses();
      this.selectedAddressId.set(id);
      this.addingAddress.set(false);
    } catch (error) {
      this.addressError.set(
        error instanceof ApiError
          ? describeApiError(error, (key, values) => this.i18n.t(key, values))
          : this.i18n.t('error.unknown.noReference'),
      );
    } finally {
      this.addressSaving.set(false);
    }
  }

  protected coordinateSourceLabel(source: CustomerCoordinateSource): string {
    return this.i18n.t(COORDINATE_SOURCE_LABEL_KEYS[source]);
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
    // Row 4.2g: the same client-side mirror of the server rule `orderable`
    // above already gets — refused before a dialog ever opens, distinct from
    // 86'd (see `MenuVariant.onSaleNow`'s own doc).
    if (!variant.onSaleNow) {
      this.toasts.show({
        message: this.i18n.t('orders.newOrder.menu.itemOutOfSaleWindow'),
        tone: 'error',
      });
      return;
    }
    const groups = this.modifierGroupsFor(product);
    if (groups.length === 0 && product.commentPresets.length === 0) {
      this.addToBasket(product, variant, [], []);
      return;
    }
    this.pendingModifiers.set({ product, variant, groups });
  }

  protected onModifierConfirm(confirmation: ModifierDialogConfirmation): void {
    const pending = this.pendingModifiers();
    if (!pending) {
      return;
    }
    this.addToBasket(
      pending.product,
      pending.variant,
      confirmation.selections,
      confirmation.commentPresetCodes,
    );
    this.pendingModifiers.set(null);
  }

  protected onModifierDismiss(): void {
    this.pendingModifiers.set(null);
  }

  private addToBasket(
    product: MenuProduct,
    variant: MenuVariant,
    modifiers: BasketLine['modifiers'],
    commentPresetCodes: readonly string[],
  ): void {
    const line: BasketLine = {
      lineKey: nextLineKey(),
      variantId: variant.variantId,
      productName: product.name,
      quantity: 1,
      unitAmountMinor: variant.amountMinor,
      modifiers,
      commentPresetCodes,
      customerNote: null,
      orderable: variant.orderable,
      onSaleNow: variant.onSaleNow,
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

  // -------------------------------------------------------- §5.6 payment, promo

  /**
   * Row 1.3e: read from the operator channel's own matrix
   * (`SalesChannelsApi.matrices`, `CHANNEL_READ`) rather than hard-coding
   * `['CASH']`. Falls back to cash-only on a denied read — the same
   * capability-model gap this file's own channel-code fallback already
   * documents — so the screen still offers something rather than nothing.
   */
  protected readonly paymentMethods = signal<readonly string[]>(['CASH']);
  protected readonly paymentMethodCode = signal('CASH');
  protected readonly promoCode = signal('');
  /** Whole som — UZS carries no minor unit (`q-money-input`'s own doc). */
  protected readonly cashTenderedMinor = signal(0);

  private async loadPaymentMethods(
    scope: LocationScope,
    channel: ChannelView | undefined,
  ): Promise<void> {
    if (!channel) {
      return;
    }
    try {
      const matrices = await this.channelsApi.matrices(scope, channel.id);
      const enabled = Object.entries(matrices.paymentMethods)
        .filter(([, isEnabled]) => isEnabled)
        .map(([code]) => code)
        .sort();
      if (enabled.length > 0) {
        this.paymentMethods.set(enabled);
        if (!enabled.includes(this.paymentMethodCode())) {
          this.paymentMethodCode.set(enabled[0]);
        }
      }
    } catch {
      // CHANNEL_READ is the same gap already documented above this class's
      // own channel-code resolution — the cash-only default this signal
      // already carries stands, and the operator can still take an order.
    }
  }

  /**
   * The raw code, not a translated label: `CartPaymentOptions`'s own doc
   * says why — "codes rather than labels... a per-tenant naming registry",
   * and nothing on this screen's call path reaches it yet. Honest and
   * unambiguous beats a wrong guess at what CLICK or PAYME should read as.
   */
  protected paymentMethodLabel(code: string): string {
    return code;
  }

  /**
   * The order pane's live «Сдача» (orders.md §5.6) — a client-side
   * convenience only. `cash_tendered_expected_minor` is still captured after
   * creation through `SET_CASH_TENDERED` (ADR 0039), not at creation, so
   * nothing here is sent to the server; see this class's own doc for why.
   */
  protected readonly changeDueMinor = computed(() => {
    const tendered = this.cashTenderedMinor();
    const total = this.total();
    if (tendered <= 0 || total.currency === null) {
      return null;
    }
    return tendered - total.subtotalMinor;
  });

  protected formattedChangeDue(): string | null {
    const changeMinor = this.changeDueMinor();
    const currency = this.total().currency;
    if (changeMinor === null || currency === null) {
      return null;
    }
    return formatMoney({ amountMinor: Math.max(changeMinor, 0), currency }, this.i18n.locale());
  }

  // ------------------------------------------------------ §5.4/§5.6 delivery fee preview

  /**
   * Row 1.3: a preview only, best-effort, never sent back to the server —
   * the fee that actually settles is resolved fresh inside the checkout
   * transaction from the destination `submit()` sets, the same "preview vs
   * authority" split `ui-cart.service.ts`'s own `deliveryFeeQuote` doc
   * describes for the storefront's cart.
   */
  protected readonly deliveryFeeQuote = signal<DeliveryFeeQuote | null>(null);
  protected readonly deliveryFeeLoading = signal(false);

  private deliveryFeeTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    // Re-quotes on every fulfilment-mode, address or basket-subtotal change
    // while a delivery destination with a real coordinate is selected;
    // debounced the same way `onPhoneInput` already is, so a quantity
    // stepper click does not fire one request per click. `selectedAddress`
    // and `total` are declared later in this class — safe to read here
    // because every field initialiser runs before this constructor body
    // does, regardless of declaration order.
    effect(() => {
      const mode = this.fulfillmentMode();
      const address = this.selectedAddress();
      const currency = this.total().currency;
      const subtotalMinor = this.total().subtotalMinor;

      if (this.deliveryFeeTimer !== null) {
        clearTimeout(this.deliveryFeeTimer);
        this.deliveryFeeTimer = null;
      }
      if (
        mode !== 'DELIVERY' ||
        address === null ||
        address.latitude === null ||
        address.longitude === null ||
        currency === null
      ) {
        this.deliveryFeeQuote.set(null);
        return;
      }
      const lat = address.latitude;
      const lon = address.longitude;
      this.deliveryFeeTimer = setTimeout(() => {
        void this.refreshDeliveryFee(lat, lon, currency, subtotalMinor);
      }, 400);
    });
  }

  private async refreshDeliveryFee(
    lat: number,
    lon: number,
    currency: string,
    subtotalMinor: number,
  ): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.deliveryFeeLoading.set(true);
    try {
      const quote = await this.api.deliveryFeeQuote(scope, { lat, lon }, currency, subtotalMinor);
      this.deliveryFeeQuote.set(quote);
    } catch {
      // A preview, not the authority — see this class's own doc. A failed
      // read leaves the row honestly blank rather than blocking the basket.
      this.deliveryFeeQuote.set(null);
    } finally {
      this.deliveryFeeLoading.set(false);
    }
  }

  /** `—` while unlocated or unresolved, a translated refusal when the resolver refuses, the formatted fee otherwise. */
  protected formattedDeliveryFee(): string {
    if (this.deliveryFeeLoading()) {
      return this.i18n.t('orders.newOrder.order.deliveryFeeCalculating');
    }
    const quote = this.deliveryFeeQuote();
    const currency = this.total().currency;
    if (quote === null || currency === null) {
      return '—';
    }
    if (!quote.available || quote.feeMinor === null) {
      return this.i18n.t('orders.newOrder.order.deliveryFeeUnavailable');
    }
    return formatMoney({ amountMinor: quote.feeMinor, currency }, this.i18n.locale());
  }

  /** The goods total plus the previewed delivery fee — shown only once both are actually known. */
  protected formattedTotalWithDelivery(): string | null {
    const total = this.total();
    const quote = this.deliveryFeeQuote();
    if (total.currency === null || quote === null || !quote.available || quote.feeMinor === null) {
      return null;
    }
    return formatMoney(
      { amountMinor: total.subtotalMinor + quote.feeMinor, currency: total.currency },
      this.i18n.locale(),
    );
  }

  // -------------------------------------------------------- §5.6 pre-order time (1.3d)

  /** «Позже» — off by default, an ordinary order taken for now. */
  protected readonly preOrderEnabled = signal(false);
  /**
   * The `datetime-local` input's own string, read in the operator's browser
   * timezone. Uzbekistan has kept one offset since 1995 (`AGENTS.md`'s own
   * note on `ServiceabilityService`), so this is correct for every tenant
   * today; a tenant outside it would need the branch's own IANA zone threaded
   * through here instead of the browser's, which this screen does not do.
   */
  protected readonly requestedForLocal = signal('');
  protected readonly requestedForError = signal<string | null>(null);
  /**
   * Set once the backend has answered `BRANCH_CLOSED_AT_REQUESTED_TIME_CONFIRM`
   * — the branch's own policy allows a pre-order into a closed slot, but the
   * operator has not said yet that they mean this one. The value itself is
   * never shown; `problem.detail` is English and dev-facing by ADR 0031
   * convention (`ProblemDetails`'s own doc), so this only tracks that a
   * confirmation is pending, not what it says. A second {@link submit} call,
   * with this already set, sends `overrideOutOfHours: true`.
   */
  protected readonly outOfHoursConfirmReason = signal<string | null>(null);

  protected togglePreOrder(): void {
    this.preOrderEnabled.update((current) => !current);
    if (!this.preOrderEnabled()) {
      this.requestedForLocal.set('');
      this.requestedForError.set(null);
      this.outOfHoursConfirmReason.set(null);
    }
  }

  protected setRequestedForLocal(value: string): void {
    this.requestedForLocal.set(value);
    this.requestedForError.set(null);
    this.outOfHoursConfirmReason.set(null);
  }

  /** `null` when pre-order is off, or the input has nothing parseable yet. */
  private requestedForDate(): Date | null {
    if (!this.preOrderEnabled() || this.requestedForLocal().trim() === '') {
      return null;
    }
    const parsed = new Date(this.requestedForLocal());
    return Number.isNaN(parsed.getTime()) ? null : parsed;
  }

  protected outOfHoursConfirmMessage(): string | null {
    return this.outOfHoursConfirmReason() === null
      ? null
      : this.i18n.t('orders.newOrder.order.preOrder.confirmOutOfHours');
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
      (this.fulfillmentMode() === 'PICKUP' || this.selectedAddressId() !== null) &&
      (!this.preOrderEnabled() || this.requestedForLocal().trim() !== '') &&
      !this.submitting(),
  );

  protected async submit(): Promise<void> {
    const scope = this.location.scope();
    const customer = this.selectedCustomer();
    if (!scope || !customer || !this.canSubmit()) {
      return;
    }
    const delivery = this.fulfillmentMode() === 'DELIVERY';
    const addressId = this.selectedAddressId();
    if (delivery && addressId === null) {
      return;
    }
    const requestedForDate = this.requestedForDate();
    if (this.preOrderEnabled()) {
      if (requestedForDate === null) {
        return;
      }
      if (requestedForDate.getTime() <= Date.now()) {
        this.requestedForError.set(this.i18n.t('orders.newOrder.order.preOrder.mustBeFuture'));
        return;
      }
    }
    // A resubmit while a confirmation is pending is the operator's "yes,
    // place it anyway" — every other resubmit (a first attempt, or one after
    // the operator changed the requested time) starts from no override.
    const confirmingOutOfHours = this.outOfHoursConfirmReason() !== null;
    this.submitting.set(true);
    this.submitError.set(null);
    this.unavailableItemIds.set([]);
    try {
      const lines: PlaceOrderLine[] = this.basket().map((line) => ({
        variantId: line.variantId,
        quantity: line.quantity,
        modifierOptionIds: flattenModifiers(line),
        commentPresetCodes: line.commentPresetCodes,
        customerNote: line.customerNote,
      }));
      const request: PlaceOrderRequest = {
        customerAccountId: customer.accountId,
        channelCode: this.channelCode(),
        fulfillmentMode: this.fulfillmentMode(),
        lines,
        destination:
          delivery && addressId !== null
            ? {
                customerAddressId: addressId,
                recipientName: this.recipientName().trim() || customer.label,
                recipientPhone: this.recipientPhone().trim() || this.phone().trim(),
                deliveryNote: this.deliveryNote().trim() || null,
              }
            : null,
        paymentMethodCode: this.paymentMethodCode(),
        promoCode: this.promoCode().trim() || null,
        requestedFor: requestedForDate ? requestedForDate.toISOString() : null,
        overrideOutOfHours: confirmingOutOfHours,
      };
      const result = await this.api.placeOrder(scope, request);
      this.outOfHoursConfirmReason.set(null);
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
        const reason = error.problem?.['reason'];
        if (refusal?.kind === 'denied') {
          this.submitDenied.set(true);
        } else if (reason === 'REQUESTED_TIME_IN_PAST') {
          this.requestedForError.set(this.i18n.t('orders.newOrder.order.preOrder.mustBeFuture'));
        } else if (reason === 'BRANCH_CLOSED_AT_REQUESTED_TIME_CONFIRM') {
          // The warn half of "WARN ... while still allowing an explicit
          // override": not a submitError, a confirmation the operator can
          // accept by pressing submit again. The sentinel is never rendered —
          // see this signal's own doc for why.
          this.outOfHoursConfirmReason.set('PENDING');
        } else {
          if (reason === 'BRANCH_CLOSED_AT_REQUESTED_TIME') {
            this.outOfHoursConfirmReason.set(null);
          }
          const unavailable = error.problem?.['unavailableItems'];
          if (Array.isArray(unavailable)) {
            this.unavailableItemIds.set(
              unavailable.filter((id): id is string => typeof id === 'string'),
            );
          }
          this.submitError.set(this.describeDeliveryRefusal(error));
        }
      } else {
        this.submitError.set(this.i18n.t('error.unknown.noReference'));
      }
    } finally {
      this.submitting.set(false);
    }
  }

  /**
   * Row 1.3b's "out-of-zone shows the refusal reason_code in words": the
   * checkout refusals a delivery order can hit (`DESTINATION_NOT_LOCATED`,
   * `NOT_SERVICEABLE`) carry their code in `problem.reason` — see
   * `StorefrontOrderingController.refusal`/`errorCodeFor` — read here rather
   * than falling through to the generic ADR 0031 message map, which knows
   * nothing about either code. `REQUESTED_TIME_IN_PAST` and
   * `BRANCH_CLOSED_AT_REQUESTED_TIME_CONFIRM` (row 1.3d) are handled earlier,
   * in {@link submit} itself, because they set a different signal than this
   * one's plain string — only `BRANCH_CLOSED_AT_REQUESTED_TIME`, the hard
   * refusal with no confirmation to offer, reaches this method.
   *
   * Row 4.2g also lands here: `ITEM_OUT_OF_SALE_WINDOW`, a basket line whose
   * variant left its sale window in the gap between adding it and pressing
   * `Создать` — {@link selectVariant} refuses the same fact up front, this
   * is the race that check cannot see. The basket line stays exactly as the
   * operator left it; nothing here removes it.
   */
  private describeDeliveryRefusal(error: ApiError): string {
    const reason = error.problem?.['reason'];
    if (reason === 'DESTINATION_NOT_LOCATED') {
      return this.i18n.t('orders.newOrder.address.notLocated');
    }
    if (reason === 'NOT_SERVICEABLE') {
      return this.i18n.t('orders.newOrder.address.notServiceable');
    }
    if (reason === 'BRANCH_CLOSED_AT_REQUESTED_TIME') {
      return this.i18n.t('orders.newOrder.order.preOrder.closedNoOverride');
    }
    if (reason === 'ITEM_OUT_OF_SALE_WINDOW') {
      return this.i18n.t('orders.newOrder.menu.itemOutOfSaleWindow');
    }
    return describeApiError(error, (key, values) => this.i18n.t(key, values));
  }

  protected cancel(): void {
    void this.router.navigate(['/orders']);
  }

  // -------------------------------------------------------- §5.6 aggregator entry

  /** Row 1.3g: the tenant's registered AGGREGATOR-type channels, for the «Заказ агрегатора» picker. */
  protected readonly aggregatorChannels = computed(() =>
    this.channels().filter(
      (channel) => channel.systemType === 'AGGREGATOR' && channel.status === 'ACTIVE',
    ),
  );

  protected readonly aggregatorMode = signal(false);
  protected readonly aggregatorChannelCode = signal<string | null>(null);
  protected readonly aggregatorExternalOrderId = signal('');
  /** Whole som, as the aggregator itself stated them — never re-derived from the basket. */
  protected readonly aggregatorSubtotalMinor = signal(0);
  protected readonly aggregatorDiscountMinor = signal(0);
  protected readonly aggregatorFeeMinor = signal(0);
  protected readonly aggregatorTotalMinor = signal(0);
  protected readonly aggregatorSubmitting = signal(false);
  protected readonly aggregatorError = signal<string | null>(null);

  protected toggleAggregatorMode(): void {
    this.aggregatorMode.update((current) => !current);
    const first = this.aggregatorChannels()[0];
    if (this.aggregatorMode() && first && this.aggregatorChannelCode() === null) {
      this.aggregatorChannelCode.set(first.code);
    }
  }

  protected readonly canSubmitAggregator = computed(
    () =>
      this.basket().length > 0 &&
      this.total().allAvailable &&
      this.aggregatorChannelCode() !== null &&
      this.aggregatorExternalOrderId().trim() !== '' &&
      this.aggregatorTotalMinor() > 0 &&
      !this.aggregatorSubmitting(),
  );

  /**
   * Never runs the basket back through the HorecaOS quote — the total is
   * exactly what the aggregator typed in (ADR 0040). Line unit prices are
   * the catalogue's own, on the same reasoning `computeBasketTotal` already
   * uses: nobody re-typed the menu, only the order's own totals.
   */
  protected async submitAggregator(): Promise<void> {
    const scope = this.location.scope();
    const channelCode = this.aggregatorChannelCode();
    if (!scope || channelCode === null || !this.canSubmitAggregator()) {
      return;
    }
    this.aggregatorSubmitting.set(true);
    this.aggregatorError.set(null);
    try {
      const lines: AggregatorOrderLine[] = this.basket().map((line) => ({
        variantId: line.variantId,
        nameSnapshot: line.productName,
        quantity: line.quantity,
        unitAmountMinor: line.unitAmountMinor ?? 0,
      }));
      const result = await this.api.aggregatorEntry(scope, {
        channelCode,
        externalOrderId: this.aggregatorExternalOrderId().trim(),
        lines,
        currency: this.total().currency ?? 'UZS',
        subtotalMinor: this.aggregatorSubtotalMinor(),
        discountMinor: this.aggregatorDiscountMinor(),
        feeMinor: this.aggregatorFeeMinor(),
        totalMinor: this.aggregatorTotalMinor(),
      });
      this.toasts.show({
        message: this.i18n.t('orders.newOrder.order.created', { number: result.publicOrderNumber }),
        tone: 'success',
      });
      void this.router.navigate(['/orders', result.orderId]);
    } catch (error) {
      this.aggregatorError.set(
        error instanceof ApiError
          ? describeApiError(error, (key, values) => this.i18n.t(key, values))
          : this.i18n.t('error.unknown.noReference'),
      );
    } finally {
      this.aggregatorSubmitting.set(false);
    }
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
