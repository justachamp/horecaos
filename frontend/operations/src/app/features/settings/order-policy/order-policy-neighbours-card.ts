import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { RouterLink } from '@angular/router';

import { EditableScopeType } from '../../../core/api/configuration';
import { LocationScope } from '../../../core/api/operations-paths';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { DeliveryTariffsApi } from '../../delivery/delivery-tariffs-api';
import { PaidHoldApi } from '../../kitchen/paid-hold-api';
import { ConfigurationApi } from '../configuration-api';
import { LocationsApi } from '../locations/locations-api';
import { SalesChannelsApi } from '../sales-channels/sales-channels-api';

/** One setting that shapes an order and is set on a screen of its own (settings.md 10.3, ADR 0177). */
interface NeighbourRow {
  readonly id: string;
  readonly labelKey: MessageKey;
  readonly link: string;
  /** What is in force, already worded; `null` while it is being read. */
  readonly value: string | null;
  /** A caveat that is part of the truth about the value, worded beside it. */
  readonly noteKey?: MessageKey;
  /** The role cannot read it: the row says so rather than showing a blank. */
  readonly unreadable: boolean;
}

const OUT_OF_ZONE_POLICY_CODE = 'delivery.out_of_zone_policy';

/**
 * The settings the information architecture lists on the order policy (10.3) but that live on a screen of
 * their own, shown as what is in force with the way to the screen that edits them (ADR 0177, row `10.3b`):
 * how a delivery is measured (`3.7`), the most orders a branch takes at once (`10.2c`), what happens to an
 * address outside every zone (`10.13`), whether the kitchen waits for payment (ADR 0168), and the call-centre
 * channel's payment methods and order types (`10.4b`).
 *
 * Read-only by construction -- it holds no draft and calls only reads -- and each read fails on its own: a
 * role that cannot read one still sees the others, and the row says so. The pattern is the dispatch-rules
 * summary above it, which this sits beside: an operator on this screen is told what is in force and sent to
 * the right place, not left to look for a field that was never here.
 *
 * The out-of-zone row carries the platform's own caveat: the key is registered and editable, and the delivery
 * fee resolver does not read it yet (`DeliveryConfigurationKeys`). Showing a value without saying so would
 * make it read as a setting that works.
 */
