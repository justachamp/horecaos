import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { describeApiError } from '../orders/order-errors';
import {
  DeviceStationRef,
  KitchenDeviceClass,
  KitchenDeviceView,
  KitchenDevicesApi,
  PendingEnrolmentView,
} from './devices-api';
import { KitchenApi, StationResponse } from './kitchen-api';

/** The classes a request for `requested` may be approved as: an approval narrows and never widens (ADR 0151). */
const APPROVABLE_AS: Readonly<Record<KitchenDeviceClass, readonly KitchenDeviceClass[]>> = {
  KITCHEN_KDS: ['KITCHEN_KDS', 'KITCHEN_VDU'],
  KITCHEN_VDU: ['KITCHEN_VDU'],
};

/**
 * IA row `2/X.2` — Kitchen › Devices (ADR 0079). The console half of the
 * pairing handshake `device/device-shell.ts` runs on the tablet's own side:
 * a manager reads the `userCode` off the new screen and types it here, never
 * scans it — ADR 0079's own decision, not reopened by this screen or by
 * `q-qr-code` (`shared/ui/qr-code.ts`), which is a *display* component only.
 *
 * **Built:** list (active and revoked alike), approve-by-typed-user-code,
 * revoke-with-a-reason. Since ADR 0151 the page also shows each device's class
 * (and the class it asked for, when the approver gave it less), lets the approver
 * read what a typed code claims and choose the class to approve it as (a wall
 * display, view only, or a cook's touch board; never more than it asked for),
 * lets a manager point a wall at a station, and says when a wall was last seen. Every call sits behind `kitchen.station.manage` at
 * `LOCATION` scope (`KitchenDeviceController`'s own doc explains why that
 * capability rather than a new one); this page shows the same honest denied
 * state `capacity-page.ts` does for an operator who lacks it, rather than a
 * blank table.
 */
