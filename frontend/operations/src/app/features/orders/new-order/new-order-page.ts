import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnInit,
  computed,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { LocationScope } from '../../../core/api/operations-paths';
import { firstPage } from '../../../core/api/page';
import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { formatMoney } from '../../../core/format/money';
import { I18n } from '../../../core/i18n/i18n';
import { presetLabelFor } from '../../../core/i18n/locale-labels';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { Combobox, ComboboxOption } from '../../../shared/ui/combobox';
import { DeniedState } from '../../../shared/ui/denied-state';
import { MoneyInput } from '../../../shared/ui/money-input';
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
import { ComboDialogConfirmation, ComboPickerDialog } from './combo-picker-dialog';
import { resolvePicks } from './combo-selection';
import { DineInTablePicker, TablePick } from './dine-in-table-picker';
import { ItemModifierDialog, ModifierDialogConfirmation } from './item-modifier-dialog';
import {
  AggregatorOrderLine,
  BranchCandidate,
  BranchOverrideReason,
  CommentPresetOption,
  CustomerLookupCandidate,
  DeliveryFeeQuote,
  MenuComboGroup,
  MenuModifierGroup,
  MenuProduct,
  MenuVariant,
  NewOrderApi,
  OrderQuote,
  PlaceOrderLine,
  PlaceOrderRequest,
  StorefrontMenu,
} from './new-order-api';
import { BasketLineView, NewOrderBasket } from './new-order-basket';
import { NewOrderHeader } from './new-order-header';
import { NewOrderMenuGrid } from './new-order-menu-grid';
import {
  BasketLine,
  basketFactsFor,
  comboAmountMinor,
  computeBasketTotal,
  lineUnitAmountMinor,
} from './new-order-total';

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

/** A combo the operator has opened and not yet confirmed (ADR 0136). */
interface PendingComboSelection {
  readonly product: MenuProduct;
  readonly variant: MenuVariant;
  readonly groups: readonly MenuComboGroup[];
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
 * doc), promo code is threaded to `CartService.applyPromoCode` (ADR 0072).
 * The total beside «Создать» is the server's own price (row `1.3e`): the same
 * request, run through the same cart and pricing path and undone
 * (`NewOrderApi.quote`), so a promo discount and a delivery fee are in the figure
 * the operator reads out, and «Сдача» is the tender minus that total. The cash
 * tendered is sent with the order (`cashTenderedMinor`) and lands in the
 * transaction that creates it. «Повторить»
 * (row `1.3f`) calls the staff reorder-plan wrapper and adds every
 * `AVAILABLE` line straight to the basket. A «Заказ агрегатора»
 * toggle (row `1.3g`) records an aggregator's own phoned-through order under
 * its `AGGREGATOR`-type channel with externally-set totals; the order itself
 * still matches no customer account — ADR 0040 is explicit that a
 * marketplace order never does. Wave 11 w5-fulfillment-destination lets this
 * be a `DELIVERY` entry: the panel it replaces reads `fulfillmentMode`,
 * `selectedCustomer` and `selectedAddressId` unchanged (`toggleAggregatorMode`
 * touches none of them), so an operator resolves the customer and picks a
 * delivery address in the ordinary customer pane first, then switches to
 * this one — the resolved address scopes the lookup only, never the order's
 * own attribution.
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
 *
 * **Wave 15 (ADR 0047, operator side): a third mode, `DINE_IN`.** The operator
 * names the table the order is for -- a party already seated, or one seated from
 * here -- through {@link DineInTablePicker}; `Создать` then places the order at the
 * operator's own branch (a table is a room, and there is no cross-branch question
 * to resolve for one) and names the party's session in the same request
 * (`dineInSessionId`). The platform puts the order on that party's bill inside the
 * transaction that creates it, so the order shows its table on the board, the
 * detail and the kitchen ticket at once, and cannot exist without being on a bill:
 * a party that left while the basket was being built refuses the placement
 * (`SESSION_NOT_LIVE`) before anything is priced or cooked, the screen says so and
 * re-reads the room, and the operator chooses again. There is no second call to
 * lose and no half-placed order to recover.
 */
