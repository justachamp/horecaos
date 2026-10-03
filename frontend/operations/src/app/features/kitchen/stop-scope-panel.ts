import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { CurrentLocation } from '../../core/auth/current-location';
import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { ApiError } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { MenuSetsApi, MenuSetSummary } from '../catalog/menu-sets-api';
import { describeApiError } from '../orders/order-errors';
import { ChannelView, SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import { CreateStopRequest, StopGestureResponse, StopScope, StopsApi } from './stop-scope-api';

/** `InventoryStopGestureService.MAX_ITEMS`, mirrored so the panel can refuse an oversized selection before the round trip. */
const MAX_ITEMS = 200;

/**
 * The enumerated reasons a stop may carry — never free text an operator typed (ADR 0029): the
 * code lands verbatim in the audit trail and on `inventory.events`. The same four the bulk
 * picker offers, plus the one that only makes sense for a reach wider than a branch.
 */
export const STOP_REASON_CODES = [
  'OUT_OF_STOCK',
  'NO_PRODUCT',
  'EQUIPMENT',
  'RECALL',
  'OTHER',
] as const;
type StopReasonCode = (typeof STOP_REASON_CODES)[number];

type Duration = 'INDEFINITE' | 'END_OF_DAY' | 'UNTIL';

/** What the panel reports when a stop landed, so the page can refresh rows, badges and the propagation banner. */
export interface StopApplied {
  readonly response: StopGestureResponse;
  readonly scope: StopScope;
}

/**
 * Stop with a scope (ADR 0141, gap map row 2.5a): which dishes, how far the stop reaches,
 * until when, and why.
 *
 * Scope is four values and the union of covering stops stops the sale — there is no "allow"
 * that overrides a broader stop, so a branch manager cannot un-stop a brand recall. `This
 * branch` and `One channel here` are the branch manager's own reach
 * (`inventory.availability.manage`); `Whole brand`, `A menu` and `One channel everywhere`
 * need `inventory.stop.manage` and are offered only to someone who holds it anywhere — a
 * usability affordance, never an authorization decision: the server checks again and answers
 * 403 to anyone who does not hold it at this brand.
 *
 * A device that takes orders is a sales channel (ADR 0036), so "stop it on the kiosk" is
 * `One channel`; there is no terminal scope to offer.
 */
@Component({
  selector: 'q-stop-scope-panel',
  imports: [TPipe],
  templateUrl: './stop-scope-panel.html',
  styleUrl: './stop-scope-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StopScopePanel {
  private readonly stops = inject(StopsApi);
  private readonly channelsApi = inject(SalesChannelsApi);
  private readonly menusApi = inject(MenuSetsApi);
  private readonly location = inject(CurrentLocation);
  private readonly capabilities = inject(SessionCapabilities);
  protected readonly i18n = inject(I18n);

  /** The dishes to stop — the rows selected on the stop list. */
  readonly variantIds = input.required<readonly string[]>();

  readonly applied = output<StopApplied>();
  readonly closed = output<void>();

  protected readonly reasonCodes = STOP_REASON_CODES;

  protected readonly scope = signal<StopScope>('LOCATION');
  protected readonly channelId = signal('');
  /** For `CHANNEL`: this branch only (the default, a branch manager's own reach) or everywhere the channel runs. */
  protected readonly channelHereOnly = signal(true);
  protected readonly menuId = signal('');
  protected readonly duration = signal<Duration>('INDEFINITE');
  protected readonly until = signal('');
  protected readonly reason = signal<StopReasonCode | ''>('');

  protected readonly channels = signal<readonly ChannelView[]>([]);
  protected readonly menus = signal<readonly MenuSetSummary[]>([]);
  protected readonly busy = signal(false);
  protected readonly message = signal<string | null>(null);
  protected readonly failed = signal(false);

  /** Whether the operator holds the brand-wide capability anywhere; the server decides for real. */
  protected readonly canStopBrandWide = computed(() =>
    this.capabilities.has('INVENTORY_STOP_MANAGE'),
  );

  protected readonly overLimit = computed(() => this.variantIds().length > MAX_ITEMS);

  protected readonly canSubmit = computed(() => {
    if (this.busy() || this.overLimit() || this.variantIds().length === 0 || this.reason() === '') {
      return false;
    }
    if (this.scope() === 'CHANNEL' && this.channelId() === '') {
      return false;
    }
    if (this.scope() === 'MENU' && this.menuId() === '') {
      return false;
    }
    if (this.duration() === 'UNTIL' && !this.untilInstant()) {
      return false;
    }
    return true;
  });

  protected pickScope(scope: StopScope): void {
    this.scope.set(scope);
    this.message.set(null);
    if (scope === 'CHANNEL' && this.channels().length === 0) {
      void this.loadChannels();
    }
    if (scope === 'MENU' && this.menus().length === 0) {
      void this.loadMenus();
    }
  }

  protected scopeLabel(scope: StopScope): string {
    switch (scope) {
      case 'LOCATION':
        return this.i18n.t('kitchen.stopList.scope.location');
      case 'CHANNEL':
        return this.i18n.t('kitchen.stopList.scope.channel');
      case 'BRAND':
        return this.i18n.t('kitchen.stopList.scope.brand');
      case 'MENU':
        return this.i18n.t('kitchen.stopList.scope.menu');
    }
  }

  protected reasonLabel(code: StopReasonCode): string {
    switch (code) {
      case 'OUT_OF_STOCK':
        return this.i18n.t('kitchen.stopList.bulk.reason.outOfStock');
      case 'NO_PRODUCT':
        return this.i18n.t('kitchen.stopList.bulk.reason.noProduct');
      case 'EQUIPMENT':
        return this.i18n.t('kitchen.stopList.bulk.reason.equipment');
      case 'RECALL':
        return this.i18n.t('kitchen.stopList.panel.reason.recall');
      case 'OTHER':
        return this.i18n.t('kitchen.stopList.bulk.reason.other');
    }
  }

  protected onReason(value: string): void {
    this.reason.set(
      (STOP_REASON_CODES as readonly string[]).includes(value) ? (value as StopReasonCode) : '',
    );
  }

  protected onDuration(value: string): void {
    this.duration.set(value === 'END_OF_DAY' || value === 'UNTIL' ? value : 'INDEFINITE');
  }

  /** The `datetime-local` value as an instant, or null when empty, malformed or not in the future. */
  private untilInstant(): string | null {
    const raw = this.until();
    if (raw === '') {
      return null;
    }
    const parsed = new Date(raw);
    return Number.isNaN(parsed.getTime()) || parsed.getTime() <= Date.now()
      ? null
      : parsed.toISOString();
  }

  protected async submit(): Promise<void> {
    const where = this.location.scope();
    if (!where || !this.canSubmit()) {
      return;
    }
    const scope = this.scope();
    const channelHere = scope === 'CHANNEL' && this.channelHereOnly();
    const request: CreateStopRequest = {
      variantIds: this.variantIds(),
      scope,
      reasonCode: this.reason(),
      ...(scope === 'CHANNEL' ? { channelId: this.channelId() } : {}),
      ...(scope === 'MENU' ? { menuId: this.menuId() } : {}),
      ...(this.duration() === 'END_OF_DAY' ? { untilEndOfTradingDay: true } : {}),
      ...(this.duration() === 'UNTIL' ? { endsAt: this.untilInstant() ?? undefined } : {}),
    };
    // A branch-wide channel stop goes to the branch route, and carries `CHANNEL` there: the
    // path supplies the branch. Everything wider goes to the brand route.
    const atThisBranch = scope === 'LOCATION' || channelHere;
    this.busy.set(true);
    this.message.set(null);
    this.failed.set(false);
    try {
      const response = await this.stops.stop(where, request, atThisBranch);
      this.message.set(
        response.failedCount > 0
          ? this.i18n.t('kitchen.stopList.bulk.partial', { failed: response.failedCount })
          : this.i18n.t('kitchen.stopList.bulk.done', { count: response.appliedCount }),
      );
      this.applied.emit({ response, scope });
    } catch (error) {
      this.failed.set(true);
      this.message.set(this.describe(error));
    } finally {
      this.busy.set(false);
    }
  }

  private async loadChannels(): Promise<void> {
    const where = this.location.scope();
    if (!where) {
      return;
    }
    try {
      const all = await this.channelsApi.list(where);
      this.channels.set(all.filter((channel) => channel.status === 'ACTIVE'));
    } catch {
      this.channels.set([]);
    }
  }

  private async loadMenus(): Promise<void> {
    const where = this.location.scope();
    if (!where) {
      return;
    }
    try {
      const all = await this.menusApi.list({ tenantId: where.tenantId, brandId: where.brandId });
      this.menus.set(all.filter((menu) => menu.status !== 'ARCHIVED'));
    } catch {
      this.menus.set([]);
    }
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError && error.status === 409 && this.isFrozen(error)) {
      return this.i18n.t('kitchen.stopList.panel.frozen');
    }
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }

  /** `409 RESOURCE_CONFLICT { conflict: "STOPS_FROZEN" }` — scope stops are paused (ADR 0141's freeze switch). */
  private isFrozen(error: ApiError): boolean {
    const body = error.problem as { conflict?: unknown } | null | undefined;
    return body?.conflict === 'STOPS_FROZEN';
  }

  protected close(): void {
    this.closed.emit();
  }
}