@Component({
  selector: 'q-devices-page',
  imports: [TPipe],
  templateUrl: './devices-page.html',
  styleUrl: './devices-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DevicesPage implements OnInit {
  private readonly location = inject(CurrentLocation);
  private readonly devicesApi = inject(KitchenDevicesApi);
  private readonly kitchenApi = inject(KitchenApi);
  protected readonly i18n = inject(I18n);

  protected readonly firstLoadComplete = signal(false);
  protected readonly denied = signal(false);
  protected readonly lastError = signal<ApiError | null>(null);

  protected readonly devices = signal<readonly KitchenDeviceView[]>([]);

  /** The branch's stations, for a wall's station picker; empty when the read is refused (the picker then offers the whole branch only). */
  protected readonly stations = signal<readonly StationResponse[]>([]);
  /** What the typed code claims (ADR 0151); null until it is checked, and again when the code changes. */
  protected readonly claim = signal<PendingEnrolmentView | null>(null);
  protected readonly claimedCode = signal('');
  protected readonly claimNotFound = signal(false);
  protected readonly formClass = signal<KitchenDeviceClass | null>(null);

  /** The classes the approver may choose: what the device asked for, and anything with less access. */
  protected readonly approvableClasses = computed<readonly KitchenDeviceClass[]>(() => {
    const claimed = this.claim();
    return claimed ? APPROVABLE_AS[claimed.requestedClass] : [];
  });

  /** A wall's station draft, by device: '' is the whole branch. */
  protected readonly stationDrafts = signal<Readonly<Record<string, string>>>({});
  protected readonly stationSavingId = signal<string | null>(null);
  protected readonly stationError = signal<string | null>(null);

  protected readonly formUserCode = signal('');
  protected readonly formDisplayName = signal('');
  protected readonly formTouched = signal(false);
  protected readonly formSubmitting = signal(false);
  protected readonly formError = signal<string | null>(null);

  protected readonly formValid = () =>
    this.formUserCode().trim().length > 0 && this.formDisplayName().trim().length > 0;

  /** The device row a revoke reason is currently being typed for; null when no row is mid-revoke. */
  protected readonly revokeTargetId = signal<string | null>(null);
  protected readonly revokeReason = signal('');
  protected readonly revokeSubmitting = signal(false);
  protected readonly revokeError = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    await this.location.ensureLoaded();
    await this.reload();
  }

  private async reload(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.firstLoadComplete.set(true);
      return;
    }
    try {
      const devices = await this.devicesApi.list(scope);
      this.devices.set(devices);
      this.stationDrafts.set(
        Object.fromEntries(
          devices
            .filter((d) => d.display)
            .map((d) => [d.deviceId, d.display?.station?.stationId ?? '']),
        ),
      );
      this.denied.set(false);
      this.lastError.set(null);
      void this.loadStations(scope);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
        this.lastError.set(null);
      } else if (error instanceof ApiError) {
        this.lastError.set(error);
      } else {
        throw error;
      }
    } finally {
      this.firstLoadComplete.set(true);
    }
  }

  /** A manager can pick a station only if they can list them; without the read the picker offers the whole branch alone. */
  private async loadStations(scope: LocationScope): Promise<void> {
    try {
      this.stations.set(await this.kitchenApi.stations(scope));
    } catch {
      this.stations.set([]);
    }
  }

  protected activeDevices(): readonly KitchenDeviceView[] {
    return this.devices().filter((device) => device.status === 'ACTIVE');
  }

  protected revokedDevices(): readonly KitchenDeviceView[] {
    return this.devices().filter((device) => device.status !== 'ACTIVE');
  }

  /** The code changed: whatever was claimed for the old one no longer applies. */
  protected onUserCodeInput(value: string): void {
    this.formUserCode.set(value);
    if (value.trim() !== this.claimedCode()) {
      this.claim.set(null);
      this.claimNotFound.set(false);
      this.formClass.set(null);
    }
  }

  /** Reads what the typed code claims, so the approver sees the class it asked for before choosing one. */
  protected async lookupClaim(): Promise<PendingEnrolmentView | null> {
    const scope = this.location.scope();
    const code = this.formUserCode().trim();
    if (!scope || code.length === 0) {
      return null;
    }
    this.formError.set(null);
    try {
      const found = await this.devicesApi.pending(scope, code);
      this.claimedCode.set(code);
      this.claim.set(found);
      this.claimNotFound.set(found === null);
      this.formClass.set(found ? found.requestedClass : null);
      return found;
    } catch (error) {
      this.formError.set(this.describe(error));
      return null;
    }
  }

  protected async submitApprove(): Promise<void> {
    this.formTouched.set(true);
    const scope = this.location.scope();
    if (!scope || !this.formValid()) {
      return;
    }
    this.formSubmitting.set(true);
    this.formError.set(null);
    try {
      // The claim is read first when the manager has not checked the code: approval defaults to what
      // the device asked for, and a code nothing is waiting on is told so rather than approved blind.
      const claimed =
        this.claim() !== null && this.claimedCode() === this.formUserCode().trim()
          ? this.claim()
          : await this.lookupClaim();
      if (!claimed) {
        return;
      }
      const approved = await firstValueFrom(
        this.devicesApi.approve(
          scope,
          this.formUserCode().trim(),
          this.formDisplayName().trim(),
          this.formClass() ?? claimed.requestedClass,
        ),
      );
      this.devices.update((current) => [
        approved,
        ...current.filter((d) => d.deviceId !== approved.deviceId),
      ]);
      if (approved.display) {
        this.stationDrafts.update((drafts) => ({
          ...drafts,
          [approved.deviceId]: approved.display?.station?.stationId ?? '',
        }));
      }
      this.formUserCode.set('');
      this.formDisplayName.set('');
      this.formTouched.set(false);
      this.claim.set(null);
      this.claimedCode.set('');
      this.formClass.set(null);
    } catch (error) {
      this.formError.set(this.describe(error));
    } finally {
      this.formSubmitting.set(false);
    }
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }

  // ------------------------------------------------------------ a wall display

  protected classLabel(deviceClass: string | undefined): string {
    return deviceClass === 'KITCHEN_VDU'
      ? this.i18n.t('kitchen.devices.class.KITCHEN_VDU')
      : this.i18n.t('kitchen.devices.class.KITCHEN_KDS');
  }

  /** A narrowed device says what it asked for: a tablet that was approved as a wall. */
  protected requestedAs(device: KitchenDeviceView): string | null {
    return device.requestedClass && device.requestedClass !== device.deviceClass
      ? this.i18n.t('kitchen.devices.requestedAs', {
          class: this.classLabel(device.requestedClass),
        })
      : null;
  }

  protected stationName(station: DeviceStationRef | StationResponse): string {
    switch (this.i18n.locale()) {
      case 'uz-Latn':
        return station.displayNameUz;
      case 'en':
        return station.displayNameEn;
      default:
        return station.displayNameRu;
    }
  }

  protected stationDraftOf(device: KitchenDeviceView): string {
    return this.stationDrafts()[device.deviceId] ?? device.display?.station?.stationId ?? '';
  }

  protected setStationDraft(deviceId: string, stationId: string): void {
    this.stationDrafts.update((drafts) => ({ ...drafts, [deviceId]: stationId }));
  }

  protected stationChanged(device: KitchenDeviceView): boolean {
    return this.stationDraftOf(device) !== (device.display?.station?.stationId ?? '');
  }

  protected async saveStation(device: KitchenDeviceView): Promise<void> {
    const scope = this.location.scope();
    const display = device.display;
    if (!scope || !display || this.stationSavingId() !== null) {
      return;
    }
    this.stationSavingId.set(device.deviceId);
    this.stationError.set(null);
    try {
      const saved = await firstValueFrom(
        this.devicesApi.configureDisplay(
          scope,
          device.deviceId,
          this.stationDraftOf(device) || null,
          display.version,
        ),
      );
      this.devices.update((current) =>
        current.map((d) => (d.deviceId === device.deviceId ? { ...d, display: saved } : d)),
      );
    } catch (error) {
      if (error instanceof ApiError && error.code === ApiErrorCode.STALE_VERSION) {
        // Someone else configured this wall first: show what is true now and ask again.
        await this.reload();
        this.stationError.set(this.i18n.t('kitchen.devices.station.stale'));
      } else {
        this.stationError.set(this.i18n.t('kitchen.devices.station.error'));
      }
    } finally {
      this.stationSavingId.set(null);
    }
  }

  /** `seen 14:32`, `last read 14:10`, or that it has never read, as the manager should read a silent wall. */
  protected lastSeenLabel(device: KitchenDeviceView): string {
    const display = device.display;
    if (!display) {
      return '';
    }
    const when = display.lastReadAt ? this.formattedInstant(display.lastReadAt) : null;
    if (display.notSeen) {
      return when
        ? this.i18n.t('kitchen.devices.lastSeen.notSeen', { time: when })
        : this.i18n.t('kitchen.devices.lastSeen.notSeenNever');
    }
    return when
      ? this.i18n.t('kitchen.devices.lastSeen.seen', { time: when })
      : this.i18n.t('kitchen.devices.lastSeen.never');
  }

  protected startRevoke(deviceId: string): void {
    this.revokeTargetId.set(deviceId);
    this.revokeReason.set('');
    this.revokeError.set(null);
  }

  protected cancelRevoke(): void {
    this.revokeTargetId.set(null);
    this.revokeReason.set('');
    this.revokeError.set(null);
  }

  protected canConfirmRevoke(): boolean {
    return this.revokeReason().trim().length > 0;
  }

  protected async confirmRevoke(): Promise<void> {
    const scope = this.location.scope();
    const deviceId = this.revokeTargetId();
    if (!scope || !deviceId || !this.canConfirmRevoke()) {
      return;
    }
    this.revokeSubmitting.set(true);
    this.revokeError.set(null);
    try {
      await firstValueFrom(this.devicesApi.revoke(scope, deviceId, this.revokeReason().trim()));
      await this.reload();
      this.revokeTargetId.set(null);
      this.revokeReason.set('');
    } catch (error) {
      this.revokeError.set(
        error instanceof ApiError
          ? describeApiError(error, (key, values) => this.i18n.t(key, values))
          : this.i18n.t('error.unknown.noReference'),
      );
    } finally {
      this.revokeSubmitting.set(false);
    }
  }

  /**
   * `DD.MM.YYYY HH:mm` in UTC — this table is an administrative device
   * registry, not an order timeline, so it deliberately skips
   * `core/format/datetime.ts`'s tenant-zone conversion (which needs a zone
   * this page never fetches) rather than mislabel a UTC instant as tenant
   * local time.
   */
  protected formattedInstant(value: string): string {
    const parsed = new Date(value);
    if (Number.isNaN(parsed.getTime())) {
      return value;
    }
    const pad = (n: number) => String(n).padStart(2, '0');
    return (
      `${pad(parsed.getUTCDate())}.${pad(parsed.getUTCMonth() + 1)}.${parsed.getUTCFullYear()} ` +
      `${pad(parsed.getUTCHours())}:${pad(parsed.getUTCMinutes())}`
    );
  }
}
