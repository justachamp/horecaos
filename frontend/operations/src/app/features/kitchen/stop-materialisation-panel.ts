import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';

import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { TimeZone, formatDateTime } from '../../core/format/datetime';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import {
  MaterialisationLine,
  MaterialisationRun,
  StopMaterialisationApi,
} from './stop-materialisation-api';

/** See `stop-list-page.ts`'s identical constant: no location carries a timezone on this response yet. */
const PLACEHOLDER_TIME_ZONE: TimeZone = 'Asia/Tashkent';

/**
 * The report reasons the platform can give (`inventory.stop_materialisation_lines.reason_code`). One literal
 * `t` key per case, so a code the platform adds later is a build error here and not a raw code on screen.
 */
const REASON_KEYS: Readonly<Record<string, MessageKey>> = {
  UNTRACKED_ITEM: 'kitchen.stopList.decommission.reason.UNTRACKED_ITEM',
  QUANTITY_ITEM: 'kitchen.stopList.decommission.reason.QUANTITY_ITEM',
  CHANNEL_SCOPE: 'kitchen.stopList.decommission.reason.CHANNEL_SCOPE',
  MENU_NOT_EVERY_CHANNEL: 'kitchen.stopList.decommission.reason.MENU_NOT_EVERY_CHANNEL',
  WRITE_FAILED: 'kitchen.stopList.decommission.reason.WRITE_FAILED',
};

const SCOPE_KEYS: Readonly<Record<string, MessageKey>> = {
  LOCATION: 'kitchen.stopList.scope.location',
  BRAND: 'kitchen.stopList.scope.brand',
  MENU: 'kitchen.stopList.scope.menu',
  CHANNEL: 'kitchen.stopList.scope.channel',
};

/**
 * Switching stops off: the materialisation run and its report (gap map row `2.5a`, ADR 0141 rollback switch
 * three).
 *
 * Turning stops off (`inventory.stops.read_enabled`) is the only switch that can sell a stopped dish again, so
 * the platform refuses to turn until a **run** has written every stop it can onto the dish's own position and a
 * holder of `inventory.stop.manage` has **read what it could not carry and accepted it**. This panel is where
 * that is done: start the run, read the report, acknowledge it. The report is the one place a stop can vanish,
 * so it names each dish and branch (resolved when the report is read, never copied into a table), why the stop
 * could not land, and whether it was a write that failed (which keeps the switch blocked until a later run
 * carries it).
 *
 * The panel never turns the switch itself. The key is a platform setting a tenant cannot see; a platform
 * administrator writes it, with the runbook in front of them, once this panel shows an acknowledged run and no
 * stop has been made since.
 */
