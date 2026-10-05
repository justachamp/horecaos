import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';

import { firstValueFrom } from 'rxjs';

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError } from '../../../core/api/problem-details';
import { SessionCapabilities } from '../../../core/auth/session-capabilities';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import {
  FloorPlanCanvas,
  TableMovedEvent,
} from '../../../shared/ui/floor-plan-canvas/floor-plan-canvas';
import { TablePrintCard } from '../../../shared/ui/table-print-card/table-print-card';
import { describeApiError } from '../../orders/order-errors';
import { PartyClose } from '../../orders/party-close';
import { ReservationsApi } from '../../orders/reservations-api';
import {
  SESSION_CURRENCY,
  SessionView,
  TableSessionsApi,
  isUnconfirmedClaim,
} from '../../orders/table-sessions-api';
import { DineInApi, DineInSettingsView, QrMode, SectionView, TableView } from './dinein-api';

/**
 * How far ahead a confirmed booking counts as "soon" when seating a walk-in.
 * Advisory only -- `table-availability`'s `booked` flag is, in its own words,
 * a read and not a hold, and a host who knows the party will be gone by then may
 * seat them regardless. Nothing here refuses: staff keep every override (ADR 0143,
 * Decision 8), and the guest-side horizon is the branch's own setting, which the
 * server applies to a guest's self-seating and never to this screen.
 */
