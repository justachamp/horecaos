import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { FeatureFlags } from '../../../core/feature-flags';
import { SessionCapabilities } from '../../../core/auth/session-capabilities';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import { KIOSK_FLAG, KioskApi, KioskChannelChoice, KioskView } from './kiosk-api';

/** How long a kiosk may go quiet before the list says so. A kiosk polls its record once a minute. */
export const KIOSK_QUIET_AFTER_MINUTES = 5;

/**
 * The KIOSK section of Channel setup (ADR 0162, Decision 6; settings.md §10.5): the branch's kiosks with the channel
 * each sells through, when each was last seen and on which build, who paired and who revoked it and why; *Pair a kiosk*;
 * rename or move to another KIOSK channel at the same branch; revoke with a reason.
 *
 * It answers to the dark flag `feature.kiosk_devices`: with the flag off the card says the feature is not switched on and
 * asks the server for nothing. «Replace» is revoke and then pair, and the card says so: the kiosk displays its own code, so
 * no console issues one.
 *
 * Changing anything needs `kiosk.device.manage` at the branch, which the list itself also needs; the card hides what the
 * caller's grant cannot use and shows the server's refusal when it disagrees.
 */
@Component({
  selector: 'q-kiosk-card',
  imports: [TPipe, RouterLink],
  templateUrl: './kiosk-card.html',
  styleUrl: './kiosk-card.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class KioskCard implements OnInit {
  private readonly api = inject(KioskApi);
  private readonly flags = inject(FeatureFlags);
  private readonly capabilities = inject(SessionCapabilities);
  protected readonly i18n = inject(I18n);

  readonly scope = input.required<LocationScope>();

  protected readonly flagLoaded = signal(false);
  protected readonly enabled = computed(() => this.flagLoaded() && this.flags.isOn(KIOSK_FLAG));
  protected readonly canManage = computed(() => this.capabilities.has('KIOSK_DEVICE_MANAGE'));

  protected readonly state = signal<'loading' | 'ready' | 'denied' | 'error'>('loading');
  protected readonly kiosks = signal<readonly KioskView[]>([]);
  protected readonly channels = signal<readonly KioskChannelChoice[]>([]);
  protected readonly now = signal(Date.now());

  protected readonly active = computed(() => this.kiosks().filter((k) => k.status === 'ACTIVE'));
  protected readonly revoked = computed(() => this.kiosks().filter((k) => k.status !== 'ACTIVE'));

  protected readonly editId = signal<string | null>(null);
  protected readonly draftName = signal('');
  protected readonly draftChannel = signal('');
  protected readonly editSaving = signal(false);
  protected readonly editError = signal<string | null>(null);

  protected readonly revokeId = signal<string | null>(null);
  protected readonly revokeReason = signal('');
  protected readonly revokeBusy = signal(false);
  protected readonly revokeError = signal<string | null>(null);

  protected readonly replaceHintId = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    await this.flags.ensureLoaded();
    this.flagLoaded.set(true);
    if (this.enabled()) {
      await this.reload();
    }
  }

  private async reload(): Promise<void> {
    this.state.set('loading');
    try {
      const [kiosks, channels] = await Promise.all([
        this.api.devices(this.scope()),
        this.api.channels(this.scope()),
      ]);
      this.kiosks.set(kiosks);
      this.channels.set(channels);
      this.now.set(Date.now());
      this.state.set('ready');
    } catch (error) {
      this.state.set(error instanceof ApiError && error.status === 403 ? 'denied' : 'error');
    }
  }

  // --------------------------------------------------------------------------------- labels

  /** How long the kiosk has been silent, as a manager reads it. */
  protected lastSeenLabel(kiosk: KioskView): string {
    if (!kiosk.lastSeenAt) {
      return this.i18n.t('settings.kiosk.lastSeen.never');
    }
    const minutes = this.minutesSilent(kiosk);
    return minutes < 1
      ? this.i18n.t('settings.kiosk.lastSeen.now')
      : minutes < 60
        ? this.i18n.t('settings.kiosk.lastSeen.minutes', { minutes })
        : this.i18n.t('settings.kiosk.lastSeen.at', { time: this.formatted(kiosk.lastSeenAt) });
  }

  protected quiet(kiosk: KioskView): boolean {
    return (
      kiosk.status === 'ACTIVE' &&
      kiosk.lastSeenAt !== null &&
      this.minutesSilent(kiosk) >= KIOSK_QUIET_AFTER_MINUTES
    );
  }

  private minutesSilent(kiosk: KioskView): number {
    const seen = new Date(kiosk.lastSeenAt ?? '').getTime();
    return Number.isNaN(seen) ? 0 : Math.max(0, Math.floor((this.now() - seen) / 60_000));
  }

  protected formatted(value: string): string {
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

  // --------------------------------------------------------------------------------- editing

  protected startEdit(kiosk: KioskView): void {
    this.editId.set(kiosk.deviceId);
    this.draftName.set(kiosk.displayName);
    this.draftChannel.set(kiosk.channel?.channelId ?? '');
    this.editError.set(null);
  }

  protected cancelEdit(): void {
    this.editId.set(null);
    this.editError.set(null);
  }

  protected editValid(): boolean {
    return this.draftName().trim().length > 0 && this.draftChannel() !== '';
  }

  protected async saveEdit(kiosk: KioskView): Promise<void> {
    if (!this.editValid() || this.editSaving()) {
      return;
    }
    this.editSaving.set(true);
    this.editError.set(null);
    try {
      const saved = await firstValueFrom(
        this.api.rebind(
          this.scope(),
          kiosk.deviceId,
          { displayName: this.draftName().trim(), channelId: this.draftChannel() },
          kiosk.version,
        ),
      );
      this.kiosks.update((current) =>
        current.map((row) => (row.deviceId === saved.deviceId ? saved : row)),
      );
      this.editId.set(null);
    } catch (error) {
      if (error instanceof ApiError && error.code === ApiErrorCode.STALE_VERSION) {
        await this.reload();
        this.editId.set(null);
        this.editError.set(this.i18n.t('settings.kiosk.edit.stale'));
      } else if (error instanceof ApiError && error.code === 'KIOSK_CHANNEL_NOT_AVAILABLE') {
        this.editError.set(this.i18n.t('settings.kiosk.edit.channelUnavailable'));
      } else {
        this.editError.set(this.describe(error));
      }
    } finally {
      this.editSaving.set(false);
    }
  }

  // --------------------------------------------------------------------------------- revoking

  protected startRevoke(deviceId: string): void {
    this.revokeId.set(deviceId);
    this.revokeReason.set('');
    this.revokeError.set(null);
  }

  protected cancelRevoke(): void {
    this.revokeId.set(null);
    this.revokeReason.set('');
    this.revokeError.set(null);
  }

  protected async confirmRevoke(): Promise<void> {
    const deviceId = this.revokeId();
    const reason = this.revokeReason().trim();
    if (deviceId === null || reason === '' || this.revokeBusy()) {
      return;
    }
    this.revokeBusy.set(true);
    this.revokeError.set(null);
    try {
      await firstValueFrom(this.api.revoke(this.scope(), deviceId, reason));
      await this.reload();
      this.cancelRevoke();
    } catch (error) {
      this.revokeError.set(this.describe(error));
    } finally {
      this.revokeBusy.set(false);
    }
  }

  protected toggleReplaceHint(deviceId: string): void {
    this.replaceHintId.update((current) => (current === deviceId ? null : deviceId));
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}
