import { Injectable, computed, inject, signal } from '@angular/core';

import { IntentKeys } from '../api/intent-keys';
import { ApiError, toApiError } from '../api/problem-details';
import { CourierApi } from '../courier/courier-api';
import { CourierProfileStore } from '../courier/courier-profile.store';

/**
 * The version of the courier transparency notice this build shows (ADR 0045: what is collected,
 * who sees it, for how long).
 *
 * **The wording is not ours to write.** ADR 0045 makes the notice the tenant's document, signed off
 * with counsel, and the precondition for switching telemetry ingest on for a real tenant. Until the
 * tenant supplies it this build ships a clearly marked draft (`notice.draft.*` in the catalogues),
 * and the version below says `draft` so the platform's audit record names exactly what a courier
 * was shown. Replacing the text means replacing the catalogue entries, setting
 * {@link NOTICE_IS_DRAFT} to false and changing this version: every courier is then asked again.
 */
export const NOTICE_VERSION = '2026-10-draft-1';

export const NOTICE_IS_DRAFT = true;

const STORAGE_KEY = 'courier.notice';

interface StoredAcknowledgement {
  readonly courierId: string;
  readonly version: string;
  readonly acknowledgedAt: string;
}

function readStored(): StoredAcknowledgement | null {
  try {
    const raw = globalThis.localStorage.getItem(STORAGE_KEY);
    if (raw === null) {
      return null;
    }
    const parsed: unknown = JSON.parse(raw);
    if (
      typeof parsed === 'object' &&
      parsed !== null &&
      typeof (parsed as StoredAcknowledgement).courierId === 'string' &&
      typeof (parsed as StoredAcknowledgement).version === 'string' &&
      typeof (parsed as StoredAcknowledgement).acknowledgedAt === 'string'
    ) {
      return parsed as StoredAcknowledgement;
    }
  } catch {
    // A browser that keeps no storage, or a record that is not ours: the courier is asked again.
  }
  return null;
}

/**
 * Whether this courier has been shown, and has acknowledged, the current transparency notice --
 * the one thing that must be true before the app goes on duty or sends a position (ADR 0179 D7.8).
 *
 * **The platform records it; the handset remembers it.** Acknowledging posts the version to the
 * platform, which writes an audit fact naming the courier, the version and the time -- the
 * evidence a tenant needs and a handset cannot give. Only after the platform has confirmed does
 * the handset store the courier id, the version and the time, so a courier is not asked again on
 * this phone. A new phone, a new notice version or another courier on the same phone asks again,
 * which is the safe direction to be wrong in.
 */
@Injectable({ providedIn: 'root' })
export class NoticeStore {
  private readonly api = inject(CourierApi);
  private readonly profile = inject(CourierProfileStore);
  private readonly keys = inject(IntentKeys);

  private readonly remembered = signal<StoredAcknowledgement | null>(readStored());
  private readonly working = signal(false);
  private readonly failure = signal<ApiError | null>(null);

  readonly version = NOTICE_VERSION;
  readonly isDraft = NOTICE_IS_DRAFT;
  readonly busy = this.working.asReadonly();
  readonly error = this.failure.asReadonly();

  readonly acknowledged = computed(() => {
    const courier = this.profile.profile();
    const kept = this.remembered();
    return (
      courier !== null &&
      kept !== null &&
      kept.courierId === courier.courierId &&
      kept.version === NOTICE_VERSION
    );
  });

  /** Returns whether the platform recorded it. A false leaves {@link error} to say why. */
  async acknowledge(): Promise<boolean> {
    const courier = this.profile.profile();
    if (courier === null || this.working()) {
      return false;
    }
    this.working.set(true);
    this.failure.set(null);
    try {
      const recorded = await this.keys.run(`notice:${NOTICE_VERSION}`, (key) =>
        this.api.acknowledgeNotice(NOTICE_VERSION, key),
      );
      const kept: StoredAcknowledgement = {
        courierId: courier.courierId,
        version: NOTICE_VERSION,
        acknowledgedAt: recorded.acknowledgedAt,
      };
      this.remembered.set(kept);
      try {
        globalThis.localStorage.setItem(STORAGE_KEY, JSON.stringify(kept));
      } catch {
        // Held for this page's life; the platform has the record.
      }
      return true;
    } catch (cause) {
      this.failure.set(toApiError(cause));
      return false;
    } finally {
      this.working.set(false);
    }
  }
}
