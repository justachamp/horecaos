import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { SessionCapabilities } from '../../../core/auth/session-capabilities';
import { FeatureFlags } from '../../../core/feature-flags';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import {
  KioskApi,
  KioskChannelChoice,
  KioskPendingView,
  KioskView,
  kioskOffered,
} from './kiosk-api';

/** The longest display name the platform accepts. */
const NAME_MAX = 120;

type Lookup = 'none' | 'looking' | 'found' | 'notFound' | 'tooMany' | 'failed' | 'denied';

/**
 * The approval page a kiosk's QR opens (ADR 0162, Decision 4): *Settings → Channel setup → KIOSK → Pair a kiosk*, with the
 * code prefilled from `?code=`, or typed.
 *
 * **Landing on this page approves nothing.** It reads what the code claims (`GET …/kiosk/enrolments/{code}`: the class the
 * device asked for, the label it sent and when the code dies), shows that label as escaped text and never as anything
 * else, and approves only when the manager has chosen a channel and ticked a box that says, in words, that the code on the
 * kiosk's own screen in front of them is the one on this page. A code that asked to be a kitchen display is explained, not
 * offered: that class is approved in Kitchen → Devices. An unknown, spent or expired code is one answer.
 *
 * The branch is the one the scope bar names, because a kiosk is paired to a branch; the channels offered are the KIOSK
 * channels active and selling there, read through the kiosk's own endpoint so a branch manager without a tenant-wide
 * `channel.read` can still pair.
 */
@Component({
  selector: 'q-kiosk-pairing-page',
  imports: [TPipe, RouterLink],
  templateUrl: './kiosk-pairing-page.html',
  styleUrl: './kiosk-pairing-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class KioskPairingPage implements OnInit {
  private readonly api = inject(KioskApi);
  private readonly route = inject(ActivatedRoute);
  private readonly flags = inject(FeatureFlags);
  private readonly capabilities = inject(SessionCapabilities);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  protected readonly nameMax = NAME_MAX;

  protected readonly ready = signal(false);
  protected readonly enabled = computed(
    () =>
      this.ready() && kioskOffered(this.flags, (capability) => this.capabilities.has(capability)),
  );
  protected readonly scope = this.location.scope;
  protected readonly branchName = computed(() => {
    const id = this.scope()?.locationId;
    return this.location.options().find((option) => option.id === id)?.displayName ?? null;
  });

  protected readonly code = signal('');
  protected readonly lookup = signal<Lookup>('none');
  protected readonly pending = signal<KioskPendingView | null>(null);
  protected readonly channels = signal<readonly KioskChannelChoice[]>([]);

  protected readonly displayName = signal('');
  protected readonly channelId = signal('');
  protected readonly confirmed = signal(false);
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly paired = signal<KioskView | null>(null);

  protected readonly isKiosk = computed(() => this.pending()?.requestedClass === 'KIOSK');
  protected readonly canSubmit = computed(
    () =>
      this.lookup() === 'found' &&
      this.isKiosk() &&
      this.displayName().trim().length > 0 &&
      this.channelId() !== '' &&
      this.confirmed() &&
      !this.submitting(),
  );

  async ngOnInit(): Promise<void> {
    await Promise.all([
      this.flags.ensureLoaded(),
      this.capabilities.ensureLoaded(),
      this.location.ensureLoaded(),
    ]);
    this.ready.set(true);
    const prefilled = this.route.snapshot.queryParamMap.get('code');
    if (prefilled !== null && prefilled.trim() !== '') {
      this.code.set(normalise(prefilled));
      if (this.enabled()) {
        await this.look();
      }
    }
  }

  protected onCode(value: string): void {
    this.code.set(normalise(value));
    this.lookup.set('none');
    this.pending.set(null);
    this.confirmed.set(false);
    this.error.set(null);
  }

  protected async look(): Promise<void> {
    const scope = this.scope();
    const code = this.code();
    if (!scope || code === '' || this.lookup() === 'looking') {
      return;
    }
    this.lookup.set('looking');
    this.error.set(null);
    this.confirmed.set(false);
    try {
      const pending = await this.api.pending(scope, code);
      this.pending.set(pending);
      if (pending.requestedClass === 'KIOSK') {
        this.channels.set(await this.api.channels(scope));
        const label = pending.requestedLabel?.trim() ?? '';
        if (this.displayName() === '' && label !== '') {
          this.displayName.set(label.slice(0, NAME_MAX));
        }
      }
      this.lookup.set('found');
    } catch (failure) {
      this.pending.set(null);
      this.lookup.set(lookupFailure(failure));
    }
  }

  protected async pair(): Promise<void> {
    const scope = this.scope();
    if (!scope || !this.canSubmit()) {
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    try {
      this.paired.set(
        await firstValueFrom(
          this.api.approve(scope, this.code(), {
            displayName: this.displayName().trim(),
            channelId: this.channelId(),
          }),
        ),
      );
    } catch (failure) {
      this.error.set(this.describe(failure));
    } finally {
      this.submitting.set(false);
    }
  }

  protected formatted(value: string): string {
    const parsed = new Date(value);
    if (Number.isNaN(parsed.getTime())) {
      return value;
    }
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${pad(parsed.getUTCHours())}:${pad(parsed.getUTCMinutes())}`;
  }

  private describe(failure: unknown): string {
    if (failure instanceof ApiError) {
      if (failure.code === 'DEVICE_CLASS_MISMATCH') {
        return this.i18n.t('settings.kioskPairing.error.classMismatch');
      }
      if (failure.code === 'KIOSK_CHANNEL_NOT_AVAILABLE') {
        return this.i18n.t('settings.kioskPairing.error.channelUnavailable');
      }
      if (failure.status === 404) {
        return this.i18n.t('settings.kioskPairing.error.gone');
      }
      return describeApiError(failure, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}

/** A typed or scanned code is read the way a person says it: no spaces, upper case. */
function normalise(value: string): string {
  return value.replace(/\s+/g, '').toUpperCase();
}

function lookupFailure(failure: unknown): Lookup {
  if (failure instanceof ApiError) {
    if (failure.status === 404) {
      return 'notFound';
    }
    if (failure.status === 429) {
      return 'tooMany';
    }
    if (failure.status === 403) {
      return 'denied';
    }
  }
  return 'failed';
}
