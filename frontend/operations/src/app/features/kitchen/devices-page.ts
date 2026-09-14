import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { ApiError } from '../../core/api/problem-details';
import { describeApiError } from '../orders/order-errors';
import { KitchenDeviceView, KitchenDevicesApi } from './devices-api';

/**
 * IA row `2/X.2` — Kitchen › Devices (ADR 0079). The console half of the
 * pairing handshake `device/device-shell.ts` runs on the tablet's own side:
 * a manager reads the `userCode` off the new screen and types it here, never
 * scans it — ADR 0079's own decision, not reopened by this screen or by
 * `q-qr-code` (`shared/ui/qr-code.ts`), which is a *display* component only.
 *
 * **Built:** list (active and revoked alike), approve-by-typed-user-code,
 * revoke-with-a-reason. Every call sits behind `kitchen.station.manage` at
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
  protected readonly i18n = inject(I18n);

  protected readonly firstLoadComplete = signal(false);
  protected readonly denied = signal(false);
  protected readonly lastError = signal<ApiError | null>(null);

  protected readonly devices = signal<readonly KitchenDeviceView[]>([]);

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
      this.devices.set(await this.devicesApi.list(scope));
      this.denied.set(false);
      this.lastError.set(null);
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

  protected activeDevices(): readonly KitchenDeviceView[] {
    return this.devices().filter((device) => device.status === 'ACTIVE');
  }

  protected revokedDevices(): readonly KitchenDeviceView[] {
    return this.devices().filter((device) => device.status !== 'ACTIVE');
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
      const approved = await firstValueFrom(
        this.devicesApi.approve(scope, this.formUserCode().trim(), this.formDisplayName().trim()),
      );
      this.devices.update((current) => [
        approved,
        ...current.filter((d) => d.deviceId !== approved.deviceId),
      ]);
      this.formUserCode.set('');
      this.formDisplayName.set('');
      this.formTouched.set(false);
    } catch (error) {
      this.formError.set(
        error instanceof ApiError
          ? describeApiError(error, (key, values) => this.i18n.t(key, values))
          : this.i18n.t('error.unknown.noReference'),
      );
    } finally {
      this.formSubmitting.set(false);
    }
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