@Component({
  selector: 'q-new-order-page',
  imports: [
    TPipe,
    Combobox,
    CreateCustomerDialog,
    ItemModifierDialog,
    ComboPickerDialog,
    DeniedState,
    MoneyInput,
    DineInTablePicker,
    NewOrderBasket,
    NewOrderHeader,
    NewOrderMenuGrid,
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
  private readonly tablePicker = viewChild(DineInTablePicker);

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
   * The operator's own logged-in branch — still what an aggregator entry
   * places at (that toggle carries no cross-branch resolution of its own) and
   * the label shown while the real resolver (below, row 1.3) has not yet
   * proposed anything.
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
    // Row 1.3: the fallback the resolution effect (constructor) keeps until
    // its first successful resolve — the New Order screen stays usable at
    // the operator's own branch from the first render, before any resolver
    // read has even started. Guarded rather than a plain set(): the
    // constructor's own effect can resolve first (it fires immediately, with
    // no await ahead of it, while this method is still on its own first
    // await) and this must never stomp a proposal that already landed.
    this.selectedLocationId.update((current) => current ?? scope.locationId);
    void this.api
      .branchOverrideReasons(scope)
      .then((reasons) => this.overrideReasons.set(reasons))
      .catch(() => {
        // Graceful degradation: an override is simply refused with no reason
        // to pick from ({@link canSubmit}) rather than the whole screen
        // failing to load.
      });

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

  /**
   * The groups a product offers, each with this product's own required/min/max where it overrides
   * the shared group's (ADR 0136) — the same values the cart enforces, so the dialog never offers a
   * range the platform then refuses.
   */
  protected modifierGroupsFor(product: MenuProduct): readonly MenuModifierGroup[] {
    const index = this.modifierGroupIndex();
    const policies = new Map(
      (product.modifierGroupPolicies ?? []).map((policy) => [policy.modifierGroupId, policy]),
    );
    return product.modifierGroupIds
      .map((id) => index.get(id))
      .filter((group): group is MenuModifierGroup => group !== undefined)
      .map((group) => {
        const policy = policies.get(group.modifierGroupId);
        return policy
          ? {
              ...group,
              required: policy.required,
              minimumSelections: policy.minimumSelections,
              maximumSelections: policy.maximumSelections,
            }
          : group;
      });
  }

  /** The combo groups a variant is the container of, in the author's order; empty when it is no combo. */
  protected comboGroupsFor(variantId: string): readonly MenuComboGroup[] {
    return (this.menu()?.comboGroups ?? []).filter(
      (group) => group.containerVariantId === variantId,
    );
  }

  /** `LARGE, EXTRA_SHOT×3` — a compact summary of one basket line's chosen modifiers. */
  protected modifierSummary(line: BasketLine): string {
    return line.modifiers
      .map((modifier) => {
        const label = modifier.name || modifier.code;
        return modifier.quantity > 1 ? `${label}×${modifier.quantity}` : label;
      })
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

  /** The console's own language wording for a checked preset code, matching `item-modifier-dialog.ts`'s own `presetLabel`. */
  private presetLabel(preset: CommentPresetOption): string {
    return presetLabelFor(preset, this.i18n.locale());
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

  /** `Burger, Cola×2` — a combo line's picks, the components it will become on the order. */
  protected comboSummary(line: BasketLine): string {
    return (line.combo?.picks ?? [])
      .map((pick) => (pick.pickQuantity > 1 ? `${pick.name}×${pick.pickQuantity}` : pick.name))
      .join(', ');
  }

  /** The basket with each line's summaries worded, for the basket component. */
  protected readonly basketView = computed<readonly BasketLineView[]>(() =>
    this.basket().map((line) => ({
      ...line,
      modifierText: this.modifierSummary(line),
      presetText: this.presetSummary(line),
      comboText: this.comboSummary(line),
    })),
  );

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
      const index = this.variantIndex();
      let dropped = 0;
      const added: BasketLine[] = [];
      for (const line of available) {
        // ADR 0136: a combo repeats as a combo — its container with the picks the order named,
        // resolved against today's menu. One the menu no longer offers is left out and said so.
        if (line.comboPicks && line.comboPicks.length > 0) {
          const picks = resolvePicks(this.comboGroupsFor(line.variantId), line.comboPicks);
          if (picks === null) {
            dropped += 1;
            continue;
          }
          const combo = { picks };
          added.push({
            lineKey: nextLineKey(),
            variantId: line.variantId,
            productName: line.productName,
            quantity: line.quantity,
            unitAmountMinor: comboAmountMinor(combo),
            combo,
            modifiers: [],
            commentPresetCodes: [],
            customerNote: null,
            orderable: true,
            onSaleNow: true,
          });
          continue;
        }
        // ADR 0137: the line is ordered from today's menu, so it takes today's portion and weight rules.
        const facts = basketFactsFor(index.get(line.variantId)?.variant.physical);
        added.push({
          lineKey: nextLineKey(),
          variantId: line.variantId,
          productName: line.productName,
          quantity: line.quantity,
          unitAmountMinor: line.unitAmountMinor,
          portionStep: facts.portionStep,
          catchweight: facts.catchweight,
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
        });
      }
      this.basket.set([...this.basket(), ...added]);
      this.historyOpen.set(false);
      if (plan.verdict === 'PARTIAL' || dropped > 0) {
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

  protected readonly fulfillmentMode = signal<'PICKUP' | 'DELIVERY' | 'DINE_IN'>('PICKUP');

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
  protected fulfillmentModeLabel(mode: 'PICKUP' | 'DELIVERY' | 'DINE_IN'): string {
    switch (mode) {
      case 'DELIVERY':
        return this.i18n.t('orders.fulfillmentMode.DELIVERY');
      case 'DINE_IN':
        return this.i18n.t('orders.fulfillmentMode.DINE_IN');
      case 'PICKUP':
        return this.i18n.t('orders.fulfillmentMode.PICKUP');
    }
  }

  protected setFulfillmentMode(mode: 'PICKUP' | 'DELIVERY' | 'DINE_IN'): void {
    this.fulfillmentMode.set(mode);
    if (mode === 'DINE_IN' && this.preOrderEnabled()) {
      // Food eaten at a table is eaten now: there is no promise time to ask for.
      this.preOrderEnabled.set(false);
      this.requestedForLocal.set('');
      this.requestedForError.set(null);
      this.outOfHoursConfirmReason.set(null);
    }
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
  protected readonly pendingCombo = signal<PendingComboSelection | null>(null);

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
    // ADR 0136: a combo's container is never sold on its own — what goes into the basket is the
    // components the operator picks from its groups.
    const combos = this.comboGroupsFor(variant.variantId);
    if (combos.length > 0) {
      this.pendingCombo.set({ product, variant, groups: combos });
      return;
    }
    const groups = this.modifierGroupsFor(product);
    if (groups.length === 0 && product.commentPresets.length === 0) {
      this.addToBasket(product, variant, [], []);
      return;
    }
    this.pendingModifiers.set({ product, variant, groups });
  }

  protected onComboConfirm(confirmation: ComboDialogConfirmation): void {
    const pending = this.pendingCombo();
    if (!pending) {
      return;
    }
    const combo = { picks: confirmation.picks };
    this.basket.set([
      ...this.basket(),
      {
        lineKey: nextLineKey(),
        variantId: pending.variant.variantId,
        productName: pending.product.name,
        quantity: 1,
        unitAmountMinor: comboAmountMinor(combo),
        combo,
        modifiers: [],
        commentPresetCodes: [],
        customerNote: null,
        orderable: pending.variant.orderable,
        onSaleNow: pending.variant.onSaleNow,
      },
    ]);
    this.pendingCombo.set(null);
  }

  protected onComboDismiss(): void {
    this.pendingCombo.set(null);
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
    // ADR 0137: a splittable variant is ordered in its portion size, and a weighed one is priced
    // per quantum at its nominal weight until the kitchen weighs it.
    const facts = basketFactsFor(variant.physical);
    const line: BasketLine = {
      lineKey: nextLineKey(),
      variantId: variant.variantId,
      productName: product.name,
      quantity: facts.initialQuantity,
      unitAmountMinor: variant.amountMinor,
      portionStep: facts.portionStep,
      catchweight: facts.catchweight,
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
    const quote = this.serverQuote();
    if (quote !== null) {
      // The server's own figure: what the order is booked at, discount and fee in.
      return formatMoney(
        { amountMinor: quote.totalMinor, currency: quote.currency },
        this.i18n.locale(),
      );
    }
    const total = this.total();
    if (total.currency === null) {
      return '—';
    }
    return formatMoney(
      { amountMinor: total.subtotalMinor, currency: total.currency },
      this.i18n.locale(),
    );
  }

  // ------------------------------------------------ row 1.3e: the server's price before Создать

  /**
   * The price the server would book for this basket, from `POST .../orders/quote`: the same request
   * as «Создать», run through the same cart and pricing path and undone, so the promo discount and
   * the delivery fee are in it. Null while there is nothing to price, while the answer is on its way
   * (the figure beside it would be stale the moment an input changed) and when the read failed --
   * the screen then falls back to the menu arithmetic it always had, honestly labelled an estimate.
   */
  protected readonly serverQuote = signal<OrderQuote | null>(null);
  protected readonly quoteLoading = signal(false);
  /** Why the server will not price this basket as it stands (a promo code that does not apply, an item out of stock), in words. */
  protected readonly quoteRefusal = signal<string | null>(null);

  private quoteTimer: ReturnType<typeof setTimeout> | null = null;
  /** Bumped on every change and every answer, so a slow response for an old basket is dropped. */
  private quoteSequence = 0;

  /** Waits this long after the last edit before asking, so a quantity stepper held down is one request. */
  private static readonly QUOTE_DEBOUNCE_MS = 400;

  /** What the order would cost the customer, for «Сдача»: the server's figure, the menu sum until it arrives. */
  private readonly changeBaseMinor = computed(
    () => this.serverQuote()?.totalMinor ?? this.total().subtotalMinor,
  );

  /** A delivery the zone refuses or prices below its minimum: checkout would refuse it, so the operator hears it now. */
  protected quoteDeliveryNotice(): string | null {
    const quote = this.serverQuote();
    if (quote?.deliveryOutcome == null) {
      return null;
    }
    if (quote.deliveryOutcome === 'RESOLVED' || quote.deliveryOutcome === 'EXTERNALLY_PRICED') {
      return null;
    }
    const shortfall = quote.deliveryShortfallMinor;
    if (shortfall !== null && shortfall > 0) {
      return this.i18n.t('orders.newOrder.order.quote.belowMinimum', {
        amount: formatMoney(
          { amountMinor: shortfall, currency: quote.currency },
          this.i18n.locale(),
        ),
      });
    }
    return this.i18n.t('orders.newOrder.order.quote.deliveryRefused');
  }

  protected formattedQuoteAmount(amountMinor: number): string {
    const currency = this.serverQuote()?.currency ?? this.total().currency;
    return currency === null ? '—' : formatMoney({ amountMinor, currency }, this.i18n.locale());
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
   * The order pane's live «Сдача» (orders.md §5.6): what the customer hands over minus what the
   * order costs -- the server's price (row 1.3e), so a promo code and a delivery fee are in it. It
   * is also sent with the order (`cashTenderedMinor`), which writes it to the order in the
   * transaction that creates it; a later change is the `SET_CASH_TENDERED` amendment (ADR 0039).
   */
  protected readonly changeDueMinor = computed(() => {
    const tendered = this.cashTenderedMinor();
    const total = this.total();
    if (tendered <= 0 || total.currency === null) {
      return null;
    }
    return tendered - this.changeBaseMinor();
  });

  protected formattedChangeDue(): string | null {
    const changeMinor = this.changeDueMinor();
    const currency = this.serverQuote()?.currency ?? this.total().currency;
    if (changeMinor === null || currency === null || changeMinor < 0) {
      return null;
    }
    return formatMoney({ amountMinor: changeMinor, currency }, this.i18n.locale());
  }

  /** The tender is less than the price: the order is still created (the customer can hand over more), but the operator is told. */
  protected formattedTenderShortfall(): string | null {
    const changeMinor = this.changeDueMinor();
    const currency = this.serverQuote()?.currency ?? this.total().currency;
    if (changeMinor === null || currency === null || changeMinor >= 0) {
      return null;
    }
    return formatMoney({ amountMinor: -changeMinor, currency }, this.i18n.locale());
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

    // Row 1.3e: the server's price for the basket as it stands. Any input the price depends
    // on retires the figure on screen at once (it would be stale) and, after the operator
    // stops editing, asks again. Debounced like the delivery-fee preview above, and a
    // slow answer for an old basket is dropped by `quoteSequence`.
    effect((onCleanup) => {
      const scope = this.location.scope();
      const customer = this.selectedCustomer();
      const mode = this.fulfillmentMode();
      const addressId = this.selectedAddressId();
      const placeAt = this.selectedLocationId();
      const basketTotal = this.total();
      // Not read for their value: each one is something the price depends on.
      this.promoCode();
      this.paymentMethodCode();
      this.channelCode();

      this.quoteSequence += 1;
      const sequence = this.quoteSequence;
      untracked(() => {
        this.serverQuote.set(null);
        this.quoteRefusal.set(null);
        this.quoteLoading.set(false);
      });
      if (this.quoteTimer !== null) {
        clearTimeout(this.quoteTimer);
        this.quoteTimer = null;
      }
      if (
        !scope ||
        customer === null ||
        placeAt === null ||
        basketTotal.currency === null ||
        this.basket().length === 0 ||
        !basketTotal.allAvailable ||
        (mode === 'DELIVERY' && addressId === null)
      ) {
        return;
      }
      this.quoteTimer = setTimeout(() => {
        this.quoteTimer = null;
        void this.refreshQuote(sequence);
      }, NewOrderPage.QUOTE_DEBOUNCE_MS);
      onCleanup(() => {
        if (this.quoteTimer !== null) {
          clearTimeout(this.quoteTimer);
          this.quoteTimer = null;
        }
      });
    });

    // Row 1.3's cross-branch resolver. Re-resolves on every fulfilment-mode
    // or address change — no debounce, unlike the delivery-fee preview
    // above: that effect also tracks the basket subtotal, which changes on
    // every quantity-stepper click, while this one tracks only mode, address
    // and channel, each a single discrete event (a toggle, a pick from a
    // list, one bootstrap correction), never a rapid-fire one a debounce
    // would need to absorb. `channelCode` is tracked too: `ngOnInit` starts
    // it at the fallback code and corrects it once the real operator channel
    // loads, and a candidate's open/closed state depends on which channel
    // asked.
    effect(() => {
      const mode = this.fulfillmentMode();
      const address = mode === 'DELIVERY' ? this.selectedAddress() : null;
      const channelCode = this.channelCode();

      // A changed resolution context (mode, address or channel) retires
      // whatever the operator picked for the previous one — a manual choice
      // for one address should not silently carry over to a different one.
      this.branchManuallyOverridden = false;
      this.overrideReasonCode.set(null);
      this.overrideNote.set('');

      if (mode === 'DINE_IN') {
        // A table is a room in this branch: there is no cross-branch question to
        // ask (the resolver refuses DINE_IN outright), and the order is placed
        // here. selectedLocationId is set, not merely left alone, because the
        // operator may arrive from a pickup order the resolver had sent elsewhere.
        this.branchCandidates.set([]);
        this.proposedLocationId.set(null);
        const here = this.location.scope()?.locationId ?? null;
        if (here !== null) {
          this.selectedLocationId.set(here);
        }
        return;
      }

      if (
        mode === 'DELIVERY' &&
        (address === null || address.latitude === null || address.longitude === null)
      ) {
        this.branchCandidates.set([]);
        this.proposedLocationId.set(null);
        // selectedLocationId is left alone: it stays at the operator's own
        // branch (ngOnInit's own fallback) until a real address gives the
        // resolver something to answer.
        return;
      }
      const point =
        mode === 'DELIVERY' && address
          ? { lat: address.latitude as number, lon: address.longitude as number }
          : null;
      void this.refreshBranchResolution(mode, point, channelCode);
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

  private async refreshQuote(sequence: number): Promise<void> {
    const scope = this.location.scope();
    const customer = untracked(() => this.selectedCustomer());
    if (!scope || customer === null || sequence !== this.quoteSequence) {
      return;
    }
    const request = untracked(() => this.placeRequest(customer, scope, 'quote'));
    if (request === null) {
      return;
    }
    const placeAtScope = untracked(() => ({ ...scope, locationId: this.placeAtLocationId(scope) }));
    this.quoteLoading.set(true);
    try {
      const quote = await this.api.quote(placeAtScope, request);
      if (sequence === this.quoteSequence) {
        this.serverQuote.set(quote);
      }
    } catch (error) {
      if (sequence !== this.quoteSequence) {
        return;
      }
      // A business refusal (a promo code that does not apply, an item out of stock, an address
      // outside every zone) is worth a sentence now rather than after «Создать». Anything else --
      // no network, a server fault, a missing grant -- leaves the menu estimate standing: the
      // quote is a convenience on top of placing the order, never a gate in front of it.
      if (
        error instanceof ApiError &&
        error.status >= 400 &&
        error.status < 500 &&
        accessRefusal(error)?.kind !== 'denied'
      ) {
        this.quoteRefusal.set(this.describeDeliveryRefusal(error));
      }
    } finally {
      if (sequence === this.quoteSequence) {
        this.quoteLoading.set(false);
      }
    }
  }

  /** `—` while unlocated or unresolved, a translated refusal when the resolver refuses, the formatted fee otherwise. */
  protected formattedDeliveryFee(): string {
    const priced = this.serverQuote();
    if (priced !== null && priced.deliveryOutcome !== null) {
      // The server's own fee, from the same pricing the order is booked with (row 1.3e).
      return this.quoteDeliveryNotice() !== null
        ? this.i18n.t('orders.newOrder.order.deliveryFeeUnavailable')
        : formatMoney(
            { amountMinor: priced.feeMinor, currency: priced.currency },
            this.i18n.locale(),
          );
    }
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
    if (this.serverQuote() !== null) {
      // The server's total above already carries the fee; adding the preview again would count it twice.
      return null;
    }
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

  // ------------------------------------------------------------- §5.4/§5.6 branch resolution (row 1.3)

  /**
   * Row 1.3: which of the brand's branches can take this order, ranked by
   * zone match for DELIVERY and by current load for PICKUP (row 1.3's own
   * note: a PICKUP order has no address to rank a zone against). Empty
   * before the first resolution completes, or when nothing serves the
   * chosen address.
   */
  protected readonly branchCandidates = signal<readonly BranchCandidate[]>([]);
  /** The resolver's own pick — the first open candidate in ranked order, or the top-ranked one if none is open. */
  protected readonly proposedLocationId = signal<string | null>(null);
  /**
   * The branch this order will actually be placed at. Defaults to the
   * operator's own current branch ({@link ngOnInit}) until the resolver has
   * something better to propose, and tracks {@link proposedLocationId} after
   * that unless the operator has picked a different candidate by hand
   * ({@link selectBranch}).
   */
  protected readonly selectedLocationId = signal<string | null>(null);
  protected readonly branchResolutionLoading = signal(false);
  protected readonly branchResolutionError = signal(false);

  /** Row 1.3's curated override-reason list, fetched once in {@link ngOnInit}. */
  protected readonly overrideReasons = signal<readonly BranchOverrideReason[]>([]);
  protected readonly overrideReasonCode = signal<string | null>(null);
  protected readonly overrideNote = signal('');

  /** Plain field, not a signal: read only inside the resolution effect above, never rendered. */
  private branchManuallyOverridden = false;

  /** Whether the operator has picked a branch other than the resolver's own proposal. */
  protected readonly isBranchOverride = computed(() => {
    const selected = this.selectedLocationId();
    const proposed = this.proposedLocationId();
    return selected !== null && proposed !== null && selected !== proposed;
  });

  protected readonly selectedBranchCandidate = computed(
    () =>
      this.branchCandidates().find(
        (candidate) => candidate.locationId === this.selectedLocationId(),
      ) ?? null,
  );

  /** {@code requiresNote} of whichever override reason is currently picked, or false while none is. */
  protected readonly overrideReasonRequiresNote = computed(
    () =>
      this.overrideReasons().find((reason) => reason.code === this.overrideReasonCode())
        ?.requiresNote ?? false,
  );

  private async refreshBranchResolution(
    mode: 'PICKUP' | 'DELIVERY',
    point: { lat: number; lon: number } | null,
    channelCode: string,
  ): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.branchResolutionLoading.set(true);
    this.branchResolutionError.set(false);
    try {
      const resolution = await this.api.resolveBranches(scope, mode, point, channelCode);
      this.branchCandidates.set(resolution.candidates);
      this.proposedLocationId.set(resolution.proposedLocationId);
      if (!this.branchManuallyOverridden) {
        this.selectedLocationId.set(resolution.proposedLocationId ?? scope.locationId);
      }
    } catch {
      // Graceful degradation, the same shape `drafts-page.ts` and this
      // screen's own channel lookup already use: the New Order screen stays
      // usable at the operator's own branch even when the resolver read
      // fails, rather than blocking order entry on a read that is a
      // convenience, not a precondition for placing an order.
      this.branchCandidates.set([]);
      this.proposedLocationId.set(null);
      this.branchResolutionError.set(true);
    } finally {
      this.branchResolutionLoading.set(false);
    }
  }

  /** The operator overriding the resolver's own proposal — every candidate stays choosable, closed ones included. */
  protected selectBranch(locationId: string): void {
    this.branchManuallyOverridden = true;
    this.selectedLocationId.set(locationId);
    if (locationId === this.proposedLocationId()) {
      this.overrideReasonCode.set(null);
      this.overrideNote.set('');
    }
  }

  protected setOverrideReasonCode(code: string): void {
    this.overrideReasonCode.set(code === '' ? null : code);
    if (this.overrideReasonCode() !== null && !this.overrideReasonRequiresNote()) {
      this.overrideNote.set('');
    }
  }

  protected branchCandidateLabel(candidate: BranchCandidate): string {
    const load = this.i18n.t('orders.newOrder.order.branchLoad', {
      count: candidate.activeOrderCount,
    });
    const closed = candidate.available
      ? ''
      : ` · ${this.i18n.t('orders.newOrder.order.branchClosed')}`;
    return `${candidate.displayName} — ${load}${closed}`;
  }

  /** `order-reject-reason-dialog.ts`'s own defensive fallback, restated for this picker's identical response shape. */
  protected branchOverrideReasonLabel(reason: BranchOverrideReason): string {
    const locale = this.i18n.locale();
    return reason.labels?.[locale] ?? reason.labels?.['ru'] ?? reason.code;
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

  // ------------------------------------------------------------- DINE_IN table (ADR 0047)

  /** The party the DINE_IN order goes to, as {@link DineInTablePicker} last reported it. */
  protected readonly tablePick = signal<TablePick | null>(null);

  /** The operator's branch as the picker's scope: a table is looked for, and seated at, here. */
  protected readonly locationScope = computed(() => this.location.scope());

  protected onTablePicked(pick: TablePick | null): void {
    this.tablePick.set(pick);
  }

  protected readonly canSubmit = computed(
    () =>
      this.basket().length > 0 &&
      this.selectedCustomer() !== null &&
      this.total().allAvailable &&
      (this.fulfillmentMode() === 'PICKUP' ||
        (this.fulfillmentMode() === 'DELIVERY' && this.selectedAddressId() !== null) ||
        (this.fulfillmentMode() === 'DINE_IN' && this.tablePick() !== null)) &&
      (!this.preOrderEnabled() || this.requestedForLocal().trim() !== '') &&
      this.selectedLocationId() !== null &&
      (!this.isBranchOverride() ||
        (this.overrideReasonCode() !== null &&
          (!this.overrideReasonRequiresNote() || this.overrideNote().trim() !== ''))) &&
      !this.submitting(),
  );

  /**
   * The branch an order is placed -- and priced -- at: a table is a room in the operator's own
   * branch, so a DINE_IN order is placed here whatever a pickup earlier in the session had
   * resolved elsewhere; any other order goes to the resolved (or overridden) branch.
   */
  private placeAtLocationId(scope: LocationScope): string {
    return this.fulfillmentMode() === 'DINE_IN'
      ? scope.locationId
      : (this.selectedLocationId() ?? scope.locationId);
  }

  /**
   * The body of `POST .../orders` -- and, minus what only matters to placing, of `POST
   * .../orders/quote`. One builder, so the price the operator is shown is asked for with exactly the
   * basket, destination, promo code and payment method the order is then placed with. A quote
   * leaves out what decides where, when and for whom the order lands rather than what it costs:
   * the pre-order time, the branch override, the party's bill, and the cash tendered.
   *
   * Null when the request cannot be made yet (a delivery with no address; for a quote, no one to
   * deliver to).
   */
  private placeRequest(
    customer: { accountId: string; label: string },
    scope: LocationScope,
    kind: 'place' | 'quote',
    confirmingOutOfHours = false,
  ): PlaceOrderRequest | null {
    const place = kind === 'place';
    const delivery = this.fulfillmentMode() === 'DELIVERY';
    const addressId = this.selectedAddressId();
    const dineIn = this.fulfillmentMode() === 'DINE_IN';
    let destination: PlaceOrderRequest['destination'] = null;
    if (delivery) {
      if (addressId === null) {
        return null;
      }
      const recipientName = this.recipientName().trim() || customer.label;
      const recipientPhone = this.recipientPhone().trim() || this.phone().trim();
      if (!place && (recipientName === '' || recipientPhone === '')) {
        // The server refuses a destination with no one to deliver to; a price does not
        // depend on who it is, so there is nothing to ask until there is a name.
        return null;
      }
      destination = {
        customerAddressId: addressId,
        recipientName,
        recipientPhone,
        deliveryNote: this.deliveryNote().trim() || null,
      };
    }
    const lines: PlaceOrderLine[] = this.basket().map((line) => ({
      variantId: line.variantId,
      quantity: line.quantity,
      modifierOptionIds: flattenModifiers(line),
      commentPresetCodes: line.commentPresetCodes,
      customerNote: line.customerNote,
      // ADR 0136: a combo goes as its container with the components picked; `quantity` counts combos.
      ...(line.combo
        ? {
            comboPicks: line.combo.picks.map((pick) => ({
              componentId: pick.componentId,
              quantity: pick.pickQuantity,
            })),
          }
        : {}),
    }));
    const override = place && this.isBranchOverride();
    const requestedForDate = place ? this.requestedForDate() : null;
    const tendered = this.cashTenderedMinor();
    return {
      customerAccountId: customer.accountId,
      channelCode: this.channelCode(),
      fulfillmentMode: this.fulfillmentMode(),
      lines,
      destination,
      paymentMethodCode: this.paymentMethodCode(),
      promoCode: this.promoCode().trim() || null,
      requestedFor: requestedForDate ? requestedForDate.toISOString() : null,
      overrideOutOfHours: place && confirmingOutOfHours,
      proposedLocationId: this.proposedLocationId(),
      overrideReasonCode: override ? this.overrideReasonCode() : null,
      overrideNote: override ? this.overrideNote().trim() || null : null,
      // The party's bill is part of the placement, not a call after it (ADR 0047).
      dineInSessionId: place && dineIn ? (this.tablePick()?.sessionId ?? null) : null,
      // Row 1.3e: what the customer says they will hand over, written to the order in the
      // transaction that creates it. Only a cash order has one, and only an entered figure.
      cashTenderedMinor:
        place && this.paymentMethodCode() === 'CASH' && tendered > 0 ? tendered : null,
    };
  }

  protected async submit(): Promise<void> {
    const scope = this.location.scope();
    const customer = this.selectedCustomer();
    if (!scope || !customer || !this.canSubmit()) {
      return;
    }
    const delivery = this.fulfillmentMode() === 'DELIVERY';
    if (delivery && this.selectedAddressId() === null) {
      return;
    }
    // Row 1.3: POST .../orders at the resolved (or overridden) branch, not
    // always the operator's own logged-in one. A table is a room in the
    // operator's own branch, so a DINE_IN order is placed here whatever a
    // pickup order earlier in this session had resolved elsewhere.
    const placeAtScope = { ...scope, locationId: this.placeAtLocationId(scope) };
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
      const request = this.placeRequest(customer, scope, 'place', confirmingOutOfHours);
      if (request === null) {
        return;
      }
      const result = await this.api.placeOrder(placeAtScope, request);
      this.outOfHoursConfirmReason.set(null);
      // The order exists from here on, and everything below is about not losing it.
      if (this.callEventId) {
        try {
          // The order's own branch — placeAtScope, not the operator's own
          // logged-in one — since an override placed it there.
          await this.api.recordCallProvenance(placeAtScope, result.orderId, this.callEventId);
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
      if (result.warnings.includes('CASH_TENDERED_INSUFFICIENT')) {
        // The order is created either way -- the customer can hand over more -- but the
        // operator is told the figure they entered was short of the price.
        this.toasts.show({
          message: this.i18n.t('orders.newOrder.order.tenderInsufficient'),
          tone: 'info',
        });
      }
      void this.router.navigate(['/orders', result.orderId]);
    } catch (error) {
      if (error instanceof ApiError) {
        const refusal = accessRefusal(error);
        const reason = error.problem?.['reason'];
        if (refusal?.kind === 'denied') {
          this.submitDenied.set(true);
        } else if (reason === 'REQUESTED_TIME_IN_PAST') {
          this.requestedForError.set(this.i18n.t('orders.newOrder.order.preOrder.mustBeFuture'));
        } else if (reason === 'SESSION_NOT_LIVE') {
          // The party the operator picked has left (a host closed the table while the
          // basket was being built). Nothing was placed. Re-read the room, which
          // drops the closed party and clears the pick, so the operator chooses again.
          this.submitError.set(this.i18n.t('orders.newOrder.table.sessionEnded'));
          void this.tablePicker()?.reload();
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
      // For a table order this is not about a delivery zone: the branch or the
      // channel does not take DINE_IN right now (a schedule or a channel mode
      // nobody has switched on), and saying "outside every delivery zone" would
      // send the operator looking for an address that does not exist.
      return this.fulfillmentMode() === 'DINE_IN'
        ? this.i18n.t('orders.newOrder.table.notServiceable')
        : this.i18n.t('orders.newOrder.address.notServiceable');
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
    if (this.aggregatorMode() && this.fulfillmentMode() === 'DINE_IN') {
      // A marketplace order is collected or delivered, never eaten at one of our
      // tables: the aggregator panel reads the page's mode and has no third case.
      this.setFulfillmentMode('PICKUP');
    }
    const first = this.aggregatorChannels()[0];
    if (this.aggregatorMode() && first && this.aggregatorChannelCode() === null) {
      this.aggregatorChannelCode.set(first.code);
    }
  }

  /**
   * Row 1.3g (wave 11 w5-fulfillment-destination): DELIVERY reuses the
   * page's own address pane — `fulfillmentMode`, `selectedCustomer` and
   * `selectedAddressId` are the identical signals {@link canSubmit}/{@link
   * submit} above already gate a native order on, neither hidden nor reset
   * when `aggregatorMode` is on (see `new-order-page.html`).
   */
  protected readonly canSubmitAggregator = computed(
    () =>
      this.basket().length > 0 &&
      this.total().allAvailable &&
      this.aggregatorChannelCode() !== null &&
      this.aggregatorExternalOrderId().trim() !== '' &&
      this.aggregatorTotalMinor() > 0 &&
      this.fulfillmentMode() !== 'DINE_IN' &&
      (this.fulfillmentMode() === 'PICKUP' ||
        (this.selectedCustomer() !== null && this.selectedAddressId() !== null)) &&
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
    const delivery = this.fulfillmentMode() === 'DELIVERY';
    const customer = this.selectedCustomer();
    const addressId = this.selectedAddressId();
    if (delivery && (customer === null || addressId === null)) {
      return;
    }
    this.aggregatorSubmitting.set(true);
    this.aggregatorError.set(null);
    try {
      const lines: AggregatorOrderLine[] = this.basket().map((line) => ({
        variantId: line.variantId,
        nameSnapshot: line.productName,
        quantity: line.quantity,
        // One unit's price: for a weighed variant the menu price is per quantum, which is not
        // what an aggregator's ticket states for a line.
        unitAmountMinor: lineUnitAmountMinor(line) ?? 0,
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
        fulfillmentMode: this.fulfillmentMode(),
        customerAccountId: delivery && customer !== null ? customer.accountId : null,
        destination:
          delivery && addressId !== null && customer !== null
            ? {
                customerAddressId: addressId,
                recipientName: this.recipientName().trim() || customer.label,
                recipientPhone: this.recipientPhone().trim() || this.phone().trim(),
                deliveryNote: this.deliveryNote().trim() || null,
              }
            : null,
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
