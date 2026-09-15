import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { TimeZone, formatDateTime } from '../../core/format/datetime';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { ChartCategory } from '../../shared/ui/charts/chart-model';
import { BarChart } from '../../shared/ui/charts/bar-chart';
import { DonutChart } from '../../shared/ui/charts/donut-chart';
import { DeniedState } from '../../shared/ui/denied-state';
import { LockedState } from '../../shared/ui/locked-state';
import { ReportingApi } from '../reports/reporting-api';
import {
  PaymentMethodView,
  PaymentMethodsApi,
} from '../settings/payment-methods/payment-methods-api';
import { MyWorkApi, MyWorkChannelMixSlice } from './my-work-api';

/** Same placeholder as `today-page.ts`/`order-queue.ts` — no call in this chain returns a tenant timezone yet. */
const PLACEHOLDER_TIME_ZONE: TimeZone = 'Asia/Tashkent';

type BandState = 'loading' | 'ready' | 'denied' | 'error';

/** One slice of {@link MyWorkPage.paymentMix} — a payment method, named and totalled. */
interface PaymentMixSlice {
  readonly key: string;
  readonly label: string;
  readonly amountSom: number;
}

/**
 * IA 0.2 — My work: the signed-in operator's own page, at `/today/my-work`.
 *
 * **What "done" means here is narrower than the row it discharges, on
 * purpose.** The IA lists four capabilities under `0.2`: `0.2a` personal
 * statistics by channel and `0.2b` revenue by payment method, both built
 * below; `0.2c` personal data (own profile) and `0.2d` UI personalization,
 * both deferred behind the staff-identity ADR and rendered as a named,
 * honest lock rather than an empty section — the same "omit, do not
 * disable" rule `not-built-page.ts` follows elsewhere, applied inline
 * because the rest of this page is real. `0.2c`/`0.2d` keep their own rows
 * on the gap map; this page does not discharge them, and does not pretend
 * to by rendering a profile store with nothing in it.
 *
 * **Why this page can exist at all when the live board's own operator
 * leaderboard (`0.1d`) cannot.** Every other actor-facing read needs a
 * staff person record to turn a Keycloak subject into a name — the
 * dependency `0.1d`, `9.2` and a dozen other rows share. This page needs no
 * such lookup: `0.2a` asks "how many orders did *I* take today", which the
 * console can answer from the token's own subject with nobody's name
 * printed at all, staff directory or not.
 *
 * **The two bands are gated independently, not as one page-level state.**
 * `0.2a` reads `ORDER_READ`, which every operator this page is for already
 * holds; `0.2b` reads `REPORTING_READ` (P39's payment-mix), which a
 * front-line `location-staff` bundle does not. A cashier opening this page
 * sees their own channel tally and a named wall where the takings band
 * would be — never a blank page, and never the whole page reading "denied"
 * for a grant one band needs and the other does not.
 */
@Component({
  selector: 'q-my-work-page',
  imports: [TPipe, BarChart, DonutChart, DeniedState, LockedState],
  templateUrl: './my-work-page.html',
  styleUrl: './my-work-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MyWorkPage implements OnInit {
  private readonly location = inject(CurrentLocation);
  private readonly capabilities = inject(SessionCapabilities);
  private readonly myWorkApi = inject(MyWorkApi);
  private readonly reportingApi = inject(ReportingApi);
  private readonly paymentMethodsApi = inject(PaymentMethodsApi);
  protected readonly i18n = inject(I18n);

  protected readonly pageState = signal<BandState>('loading');

  protected readonly channelState = signal<BandState>('loading');
  protected readonly channelMix = signal<readonly MyWorkChannelMixSlice[]>([]);
  protected readonly channelPeriodFrom = signal<string | null>(null);

  protected readonly paymentState = signal<BandState>('loading');
  protected readonly paymentMix = signal<readonly PaymentMixSlice[]>([]);

  protected readonly channelItems = computed<readonly ChartCategory[]>(() =>
    this.channelMix().map((slice) => ({ key: slice.key, label: slice.key, value: slice.orders })),
  );

  protected readonly paymentItems = computed<readonly ChartCategory[]>(() =>
    this.paymentMix().map((row) => ({ key: row.key, label: row.label, value: row.amountSom })),
  );

  protected readonly paymentTotalDisplay = computed(() =>
    this.formatMoneyValue(this.paymentMix().reduce((sum, row) => sum + row.amountSom, 0)),
  );

  ngOnInit(): void {
    void this.load();
  }

  protected periodLabel(): string | null {
    const from = this.channelPeriodFrom();
    if (!from) {
      return null;
    }
    return this.i18n.t('myWork.channel.period', {
      from: formatDateTime(new Date(from), PLACEHOLDER_TIME_ZONE),
    });
  }

  protected formatMoneyValue(amountSom: number): string {
    return formatMoney({ amountMinor: amountSom, currency: 'UZS' }, this.i18n.locale(), {
      withUnit: true,
    });
  }

  private async load(): Promise<void> {
    await Promise.all([this.location.ensureLoaded(), this.capabilities.ensureLoaded()]);
    const scope = this.location.scope();
    if (!scope) {
      this.pageState.set(this.location.denied() ? 'denied' : 'error');
      return;
    }
    this.pageState.set('ready');
    await Promise.all([this.loadChannelMix(scope), this.loadPaymentMix(scope)]);
  }

  private async loadChannelMix(scope: LocationScope): Promise<void> {
    this.channelState.set('loading');
    try {
      const result = await this.myWorkApi.channelMix(scope);
      this.channelMix.set(result.channelMix);
      this.channelPeriodFrom.set(result.periodFrom);
      this.channelState.set('ready');
    } catch (error) {
      this.channelState.set(deniedOrError(error));
    }
  }

  private async loadPaymentMix(scope: LocationScope): Promise<void> {
    // A usability affordance, not the authorization decision (`SessionCapabilities`'s
    // own doc) — skipping the call for an operator who plainly does not hold
    // `REPORTING_READ` avoids a 403 that would only ever confirm what the
    // rail already knows, never hide anything the server would have served.
    if (!this.capabilities.has('REPORTING_READ')) {
      this.paymentState.set('denied');
      return;
    }
    this.paymentState.set('loading');
    try {
      const today = todayIn(PLACEHOLDER_TIME_ZONE);
      const [methods, mix] = await Promise.all([
        this.paymentMethodsApi.list(scope).catch(() => [] as readonly PaymentMethodView[]),
        this.reportingApi.paymentMix(scope.tenantId, {
          from: today,
          to: today,
          locationId: [scope.locationId],
        }),
      ]);
      const nameByCode = new Map(methods.map((method) => [method.code, method]));
      this.paymentMix.set(
        mix.overview
          .map((row) => {
            const method = nameByCode.get(row.paymentMethodCode);
            const label = method
              ? (method.localizedNames[this.i18n.locale()] ?? method.displayName)
              : row.paymentMethodCode;
            return { key: row.paymentMethodCode, label, amountSom: row.amountSom };
          })
          .sort((a, b) => b.amountSom - a.amountSom),
      );
      this.paymentState.set('ready');
    } catch (error) {
      this.paymentState.set(deniedOrError(error));
    }
  }
}

function deniedOrError(error: unknown): BandState {
  if (error instanceof ApiError && error.status === 403) {
    return 'denied';
  }
  return 'error';
}

/** `YYYY-MM-DD` for "today" in an IANA zone — the same technique `reports-filter-state.ts`'s `todayIn` uses. */
function todayIn(zone: string): string {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: zone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date());
}