@Component({
  selector: 'q-order-policy-neighbours-card',
  imports: [TPipe, RouterLink],
  templateUrl: './order-policy-neighbours-card.html',
  styleUrl: './order-policy-neighbours-card.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderPolicyNeighboursCard {
  private readonly tariffs = inject(DeliveryTariffsApi);
  private readonly locations = inject(LocationsApi);
  private readonly channels = inject(SalesChannelsApi);
  private readonly configuration = inject(ConfigurationApi);
  private readonly paidHold = inject(PaidHoldApi);
  private readonly i18n = inject(I18n);

  readonly tenantId = input.required<string | null>();
  readonly scopeType = input.required<EditableScopeType>();
  readonly brandId = input.required<string | null>();
  readonly locationId = input.required<string | null>();

  protected readonly rows = signal<readonly NeighbourRow[]>(initialRows());

  private generation = 0;

  constructor() {
    effect(() => {
      const tenantId = this.tenantId();
      const level = this.scopeType();
      const brandId = this.brandId();
      const locationId = this.locationId();
      untracked(() => void this.load(tenantId, level, brandId, locationId));
    });
  }

  private async load(
    tenantId: string | null,
    level: EditableScopeType,
    brandId: string | null,
    locationId: string | null,
  ): Promise<void> {
    if (tenantId === null || brandId === null) {
      return;
    }
    const generation = ++this.generation;
    this.rows.set(initialRows());
    const scope: LocationScope = { tenantId, brandId, locationId: locationId ?? '' };
    const here = level === 'LOCATION' && locationId !== null;

    const settle = (row: NeighbourRow): void => {
      if (generation === this.generation) {
        this.rows.update((rows) =>
          rows.map((candidate) => (candidate.id === row.id ? row : candidate)),
        );
      }
    };
    const read = async (
      id: string,
      produce: () => Promise<Pick<NeighbourRow, 'value' | 'noteKey'>>,
    ): Promise<void> => {
      const base = initialRows().find((row) => row.id === id) as NeighbourRow;
      try {
        settle({ ...base, ...(await produce()), unreadable: false });
      } catch {
        // Not every role that opens this screen can read every neighbour; that is not an error here.
        settle({ ...base, value: null, unreadable: true });
      }
    };

    await Promise.all([
      read('distanceMode', async () => {
        const list = await this.tariffs.list({ tenantId, brandId });
        const standard = list.find((tariff) => tariff.brandDefault && tariff.status === 'ACTIVE');
        if (!standard) {
          return { value: this.i18n.t('settings.orderPolicy.neighbours.distanceMode.none') };
        }
        return {
          value:
            standard.distanceMode === 'ROAD'
              ? this.i18n.t('settings.orderPolicy.neighbours.distanceMode.ROAD')
              : this.i18n.t('settings.orderPolicy.neighbours.distanceMode.RADIUS'),
        };
      }),
      read('branchLimit', async () => {
        // The limit is a property of one branch (row 10.2c) and there is no batched read of it: with the
        // bar on a branch it is read, and above that the row says where it is set instead of guessing.
        if (!here) {
          return { value: this.i18n.t('settings.orderPolicy.neighbours.branchLimit.perBranch') };
        }
        const summary = await this.locations.serviceSummary(scope);
        const limit = summary.maxConcurrentOrders;
        return {
          value:
            limit === null
              ? this.i18n.t('settings.orderPolicy.neighbours.branchLimit.none')
              : this.i18n.t('settings.orderPolicy.neighbours.branchLimit.value', { count: limit }),
        };
      }),
      read('outOfZone', async () => {
        const resolution = await this.configuration.resolution(
          tenantId,
          OUT_OF_ZONE_POLICY_CODE,
          level,
          brandId,
          locationId,
        );
        const policy = String(resolution.value ?? 'REJECT');
        return {
          value: this.i18n.t(outOfZoneKey(policy)),
          noteKey: 'settings.orderPolicy.neighbours.outOfZone.notApplied',
        };
      }),
      read('paidHold', async () => {
        const policy = await this.paidHold.policy(scope, level);
        return { value: this.i18n.t(paidHoldKey(policy.mode)) };
      }),
      read('callCentre', async () => {
        const list = await this.channels.list(scope);
        const callCentre = list.find(
          (channel) => channel.systemType === 'CALL_CENTRE' && channel.status === 'ACTIVE',
        );
        if (!callCentre) {
          return { value: this.i18n.t('settings.orderPolicy.neighbours.callCentre.none') };
        }
        const modes = callCentre.enabledFulfillmentModes
          .map((mode) => this.i18n.t(fulfilmentKey(mode)))
          .join(', ');
        return {
          value: this.i18n.t('settings.orderPolicy.neighbours.callCentre.value', {
            payments: callCentre.enabledPaymentMethodCount,
            modes: modes === '' ? '—' : modes,
          }),
        };
      }),
    ]);
  }
}

function initialRows(): readonly NeighbourRow[] {
  return [
    {
      id: 'distanceMode',
      labelKey: 'settings.orderPolicy.neighbours.distanceMode.label',
      link: '/delivery/tariffs',
      value: null,
      unreadable: false,
    },
    {
      id: 'branchLimit',
      labelKey: 'settings.orderPolicy.neighbours.branchLimit.label',
      link: '/settings/locations',
      value: null,
      unreadable: false,
    },
    {
      id: 'outOfZone',
      labelKey: 'settings.orderPolicy.neighbours.outOfZone.label',
      link: '/delivery/courier-policy',
      value: null,
      unreadable: false,
    },
    {
      id: 'paidHold',
      labelKey: 'settings.orderPolicy.neighbours.paidHold.label',
      link: '/kitchen/buffer',
      value: null,
      unreadable: false,
    },
    {
      id: 'callCentre',
      labelKey: 'settings.orderPolicy.neighbours.callCentre.label',
      link: '/settings/sales-channels',
      value: null,
      unreadable: false,
    },
  ];
}

/** Dynamically built message keys do not typecheck against `MessageKey`, so each closed value is spelled out. */
function outOfZoneKey(policy: string): MessageKey {
  switch (policy) {
    case 'OFFER_PICKUP':
      return 'settings.orderPolicy.neighbours.outOfZone.OFFER_PICKUP';
    case 'MANUAL_REVIEW':
      return 'settings.orderPolicy.neighbours.outOfZone.MANUAL_REVIEW';
    default:
      return 'settings.orderPolicy.neighbours.outOfZone.REJECT';
  }
}

function paidHoldKey(mode: string): MessageKey {
  switch (mode) {
    case 'HOLD_UNTIL_PAID':
      return 'settings.orderPolicy.neighbours.paidHold.HOLD_UNTIL_PAID';
    case 'OFF':
      return 'settings.orderPolicy.neighbours.paidHold.OFF';
    default:
      return 'settings.orderPolicy.neighbours.paidHold.SHADOW';
  }
}

function fulfilmentKey(mode: string): MessageKey {
  switch (mode) {
    case 'DELIVERY':
      return 'settings.orderPolicy.neighbours.mode.DELIVERY';
    case 'DINE_IN':
      return 'settings.orderPolicy.neighbours.mode.DINE_IN';
    default:
      return 'settings.orderPolicy.neighbours.mode.PICKUP';
  }
}
