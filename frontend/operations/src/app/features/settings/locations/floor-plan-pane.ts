import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import {
  FloorPlanCanvas,
  TableMovedEvent,
} from '../../../shared/ui/floor-plan-canvas/floor-plan-canvas';
import { TablePrintCard } from '../../../shared/ui/table-print-card/table-print-card';
import { describeApiError } from '../../orders/order-errors';
import { DineInApi, DineInSettingsView, QrMode, SectionView, TableView } from './dinein-api';

/** couriers.md/ADR 0047: refused everywhere until a POS adapter declares both open-ticket ports. */
const QR_MODES: readonly { readonly value: QrMode; readonly selectable: boolean }[] = [
  { value: 'VIEW_ONLY', selectable: true },
  { value: 'ORDER_AND_PAY', selectable: true },
  { value: 'SETTLE_OPEN_TICKET', selectable: false },
];

/**
 * Rows `10.2d`/`X.36`/`10.5b` (wave P38) — the floor-plan tab under
 * Settings → Locations. Pure wiring over a finished backend:
 * `FloorPlanController`'s `GET`/`PUT /dine-in/settings`, sections, tables,
 * `PUT /tables/{tableId}` (new this wave) and `POST
 * /tables/{tableId}/qr-token-rotations` all existed with no caller in this
 * app before this wave.
 *
 * **`SETTLE_OPEN_TICKET` renders disabled with its reason, not as a missing
 * option.** `QrMode.require` refuses it server-side (ADR 0011: an
 * unsupported provider capability may never be the sole business path) and
 * V0034's own CHECK constraint refuses it again at the database — this is a
 * declared, permanent refusal until a POS adapter exists, not a build gap,
 * so the `<option>` stays in the list, `disabled`, with the reason as its
 * own line rather than being silently omitted.
 */