const BOOKED_SOON_WINDOW_MINUTES = 90;

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
  imports: [TPipe, FloorPlanCanvas, TablePrintCard, PartyClose],
  templateUrl: './floor-plan-pane.html',
  styleUrl: './floor-plan-pane.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FloorPlanPane {
  private readonly api = inject(DineInApi);
  private readonly sessionsApi = inject(TableSessionsApi);
  private readonly reservationsApi = inject(ReservationsApi);
  private readonly capabilities = inject(SessionCapabilities);
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
  /** ADR 0143: the self-seating switch and its numbers, as the proposal's own defaults until a branch's are loaded. */
  protected readonly draftWalkInSelfSeat = signal(false);
  protected readonly draftWalkInClaimTtlMinutes = signal(15);
  protected readonly draftWalkInHorizonMinutes = signal(90);
  protected readonly draftWalkInMaxUnconfirmed = signal(5);
  protected readonly draftWalkInDailyClaimsPerAccount = signal(3);
  protected readonly draftWalkInPaymentDeferMinutes = signal(30);
  protected readonly draftSettingsReason = signal('');

  // ------------------------------------------------------- sections/tables

  protected readonly floorLoading = signal(true);
  protected readonly floorError = signal<string | null>(null);
  protected readonly sections = signal<readonly SectionView[]>([]);
  protected readonly tables = signal<readonly TableView[]>([]);
  protected readonly activeSectionId = signal<string | null>(null);

  protected readonly tablesInSection = computed(() => {
    const sectionId = this.activeSectionId();
    if (!sectionId) {
      return [];
    }
    const occupied = this.occupiedTableIds();
    const claims = this.claimByTableId();
    return this.tables()
      .filter((table) => table.sectionId === sectionId)
      .map((table) => {
        const claim = claims.get(table.tableId);
        return {
          ...table,
          occupied: occupied.has(table.tableId),
          selfSeated: claim !== undefined,
          claimUnconfirmed: claim !== undefined && isUnconfirmedClaim(claim),
        };
      });
  });

  // ---------------------------------------------------------------- seating

  /** Every party not yet closed, and null until the room has been read (or when the read was refused). */
  protected readonly liveSessions = signal<readonly SessionView[] | null>(null);
  /** Tables a confirmed booking holds inside {@link BOOKED_SOON_WINDOW_MINUTES}. */
  private readonly bookedSoonTableIds = signal<ReadonlySet<string>>(new Set());

  /**
   * The guest-opened sessions (ADR 0143), by the table each sits at -- the host sees at a
   * glance which parties seated themselves and which of those nobody has confirmed.
   */
  private readonly claimByTableId = computed<ReadonlyMap<string, SessionView>>(() => {
    const byTable = new Map<string, SessionView>();
    for (const session of this.liveSessions() ?? []) {
      if (session.origin === 'GUEST_QR') {
        for (const table of session.tables) {
          byTable.set(table.tableId, session);
        }
      }
    }
    return byTable;
  });

  private readonly occupiedTableIds = computed<ReadonlySet<string>>(
    () =>
      new Set(
        (this.liveSessions() ?? []).flatMap((session) => session.tables.map((t) => t.tableId)),
      ),
  );

  protected readonly bookedSoonMinutes = BOOKED_SOON_WINDOW_MINUTES;
  protected readonly seatPartySize = signal(2);
  protected readonly seatReason = signal('');
  protected readonly seating = signal(false);
  protected readonly seatError = signal<string | null>(null);
  protected readonly seatedNotice = signal<string | null>(null);

  /**
   * A usability affordance, never the authorization decision -- the server
   * re-checks `dinein.session.manage` at this branch on the call itself
   * (`SessionCapabilities`' own doc).
   */
  protected canManageSessions(): boolean {
    return this.capabilities.has('DINEIN_SESSION_MANAGE');
  }

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
    // Its own effect: it also follows the operator's capabilities, which settle
    // after the session context loads, and that must not re-read the floor plan.
    effect(() => {
      void this.loadRoom(this.scope());
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
      this.draftWalkInSelfSeat.set(current.walkInSelfSeat);
      this.draftWalkInClaimTtlMinutes.set(current.walkInClaimTtlMinutes);
      this.draftWalkInHorizonMinutes.set(current.walkInHorizonMinutes);
      this.draftWalkInMaxUnconfirmed.set(current.walkInMaxUnconfirmed);
      this.draftWalkInDailyClaimsPerAccount.set(current.walkInDailyClaimsPerAccount);
      this.draftWalkInPaymentDeferMinutes.set(current.walkInPaymentDeferMinutes);
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
      const updated = await this.api.configure(
        this.scope(),
        {
          qrMode: this.draftQrMode(),
          turnaroundMinutes: this.draftTurnaroundMinutes(),
          guestSessionTtlMinutes: this.draftGuestSessionTtlMinutes(),
          serviceChargeRateBp: this.draftServiceChargeRateBp(),
          walkInSelfSeat: this.draftWalkInSelfSeat(),
          walkInClaimTtlMinutes: this.draftWalkInClaimTtlMinutes(),
          walkInHorizonMinutes: this.draftWalkInHorizonMinutes(),
          walkInMaxUnconfirmed: this.draftWalkInMaxUnconfirmed(),
          walkInDailyClaimsPerAccount: this.draftWalkInDailyClaimsPerAccount(),
          walkInPaymentDeferMinutes: this.draftWalkInPaymentDeferMinutes(),
          reason: this.draftSettingsReason().trim(),
        },
        // The version the screen read: a never-configured branch reads as 0, and a
        // second manager's edit since then is a refusal here, not a silent overwrite.
        this.settings()?.version ?? 0,
      );
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

  // -------------------------------------------------------------- seating

  /**
   * Who is sitting where, and which tables a booking holds soon. Two advisory
   * reads (`DINEIN_SESSION_READ`, `RESERVATION_READ`) that only a host who can
   * seat a party is shown the result of; a refusal leaves {@link liveSessions}
   * null, which hides the action rather than offering one the pane cannot judge.
   */
  private async loadRoom(scope: LocationScope): Promise<void> {
    if (!this.canManageSessions()) {
      this.liveSessions.set(null);
      return;
    }
    const now = new Date();
    const until = new Date(now.getTime() + BOOKED_SOON_WINDOW_MINUTES * 60_000);
    try {
      const [live, availability] = await Promise.all([
        firstValueFrom(this.sessionsApi.live(scope)),
        this.reservationsApi.availability(scope, now.toISOString(), until.toISOString()),
      ]);
      this.liveSessions.set(live);
      this.bookedSoonTableIds.set(
        new Set(availability.filter((table) => table.booked).map((table) => table.tableId)),
      );
    } catch {
      this.liveSessions.set(null);
      this.bookedSoonTableIds.set(new Set());
    }
  }

  protected isOccupied(table: TableView): boolean {
    return this.occupiedTableIds().has(table.tableId);
  }

  protected isBookedSoon(table: TableView): boolean {
    return this.bookedSoonTableIds().has(table.tableId);
  }

  /** The table's seat action is offered only where the room is known and the table is free and in service. */
  protected canSeat(table: TableView): boolean {
    return (
      this.canManageSessions() &&
      this.liveSessions() !== null &&
      table.status === 'ACTIVE' &&
      !this.isOccupied(table) &&
      this.seatPartySize() >= 1 &&
      this.seatReason().trim().length > 0 &&
      !this.seating()
    );
  }

  protected async seatWalkIn(): Promise<void> {
    const table = this.selectedTable();
    if (!table || !this.canSeat(table)) {
      return;
    }
    this.seating.set(true);
    this.seatError.set(null);
    this.seatedNotice.set(null);
    try {
      // No reservation: a walk-in, which is most covers (ADR 0047). The same endpoint
      // and the same capability the reservations screen seats a booking through.
      const opened = await firstValueFrom(
        this.sessionsApi.open(this.scope(), {
          tableIds: [table.tableId],
          partySize: this.seatPartySize(),
          currency: SESSION_CURRENCY,
          reason: this.seatReason().trim(),
        }),
      );
      this.liveSessions.set([...(this.liveSessions() ?? []), opened]);
      this.seatedNotice.set(
        this.i18n.t('settings.locations.floorPlan.seat.done', { table: table.code }),
      );
    } catch (error) {
      if (error instanceof ApiError && error.problem?.['conflict'] === 'TABLE_OCCUPIED') {
        this.seatError.set(this.i18n.t('settings.locations.floorPlan.seat.errorOccupied'));
        // Somebody seated it in the instant since the room was read: read it again.
        await this.loadRoom(this.scope());
      } else {
        this.seatError.set(this.describe(error));
      }
    } finally {
      this.seating.set(false);
    }
  }

  // ------------------------------------------------- a guest's self-seated claim

  /** The live session at a table, if a party is there. */
  protected sessionAt(table: TableView): SessionView | null {
    return (
      (this.liveSessions() ?? []).find((session) =>
        session.tables.some((t) => t.tableId === table.tableId),
      ) ?? null
    );
  }

  /** The party at this table seated themselves from its code and nobody has confirmed it (ADR 0143). */
  protected unconfirmedClaimAt(table: TableView): SessionView | null {
    const session = this.sessionAt(table);
    return session && isUnconfirmedClaim(session) ? session : null;
  }

  /** The party at this table seated themselves, confirmed or not. */
  protected selfSeatedAt(table: TableView): boolean {
    return this.sessionAt(table)?.origin === 'GUEST_QR';
  }

  protected readonly claimReason = signal('');
  protected readonly claimBusy = signal(false);
  protected readonly claimError = signal<string | null>(null);
  protected readonly claimNotice = signal<string | null>(null);

  protected canActOnClaim(): boolean {
    return this.canManageSessions() && this.claimReason().trim().length > 0 && !this.claimBusy();
  }

  /** Keeps a guest's self-seated table for them, so it does not lapse under a host who has taken charge. */
  protected async confirmClaim(table: TableView): Promise<void> {
    const claim = this.unconfirmedClaimAt(table);
    if (!claim || !this.canActOnClaim()) {
      return;
    }
    this.claimBusy.set(true);
    this.claimError.set(null);
    this.claimNotice.set(null);
    try {
      const confirmed = await firstValueFrom(
        this.sessionsApi.confirmClaim(
          this.scope(),
          claim.sessionId,
          this.claimReason().trim(),
          claim.version,
        ),
      );
      this.liveSessions.set(
        (this.liveSessions() ?? []).map((session) =>
          session.sessionId === confirmed.sessionId ? confirmed : session,
        ),
      );
      this.claimNotice.set(
        this.i18n.t('settings.locations.floorPlan.claim.kept', { table: table.code }),
      );
    } catch (error) {
      this.claimError.set(this.describe(error));
      // Somebody moved it first (a round confirmed it, the sweeper gave it back): read the room again.
      await this.loadRoom(this.scope());
    } finally {
      this.claimBusy.set(false);
    }
  }

  /** Gives a guest's unconfirmed claim back to the room now, rather than at its window's end. */
  protected async releaseClaim(table: TableView): Promise<void> {
    const claim = this.unconfirmedClaimAt(table);
    if (!claim || !this.canActOnClaim()) {
      return;
    }
    this.claimBusy.set(true);
    this.claimError.set(null);
    this.claimNotice.set(null);
    try {
      await firstValueFrom(
        this.sessionsApi.release(
          this.scope(),
          claim.sessionId,
          this.claimReason().trim(),
          claim.version,
        ),
      );
      this.liveSessions.set(
        (this.liveSessions() ?? []).filter((session) => session.sessionId !== claim.sessionId),
      );
      this.claimNotice.set(
        this.i18n.t('settings.locations.floorPlan.claim.released', { table: table.code }),
      );
    } catch (error) {
      this.claimError.set(this.describe(error));
      await this.loadRoom(this.scope());
    } finally {
      this.claimBusy.set(false);
    }
  }

  /** The party at this table, when it is the kind «Закрыть стол» is for: seated by staff, or a guest's claim that was confirmed. An unconfirmed claim has its own keep-or-release above. */
  protected closablePartyAt(table: TableView): SessionView | null {
    const session = this.sessionAt(table);
    return session !== null && !isUnconfirmedClaim(session) ? session : null;
  }

  /** A party was closed from this panel (row `10.2d`): it leaves the room, and what it freed is read again. */
  protected async onPartyClosed(sessionId: string): Promise<void> {
    this.liveSessions.set(
      (this.liveSessions() ?? []).filter((session) => session.sessionId !== sessionId),
    );
    this.seatedNotice.set(null);
    await this.loadRoom(this.scope());
  }

  /** Someone moved the party first, or the close was refused: what the plan shows is out of date. */
  protected async onPartyStale(): Promise<void> {
    await this.loadRoom(this.scope());
  }

  /** When the claim gives the table back, as a time the host reads off a clock. */
  protected claimEndsAt(claim: SessionView): string {
    if (!claim.claimExpiresAt) {
      return '';
    }
    return new Date(claim.claimExpiresAt).toLocaleTimeString(this.i18n.locale(), {
      hour: '2-digit',
      minute: '2-digit',
    });
  }

  // ------------------------------------------------------------------- QR

  protected onTableSelected(tableId: string): void {
    this.claimReason.set(this.i18n.t('settings.locations.floorPlan.claim.defaultReason'));
    this.claimError.set(null);
    this.claimNotice.set(null);
    this.selectedTableId.set(tableId);
    const chosen = this.tables().find((table) => table.tableId === tableId);
    this.seatPartySize.set(chosen ? Math.min(2, Math.max(1, chosen.seats)) : 2);
    this.seatReason.set(this.i18n.t('settings.locations.floorPlan.seat.defaultReason'));
    this.seatError.set(null);
    this.seatedNotice.set(null);
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
