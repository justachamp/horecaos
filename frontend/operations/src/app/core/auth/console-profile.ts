import { Injectable, Signal, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../api/api-client';
import { CurrentTenant } from './current-tenant';

/** `GET /api/v1/session/console-profile` (ADR 0164). */
export interface ConsoleProfileResponse {
  readonly variant: 'STANDARD' | 'DELIVERIES';
  /** The route the console opens on: `/today`, or `/deliveries` for the deliveries profile. */
  readonly landing: string;
  /** Rail sections the profile puts away, by their route (`/kitchen`, `/marketing`, `/inbox`). */
  readonly hiddenSections: readonly string[];
  /** The fulfilment modes the New order screen offers. */
  readonly handovers: readonly string[];
}

const SHOW_ALL_STORAGE_KEY = 'horecaos.console.showAllSections';

/**
 * How the console opens for the signed-in tenant (ADR 0164) -- the one read the shell, the root
 * redirect, the New order screen and the Statistics landing all ask.
 *
 * **A presentation, never a permission.** The profile decides where the console opens and which rail
 * sections are put away. It is never consulted by `capabilityGuard`, so a put-away URL still opens for
 * whoever holds the capability, and it can only ever *remove* things from a rail the capability filter
 * already allowed -- never add a section the operator may not use.
 *
 * **A profile that cannot be read is the standard console.** A failed or slow read must not leave an
 * operator on a blank screen, so every accessor answers the standard console until (and unless) a
 * profile has arrived.
 *
 * **«Показать все» is a per-viewer convenience.** It shows the put-away sections for this person on
 * this device and is kept in the browser's storage, wrapped so an unavailable store changes nothing but
 * the memory of the choice. It is never a setting anything else relies on, and it needs no request.
 */
@Injectable({ providedIn: 'root' })
export class ConsoleProfile {
  private readonly api = inject(ApiClient);
  private readonly tenant = inject(CurrentTenant);

  private readonly profile = signal<ConsoleProfileResponse | null>(null);
  private readonly showAllChosen = signal(readShowAll());

  /** `STANDARD` until a profile that says otherwise has been read. */
  readonly variant: Signal<'STANDARD' | 'DELIVERIES'> = computed(
    () => this.profile()?.variant ?? 'STANDARD',
  );

  readonly isDeliveries: Signal<boolean> = computed(() => this.variant() === 'DELIVERIES');

  /** The route the console opens on. */
  readonly landing: Signal<string> = computed(() => this.profile()?.landing ?? '/today');

  /** What the profile puts away, whether or not this person has asked to see it. */
  readonly putAway: Signal<readonly string[]> = computed(
    () => this.profile()?.hiddenSections ?? [],
  );

  /** What the rail actually hides now: the put-away sections, unless this person chose «Показать все». */
  readonly hiddenSections: Signal<readonly string[]> = computed(() =>
    this.showAllChosen() ? [] : this.putAway(),
  );

  readonly showingAll: Signal<boolean> = computed(
    () => this.showAllChosen() && this.putAway().length > 0,
  );

  /** `null` means "no restriction": the standard console, or a profile that could not be read. */
  readonly handovers: Signal<readonly string[] | null> = computed(() => {
    const profile = this.profile();
    return profile === null || profile.variant === 'STANDARD' ? null : profile.handovers;
  });

  private loadPromise: Promise<void> | null = null;

  /** Reads the profile once per page load; every later call replays the same promise. */
  ensureLoaded(): Promise<void> {
    if (this.loadPromise === null) {
      this.loadPromise = this.load();
    }
    return this.loadPromise;
  }

  /** «Показать все» / «Скрыть лишнее»: toggles the put-away sections for this person on this device. */
  toggleShowAll(): void {
    const next = !this.showAllChosen();
    this.showAllChosen.set(next);
    writeShowAll(next);
  }

  private async load(): Promise<void> {
    await this.tenant.ensureLoaded();
    const tenantId = this.tenant.tenantId();
    if (tenantId === null) {
      return;
    }
    try {
      const result = await firstValueFrom(
        this.api.get<ConsoleProfileResponse>('/api/v1/session/console-profile', {
          params: { tenantId },
        }),
      );
      this.profile.set(result.value);
    } catch {
      // A profile that cannot be read is the standard console.
      this.profile.set(null);
    }
  }
}

function readShowAll(): boolean {
  try {
    return globalThis.localStorage?.getItem(SHOW_ALL_STORAGE_KEY) === 'true';
  } catch {
    return false;
  }
}

function writeShowAll(value: boolean): void {
  try {
    globalThis.localStorage?.setItem(SHOW_ALL_STORAGE_KEY, String(value));
  } catch {
    // The choice simply is not remembered.
  }
}