@Component({
  selector: 'q-floor-plan-pane',
  imports: [TPipe, FloorPlanCanvas, TablePrintCard],
  templateUrl: './floor-plan-pane.html',
  styleUrl: './floor-plan-pane.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FloorPlanPane {
  private readonly api = inject(DineInApi);
  protected readonly i18n = inject(I18n);

  readonly scope = input.required<LocationScope>();
  readonly branchName = input.required<string>();

  protected readonly qrModes = QR_MODES;

  // -------------------------------------------------------------- settings

  protected readonly settingsLoading = signal(true);
  protected readonly settingsError = signal<string | null>(null);
  protected readonly settings = signal<DineInSettingsView | null>(null);
  protected readonly editingSettings = signal(false);
  protected readonly settingsSaving = signal(false);
  protected readonly settingsSaveError = signal<string | null>(null);

  protected readonly draftQrMode = signal<QrMode>('VIEW_ONLY');
  protected readonly draftTurnaroundMinutes = signal(15);
  protected readonly draftGuestSessionTtlMinutes = signal(240);
  protected readonly draftServiceChargeRateBp = signal(0);
  protected readonly draftSettingsReason = signal('');

  // ------------------------------------------------------- sections/tables

  protected readonly floorLoading = signal(true);
  protected readonly floorError = signal<string | null>(null);
  protected readonly sections = signal<readonly SectionView[]>([]);
  protected readonly tables = signal<readonly TableView[]>([]);
  protected readonly activeSectionId = signal<string | null>(null);

  protected readonly tablesInSection = computed(() => {
    const sectionId = this.activeSectionId();
    return sectionId ? this.tables().filter((table) => table.sectionId === sectionId) : [];
  });

  protected readonly addingSection = signal(false);
  protected readonly draftSectionCode = signal('');
  protected readonly draftSectionName = signal('');
  protected readonly sectionSaving = signal(false);
  protected readonly sectionSaveError = signal<string | null>(null);

  protected readonly addingTable = signal(false);
  protected readonly draftTableCode = signal('');
  protected readonly draftTableName = signal('');
  protected readonly draftTableSeats = signal(2);
  protected readonly tableSaving = signal(false);
  protected readonly tableSaveError = signal<string | null>(null);

  protected readonly movingTableId = signal<string | null>(null);

  // ------------------------------------------------------------------- QR

  protected readonly selectedTableId = signal<string | null>(null);
  protected readonly selectedTable = computed(
    () => this.tables().find((table) => table.tableId === this.selectedTableId()) ?? null,
  );
  protected readonly rotating = signal(false);
  protected readonly rotateError = signal<string | null>(null);
  protected readonly rotateReason = signal('');
  /** Held only in memory, only until the panel closes — never re-fetchable (see `FloorPlanController`'s own doc). */
  protected readonly lastIssuedToken = signal<string | null>(null);
  protected readonly lastRevokedGuestSessions = signal<number | null>(null);

  constructor() {
    // Re-loads whenever the branch this pane is scoped to changes — the
    // same "effect keyed on the input" idiom `location-detail-pane.ts`
    // itself uses, since the default RouteReuseStrategy re-uses this
    // component instance across a `:locationId` change.
    effect(() => {
      const scope = this.scope();
      void this.loadSettings(scope);
      void this.loadFloor(scope);
    });
  }

  // ------------------------------------------------------------- settings

  private async loadSettings(scope: LocationScope): Promise<void> {
    this.settingsLoading.set(true);
    this.settingsError.set(null);
    try {
      this.settings.set(await this.api.settings(scope));
    } catch (error) {
      this.settingsError.set(this.describe(error));
    } finally {
      this.settingsLoading.set(false);
    }
  }

  protected startEditingSettings(): void {
    const current = this.settings();
    if (current) {
      this.draftQrMode.set(current.qrMode);
      this.draftTurnaroundMinutes.set(current.turnaroundMinutes);
      this.draftGuestSessionTtlMinutes.set(current.guestSessionTtlMinutes);
      this.draftServiceChargeRateBp.set(current.serviceChargeRateBp);
    }
    this.draftSettingsReason.set('');
    this.settingsSaveError.set(null);
    this.editingSettings.set(true);
  }

  protected cancelEditingSettings(): void {
    this.editingSettings.set(false);
  }

  /** The service charge field is entered as a percentage; the wire carries basis points. */
  protected setServiceChargePercent(percent: number): void {
    this.draftServiceChargeRateBp.set(Math.round(percent * 100));
  }

  protected canSaveSettings(): boolean {
    return !this.settingsSaving() && this.draftSettingsReason().trim().length > 0;
  }

  protected async saveSettings(): Promise<void> {
    if (!this.canSaveSettings()) {
      return;
    }
    this.settingsSaving.set(true);
    this.settingsSaveError.set(null);
    try {
      const updated = await this.api.configure(this.scope(), {
        qrMode: this.draftQrMode(),
        turnaroundMinutes: this.draftTurnaroundMinutes(),
        guestSessionTtlMinutes: this.draftGuestSessionTtlMinutes(),
        serviceChargeRateBp: this.draftServiceChargeRateBp(),
        reason: this.draftSettingsReason().trim(),
      });
      this.settings.set(updated);
      this.editingSettings.set(false);
    } catch (error) {
      this.settingsSaveError.set(this.describe(error));
    } finally {
      this.settingsSaving.set(false);
    }
  }

  // ------------------------------------------------------- sections/tables

  private async loadFloor(scope: LocationScope): Promise<void> {
    this.floorLoading.set(true);
    this.floorError.set(null);
    try {
      const [sections, tables] = await Promise.all([
        this.api.sections(scope),
        this.api.tables(scope),
      ]);
      this.sections.set(sections);
      this.tables.set(tables);
      if (!this.activeSectionId() && sections.length > 0) {
        this.activeSectionId.set(sections[0].sectionId);
      }
    } catch (error) {
      this.floorError.set(this.describe(error));
    } finally {
      this.floorLoading.set(false);
    }
  }

  protected selectSection(sectionId: string): void {
    this.activeSectionId.set(sectionId);
    this.selectedTableId.set(null);
  }

  protected startAddingSection(): void {
    this.draftSectionCode.set('');
    this.draftSectionName.set('');
    this.sectionSaveError.set(null);
    this.addingSection.set(true);
  }

  protected cancelAddingSection(): void {
    this.addingSection.set(false);
  }

  protected canSaveSection(): boolean {
    return (
      !this.sectionSaving() &&
      this.draftSectionCode().trim().length > 0 &&
      this.draftSectionName().trim().length > 0
    );
  }

  protected async saveSection(): Promise<void> {
    if (!this.canSaveSection()) {
      return;
    }
    this.sectionSaving.set(true);
    this.sectionSaveError.set(null);
    try {
      const created = await this.api.createSection(this.scope(), {
        code: this.draftSectionCode().trim(),
        displayName: this.draftSectionName().trim(),
      });
      this.sections.set([...this.sections(), created]);
      this.activeSectionId.set(created.sectionId);
      this.addingSection.set(false);
    } catch (error) {
      this.sectionSaveError.set(this.describe(error));
    } finally {
      this.sectionSaving.set(false);
    }
  }

  protected startAddingTable(): void {
    this.draftTableCode.set('');
    this.draftTableName.set('');
    this.draftTableSeats.set(2);
    this.tableSaveError.set(null);
    this.addingTable.set(true);
  }

  protected cancelAddingTable(): void {
    this.addingTable.set(false);
  }

  protected canSaveTable(): boolean {
    return (
      !this.tableSaving() &&
      !!this.activeSectionId() &&
      this.draftTableCode().trim().length > 0 &&
      this.draftTableName().trim().length > 0 &&
      this.draftTableSeats() > 0
    );
  }

  protected async saveTable(): Promise<void> {
    const sectionId = this.activeSectionId();
    if (!sectionId || !this.canSaveTable()) {
      return;
    }
    this.tableSaving.set(true);
    this.tableSaveError.set(null);
    try {
      // New tables land at the canvas's own top-left corner and are dragged
      // into place afterwards — this pane does not ask an operator to guess
      // pixel coordinates for a table they have not placed yet.
      const created = await this.api.createTable(this.scope(), {
        sectionId,
        code: this.draftTableCode().trim(),
        displayName: this.draftTableName().trim(),
        seats: this.draftTableSeats(),
        layoutX: 20,
        layoutY: 20,
      });
      this.tables.set([...this.tables(), created]);
      this.addingTable.set(false);
    } catch (error) {
      this.tableSaveError.set(this.describe(error));
    } finally {
      this.tableSaving.set(false);
    }
  }

  protected async onTableMoved(event: TableMovedEvent): Promise<void> {
    const table = this.tables().find((candidate) => candidate.tableId === event.tableId);
    if (!table) {
      return;
    }
    this.movingTableId.set(event.tableId);
    try {
      const moved = await this.api.moveTable(
        this.scope(),
        event.tableId,
        event.layoutX,
        event.layoutY,
        this.i18n.t('settings.locations.floorPlan.moveReason'),
        table.version,
      );
      this.tables.set(
        this.tables().map((candidate) => (candidate.tableId === moved.tableId ? moved : candidate)),
      );
    } catch {
      // A stale version (another manager moved it first) or a network
      // failure both resolve the same way: re-read the branch's tables so
      // the canvas snaps back to whatever is actually stored, rather than
      // leaving the dragged token sitting somewhere that was never saved.
      await this.loadFloor(this.scope());
    } finally {
      this.movingTableId.set(null);
    }
  }

  // ------------------------------------------------------------------- QR

  protected onTableSelected(tableId: string): void {
    this.selectedTableId.set(tableId);
    this.lastIssuedToken.set(null);
    this.lastRevokedGuestSessions.set(null);
    this.rotateReason.set('');
    this.rotateError.set(null);
  }

  protected closeTablePanel(): void {
    this.selectedTableId.set(null);
    this.lastIssuedToken.set(null);
    this.lastRevokedGuestSessions.set(null);
  }

  protected canRotate(): boolean {
    return !this.rotating() && this.rotateReason().trim().length > 0;
  }

  protected async rotateQrToken(): Promise<void> {
    const table = this.selectedTable();
    if (!table || !this.canRotate()) {
      return;
    }
    this.rotating.set(true);
    this.rotateError.set(null);
    try {
      const rotated = await this.api.rotateQrToken(
        this.scope(),
        table.tableId,
        this.rotateReason().trim(),
        table.version,
      );
      this.lastIssuedToken.set(rotated.qrToken);
      this.lastRevokedGuestSessions.set(rotated.revokedGuestSessions);
      this.rotateReason.set('');
      this.tables.set(
        this.tables().map((candidate) =>
          candidate.tableId === table.tableId
            ? {
                ...candidate,
                qrIssued: true,
                qrRotatedAt: rotated.rotatedAt,
                version: rotated.version,
              }
            : candidate,
        ),
      );
    } catch (error) {
      this.rotateError.set(this.describe(error));
    } finally {
      this.rotating.set(false);
    }
  }

  protected qrModeLabel(mode: QrMode): string {
    switch (mode) {
      case 'VIEW_ONLY':
        return this.i18n.t('settings.locations.floorPlan.qrMode.VIEW_ONLY');
      case 'ORDER_AND_PAY':
        return this.i18n.t('settings.locations.floorPlan.qrMode.ORDER_AND_PAY');
      case 'SETTLE_OPEN_TICKET':
        return this.i18n.t('settings.locations.floorPlan.qrMode.SETTLE_OPEN_TICKET');
    }
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