@Component({
  selector: 'q-stop-materialisation-panel',
  imports: [TPipe],
  templateUrl: './stop-materialisation-panel.html',
  styleUrl: './stop-materialisation-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StopMaterialisationPanel implements OnInit {
  private readonly api = inject(StopMaterialisationApi);
  private readonly location = inject(CurrentLocation);
  private readonly capabilities = inject(SessionCapabilities);
  protected readonly i18n = inject(I18n);

  /** Whether the operator holds the brand-wide stop capability anywhere; the server decides for real. */
  protected readonly canManage = computed(() => this.capabilities.has('INVENTORY_STOP_MANAGE'));

  protected readonly loading = signal(true);
  protected readonly run = signal<MaterialisationRun | null>(null);
  protected readonly lines = signal<readonly MaterialisationLine[]>([]);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly starting = signal(false);
  protected readonly loadingLines = signal(false);
  protected readonly acknowledging = signal(false);
  protected readonly accepted = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly canAcknowledge = computed(() => {
    const run = this.run();
    return (
      run !== null &&
      run.status === 'COMPLETED' &&
      !run.acknowledged &&
      this.accepted() &&
      !this.acknowledging() &&
      !this.starting()
    );
  });

  async ngOnInit(): Promise<void> {
    await this.capabilities.ensureLoaded();
    if (!this.canManage()) {
      this.loading.set(false);
      return;
    }
    await this.refresh();
  }

  /** Reads the newest run and the first page of its report. */
  protected async refresh(): Promise<void> {
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.loading.set(false);
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    try {
      const latest = await this.api.latest(scope);
      this.run.set(latest);
      this.lines.set([]);
      this.nextCursor.set(null);
      this.accepted.set(false);
      if (latest) {
        await this.loadLines(null);
      }
    } catch (failure) {
      this.error.set(this.describe(failure));
    } finally {
      this.loading.set(false);
    }
  }

  protected async start(): Promise<void> {
    const scope = this.location.scope();
    if (!scope || this.starting()) {
      return;
    }
    this.starting.set(true);
    this.error.set(null);
    try {
      const finished = await this.api.start(scope);
      this.run.set(finished);
      this.lines.set([]);
      this.nextCursor.set(null);
      this.accepted.set(false);
      await this.loadLines(null);
    } catch (failure) {
      this.error.set(this.describe(failure));
    } finally {
      this.starting.set(false);
    }
  }

  protected async more(): Promise<void> {
    const cursor = this.nextCursor();
    if (cursor !== null) {
      await this.loadLines(cursor);
    }
  }

  protected async acknowledge(): Promise<void> {
    const scope = this.location.scope();
    const run = this.run();
    if (!scope || !run || !this.canAcknowledge()) {
      return;
    }
    this.acknowledging.set(true);
    this.error.set(null);
    try {
      this.run.set(await this.api.acknowledge(scope, run));
    } catch (failure) {
      this.error.set(this.describe(failure));
    } finally {
      this.acknowledging.set(false);
    }
  }

  protected toggleAccepted(checked: boolean): void {
    this.accepted.set(checked);
  }

  // ---------------------------------------------------------------- presentation

  protected statusLabel(run: MaterialisationRun): string {
    if (run.acknowledged) {
      return this.i18n.t('kitchen.stopList.decommission.status.acknowledged');
    }
    switch (run.status) {
      case 'COMPLETED':
        return this.i18n.t('kitchen.stopList.decommission.status.completed');
      case 'RUNNING':
        return this.i18n.t('kitchen.stopList.decommission.status.running');
      default:
        return this.i18n.t('kitchen.stopList.decommission.status.failed');
    }
  }

  protected when(instant: string | null | undefined): string {
    return instant ? formatDateTime(new Date(instant), PLACEHOLDER_TIME_ZONE) : '—';
  }

  protected reasonLabel(line: MaterialisationLine): string {
    const key = REASON_KEYS[line.reasonCode];
    return key ? this.i18n.t(key) : line.reasonCode;
  }

  protected scopeLabel(line: MaterialisationLine): string {
    const key = SCOPE_KEYS[line.scopeType];
    return key ? this.i18n.t(key) : line.scopeType;
  }

  protected dishLabel(line: MaterialisationLine): string {
    return line.variantName ?? line.variantId;
  }

  protected branchLabel(line: MaterialisationLine): string {
    if (!line.locationId) {
      return this.i18n.t('kitchen.stopList.decommission.everyBranch');
    }
    return line.locationName ?? line.locationId;
  }

  // ----------------------------------------------------------------- internals

  private async loadLines(cursor: string | null): Promise<void> {
    const scope = this.location.scope();
    const run = this.run();
    if (!scope || !run) {
      return;
    }
    this.loadingLines.set(true);
    try {
      const page = await this.api.report(scope, run.id, cursor);
      this.lines.set(cursor === null ? page.items : [...this.lines(), ...page.items]);
      this.nextCursor.set(page.nextCursor);
    } catch (failure) {
      this.error.set(this.describe(failure));
    } finally {
      this.loadingLines.set(false);
    }
  }

  private describe(failure: unknown): string {
    if (failure instanceof ApiError) {
      return describeApiError(failure, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
