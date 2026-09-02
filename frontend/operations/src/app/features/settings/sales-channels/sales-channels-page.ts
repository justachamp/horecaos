import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import {
  ChannelMatrices,
  ChannelView,
  CreateChannelRequest,
  SalesChannelsApi,
} from './sales-channels-api';

/** ADR 0036's closed system-type set. */
export const CHANNEL_SYSTEM_TYPES: readonly string[] = [
  'WEB',
  'IOS',
  'ANDROID',
  'TELEGRAM',
  'KIOSK',
  'QR_TABLE',
  'CALL_CENTRE',
  'AGGREGATOR',
  'POS',
];

/**
 * The code-owned provisional payment-method set — `channel_payment_methods
 * .payment_method_code` is a bare `varchar` with no owning registry until ADR
 * 0038 lands (`payments.payment_methods`, 10.6). Naming it here, once, is
 * cheaper than inventing a fourth copy of the same list.
 */
export const PROVISIONAL_PAYMENT_METHODS: readonly string[] = ['CASH', 'CLICK', 'PAYME'];

export const FULFILLMENT_MODES: readonly string[] = ['DELIVERY', 'PICKUP', 'DINE_IN'];

/**
 * 10.4 Sales channels — `docs/operations-spec/settings.md` §10.4.
 *
 * The registry (`SalesChannelController`) is field-complete against the
 * spec's own table. What is simplified here relative to the spec's
 * `MatrixGrid` (row/column bulk toggle, keyboard range-select, hatched
 * "unavailable" cells for a fiscally-incapable method): this renders one
 * channel's two matrices as plain checkboxes, selected from the registry
 * row below it, because this app has no `MatrixGrid` component yet (IA Part
 * 4 lists it as a pilot blocker still to build) and a working checkbox grid
 * beats a polished one that does not exist.
 */
@Component({
  selector: 'q-sales-channels-page',
  imports: [TPipe],
  templateUrl: './sales-channels-page.html',
  styleUrl: './sales-channels-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SalesChannelsPage {
  private readonly api = inject(SalesChannelsApi);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly channels = signal<readonly ChannelView[]>([]);

  protected readonly systemTypes = CHANNEL_SYSTEM_TYPES;
  protected readonly paymentMethods = PROVISIONAL_PAYMENT_METHODS;
  protected readonly fulfillmentModes = FULFILLMENT_MODES;

  protected readonly showCreateForm = signal(false);
  protected readonly createSubmitting = signal(false);
  protected readonly createError = signal<string | null>(null);
  protected readonly newCode = signal('');
  protected readonly newSystemType = signal<string>(CHANNEL_SYSTEM_TYPES[0]);
  protected readonly newDisplayName = signal('');

  protected readonly selectedChannelId = signal<string | null>(null);
  protected readonly matrices = signal<ChannelMatrices | null>(null);
  protected readonly matrixLoading = signal(false);
  protected readonly matrixError = signal<string | null>(null);
  protected readonly matrixSaving = signal(false);

  constructor() {
    void this.load();
  }

  protected canCreate(): boolean {
    return (
      !this.createSubmitting() &&
      this.newCode().trim().length > 0 &&
      this.newDisplayName().trim().length > 0
    );
  }

  protected async submitCreate(): Promise<void> {
    const scope = this.location.scope();
    if (!scope || !this.canCreate()) {
      return;
    }
    this.createSubmitting.set(true);
    this.createError.set(null);
    const request: CreateChannelRequest = {
      code: this.newCode().trim().toUpperCase(),
      systemType: this.newSystemType(),
      displayName: this.newDisplayName().trim(),
      externallyPriced: false,
      guestOrdersAllowed: true,
    };
    try {
      await this.api.create(scope, request);
      this.showCreateForm.set(false);
      this.newCode.set('');
      this.newDisplayName.set('');
      await this.reload(scope);
    } catch (error) {
      this.createError.set(this.describe(error));
    } finally {
      this.createSubmitting.set(false);
    }
  }

  protected async selectChannel(channel: ChannelView): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    if (this.selectedChannelId() === channel.id) {
      this.selectedChannelId.set(null);
      this.matrices.set(null);
      return;
    }
    this.selectedChannelId.set(channel.id);
    this.matrixLoading.set(true);
    this.matrixError.set(null);
    try {
      this.matrices.set(await this.api.matrices(scope, channel.id));
    } catch (error) {
      this.matrixError.set(this.describe(error));
    } finally {
      this.matrixLoading.set(false);
    }
  }

  protected isPaymentMethodEnabled(method: string): boolean {
    return this.matrices()?.paymentMethods[method] === true;
  }

  protected isFulfillmentModeEnabled(mode: string): boolean {
    return this.matrices()?.fulfillmentModes[mode] === true;
  }

  protected async togglePaymentMethod(channel: ChannelView, method: string): Promise<void> {
    const scope = this.location.scope();
    const current = this.matrices();
    if (!scope || !current) {
      return;
    }
    const next = { ...current.paymentMethods, [method]: !current.paymentMethods[method] };
    this.matrixSaving.set(true);
    this.matrixError.set(null);
    try {
      await this.api.replacePaymentMethods(scope, channel.id, next, channel.version);
      this.matrices.set({ ...current, paymentMethods: next });
      await this.reload(scope);
    } catch (error) {
      this.matrixError.set(this.describe(error));
    } finally {
      this.matrixSaving.set(false);
    }
  }

  protected async toggleFulfillmentMode(channel: ChannelView, mode: string): Promise<void> {
    const scope = this.location.scope();
    const current = this.matrices();
    if (!scope || !current) {
      return;
    }
    const next = { ...current.fulfillmentModes, [mode]: !current.fulfillmentModes[mode] };
    this.matrixSaving.set(true);
    this.matrixError.set(null);
    try {
      await this.api.replaceFulfillmentModes(scope, channel.id, next, channel.version);
      this.matrices.set({ ...current, fulfillmentModes: next });
      await this.reload(scope);
    } catch (error) {
      this.matrixError.set(this.describe(error));
    } finally {
      this.matrixSaving.set(false);
    }
  }

  protected async archiveChannel(channel: ChannelView): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    if (
      !confirm(this.i18n.t('settings.salesChannels.archive.confirm', { name: channel.displayName }))
    ) {
      return;
    }
    try {
      await this.api.archive(scope, channel.id, channel.version);
      await this.reload(scope);
    } catch (error) {
      this.loadError.set(this.describe(error));
    }
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.loading.set(false);
      return;
    }
    try {
      this.channels.set(await this.api.list(scope));
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else {
        this.loadError.set(this.describe(error));
      }
    } finally {
      this.loading.set(false);
    }
  }

  private async reload(scope: NonNullable<ReturnType<CurrentLocation['scope']>>): Promise<void> {
    this.channels.set(await this.api.list(scope));
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
