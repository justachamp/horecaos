import { Injectable, Signal, computed, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../api/api-client';
import { command } from '../api/idempotency';
import { ApiError, ApiErrorCode } from '../api/problem-details';
import { staffPaths } from '../api/staff-paths';
import { Auth } from '../auth/auth';
import { CurrentTenant } from '../auth/current-tenant';
import { forgetAccountLocale } from '../i18n/i18n';
import {
  EMPTY_ORDER_BOARD_DOCUMENT,
  EMPTY_SHELL_DOCUMENT,
  OrderBoardDocument,
  PREFERENCE_SURFACES,
  PreferenceDocuments,
  PreferenceSurface,
  PreferencesResponse,
  SavePreferenceRequest,
  ShellDocument,
  StoredPreference,
  sanitizeOrderBoardDocument,
  sanitizeShellDocument,
} from './preference-documents';

/** The browser's mirror of a person's documents, one key per signed-in person (a hash of the subject). */
const MIRROR_PREFIX = 'horecaos.operations.preferences.';

/** A toggle is a click, and a person flips several in a row: one write goes out after they stop. */
export const PREFERENCE_WRITE_DEBOUNCE_MS = 600;

type Documents = { [S in PreferenceSurface]?: PreferenceDocuments[S] };

/** `loading` before the first answer, `ready` after it, `unavailable` for an account the tenant keeps no record for. */
export type PreferencesStatus = 'loading' | 'ready' | 'unavailable' | 'error';

interface Held {
  readonly subject: string;
  readonly status: PreferencesStatus;
  readonly documents: Documents;
}

/**
 * What the console remembers for the signed-in person, across machines (ADR 0160, gap map row `0.2d`):
 * their default branch (`SHELL`) and the order board's optional columns and saved views (`ORDER_BOARD`).
 *
 * **The server's document is the truth for what it holds; the browser keeps a mirror.** The documents are
 * read once per signed-in subject ({@link ensureLoaded}) and held in signals; the board and the shell read
 * the signals, never the API. A first paint is drawn from the mirror in `localStorage`, so a slow or failed
 * read does not blank the board, and every change is applied to the screen at once and written through
 * after {@link PREFERENCE_WRITE_DEBOUNCE_MS} of quiet.
 *
 * **Two devices.** A write carries the version it read in `If-Match` (none for the first). On a stale
 * version the client re-reads, re-applies its own change to the document it just read, and tries once more;
 * if that fails too, it drops the write and leaves the screen as it is -- a preference is not worth a merge,
 * and the last writer wins.
 *
 * **A shared PC.** The mirror is keyed by a hash of the subject, and every key of it is removed when the
 * signed-in person goes away, so the next person to sit down inherits nothing: not a column, not a saved
 * view, not a default branch. Saved views are one person's and are never uploaded under another's account.
 *
 * **Not everyone has a record.** A HorecaOS support session and a device have no staff member row in the
 * tenant (the platform answers 404): their preferences are {@link status} `unavailable`, their changes live
 * for the session and are never written, and nothing here throws for them.
 *
 * What stays in the browser (ADR 0160, decision 3): the language this device chose, the branch and brand
 * last picked on this device, the board's live filters per tab, split-pane widths and the data table's own
 * column chooser. They are device state, high-churn or free text, and do not belong in an account.
 */
@Injectable({ providedIn: 'root' })
export class OwnPreferences {
  private readonly api = inject(ApiClient);
  private readonly tenant = inject(CurrentTenant);
  private readonly auth = inject(Auth);

  private readonly held = signal<Held | null>(null);

  private readonly current: Signal<Held | null> = computed(() => {
    const held = this.held();
    return held !== null && held.subject === this.auth.subject() ? held : null;
  });

  /** The board's column choice and saved views, or the code's default while nothing is known. */
  readonly orderBoard: Signal<OrderBoardDocument> = computed(
    () => this.current()?.documents.ORDER_BOARD ?? EMPTY_ORDER_BOARD_DOCUMENT,
  );

  /** The person's default branch, or the code's default (none) while nothing is known. */
  readonly shell: Signal<ShellDocument> = computed(
    () => this.current()?.documents.SHELL ?? EMPTY_SHELL_DOCUMENT,
  );

  readonly status: Signal<PreferencesStatus> = computed(() => this.current()?.status ?? 'loading');

  /** False for an account with no record in the tenant: changes then live for the session only. */
  readonly persistent: Signal<boolean> = computed(() => this.status() !== 'unavailable');

  /** True after a write was dropped (a second device won twice, or the platform refused it); cleared by the next one that lands. */
  readonly writeFailed = signal(false);

  private loading: Promise<void> | null = null;
  private loadingFor: string | null = null;

  /** The server's version of each surface, absent for a surface never saved. */
  private readonly versions = new Map<PreferenceSurface, number>();

  /** The changes applied on screen and not yet written, per surface, oldest first. */
  private readonly pending = new Map<PreferenceSurface, Array<(document: never) => unknown>>();
  private readonly timers = new Map<PreferenceSurface, ReturnType<typeof setTimeout>>();

  /** What is on its way to the platform for a surface, in order: a write and a reset never overtake each other. */
  private readonly inFlight = new Map<PreferenceSurface, Promise<void>>();

  /**
   * Counts the resets of a surface. A write that began before a reset is superseded by it: when it comes
   * back it must not put a version, a document or a retry on a surface the person has since emptied.
   */
  private readonly resets = new Map<PreferenceSurface, number>();

  constructor() {
    // The mirror is read at once when the subject is already known (the console boots signed in), and
    // again whenever the signed-in person changes or goes away.
    this.follow(this.auth.subject());
    effect(() => {
      const subject = this.auth.subject();
      this.follow(subject);
    });
  }

  /** Reads the person's documents once per signed-in subject; later calls replay the same promise. */
  ensureLoaded(): Promise<void> {
    const subject = this.auth.subject();
    if (subject === null) {
      return Promise.resolve();
    }
    this.follow(subject);
    if (this.loading === null || this.loadingFor !== subject) {
      this.loadingFor = subject;
      this.loading = this.load(subject);
    }
    return this.loading;
  }

  /** Applies a change to the order board's document at once and writes it through. */
  updateOrderBoard(change: (document: OrderBoardDocument) => OrderBoardDocument): void {
    this.update('ORDER_BOARD', change);
  }

  /** Applies a change to the shell's document at once and writes it through. */
  updateShell(change: (document: ShellDocument) => ShellDocument): void {
    this.update('SHELL', change);
  }

  /**
   * Resets a surface to the code's default: the document goes from the screen and the mirror at once, and
   * from the platform after whatever is already on its way to it.
   *
   * **Ordered.** A write that is in flight is not overtaken by the reset: the `DELETE` is sent when that
   * write has landed (or failed), so the platform sees the write first and the reset after it, and the
   * write cannot bring the document back. The write is also superseded -- it leaves no version behind and
   * its stale-version retry is not attempted. A reset made before the first read has answered waits for the
   * read for the same reason: the read would otherwise put the document back on the screen. A change the
   * person makes after the reset waits for it in turn, and is a first write once it has landed.
   */
  async reset(surface: PreferenceSurface): Promise<void> {
    if (this.current()?.status === 'loading') {
      await this.ensureLoaded();
    }
    const held = this.current();
    if (held === null) {
      return;
    }
    this.cancelTimer(surface);
    this.pending.delete(surface);
    this.resets.set(surface, this.resetsOf(surface) + 1);
    const { [surface]: _gone, ...rest } = held.documents;
    void _gone;
    this.replace({ ...held, documents: rest });
    const tenantId = this.tenant.tenantId();
    if (tenantId === null || held.status === 'unavailable') {
      this.versions.delete(surface);
      return;
    }
    const removal = (this.inFlight.get(surface) ?? Promise.resolve()).then(() =>
      this.sendReset(surface, tenantId),
    );
    this.inFlight.set(surface, removal);
    return removal;
  }

  /** Writes whatever is waiting for its debounce now, and resolves when it has landed. Sign-out calls it before it leaves. */
  async flush(): Promise<void> {
    for (const surface of PREFERENCE_SURFACES) {
      this.cancelTimer(surface);
      await this.write(surface);
    }
  }

  // ------------------------------------------------------------------ internals

  /**
   * Moves to `subject`: its mirror is the first paint, and no one else's stays on this browser. Nobody
   * signed in at all -- after someone was -- removes every mirror there is; a cold start with nobody yet
   * signed in removes nothing, because the person who is about to be restored may need theirs.
   */
  private follow(subject: string | null): void {
    if (subject === null) {
      const wasSignedIn = this.held() !== null;
      if (wasSignedIn) {
        this.held.set(null);
      }
      this.loading = null;
      this.loadingFor = null;
      if (wasSignedIn) {
        this.dropEverything();
      }
      return;
    }
    if (this.held()?.subject === subject) {
      return;
    }
    this.dropEverything(false);
    // A shared PC: whoever sat here before, however they left, has nothing of theirs read by this person.
    removeEveryMirror(mirrorKeyFor(subject));
    this.held.set({ subject, status: 'loading', documents: readMirror(subject) });
  }

  private dropEverything(removeMirrors = true): void {
    for (const surface of PREFERENCE_SURFACES) {
      this.cancelTimer(surface);
    }
    this.pending.clear();
    this.versions.clear();
    this.inFlight.clear();
    this.resets.clear();
    this.writeFailed.set(false);
    if (removeMirrors) {
      removeEveryMirror();
      forgetAccountLocale();
    }
  }

  private async load(subject: string): Promise<void> {
    await this.tenant.ensureLoaded();
    const tenantId = this.tenant.tenantId();
    if (tenantId === null) {
      this.settle(subject, 'unavailable');
      return;
    }
    try {
      const result = await firstValueFrom(
        this.api.get<PreferencesResponse>(staffPaths.mePreferences(tenantId)),
      );
      if (this.auth.subject() !== subject) {
        return;
      }
      const documents: Documents = {};
      this.versions.clear();
      for (const surface of PREFERENCE_SURFACES) {
        const stored = result.value.surfaces?.[surface];
        if (stored !== undefined && stored !== null) {
          this.versions.set(surface, stored.version);
          this.assign(documents, surface, stored);
        }
      }
      // A change made on screen while the read was in flight is newer than the read, and it goes on top of
      // what the platform holds -- the same rule as the retry after a stale version. It never stands in the
      // platform's place: the screen it was made on was drawn from the mirror, which may be a week old, and
      // writing that document back would erase whatever another device saved since.
      for (const surface of PREFERENCE_SURFACES) {
        const changes = this.pending.get(surface);
        if (changes !== undefined && changes.length > 0) {
          this.assignRebased(documents, surface, changes);
        }
      }
      this.replace({ subject, status: 'ready', documents });
    } catch (error) {
      if (this.auth.subject() !== subject) {
        return;
      }
      if (error instanceof ApiError && (error.status === 404 || error.status === 403)) {
        // No staff record in this tenant (a support session, a device): nothing to read, nothing to write.
        this.settle(subject, 'unavailable');
        removeEveryMirror();
        return;
      }
      // The read failed: the mirror (or the code's default) is what the screen has, and the board works.
      this.settle(subject, 'error');
    }
  }

  private settle(subject: string, status: PreferencesStatus): void {
    const held = this.held();
    this.held.set({ subject, status, documents: held?.subject === subject ? held.documents : {} });
  }

  private update<S extends PreferenceSurface>(
    surface: S,
    change: (document: PreferenceDocuments[S]) => PreferenceDocuments[S],
  ): void {
    const held = this.current();
    if (held === null) {
      return;
    }
    const before = (held.documents[surface] ?? this.emptyOf(surface)) as PreferenceDocuments[S];
    const after = change(before);
    this.replace({ ...held, documents: { ...held.documents, [surface]: after } });
    if (held.status === 'unavailable') {
      return;
    }
    const queue = this.pending.get(surface) ?? [];
    queue.push(change as (document: never) => unknown);
    this.pending.set(surface, queue);
    this.cancelTimer(surface);
    this.timers.set(
      surface,
      setTimeout(() => void this.write(surface), PREFERENCE_WRITE_DEBOUNCE_MS),
    );
  }

  /** One write at a time per surface; a change made meanwhile waits for the one in flight. */
  private write(surface: PreferenceSurface): Promise<void> {
    const previous = this.inFlight.get(surface) ?? Promise.resolve();
    const next = previous.then(() => this.send(surface));
    this.inFlight.set(surface, next);
    return next;
  }

  /** Sends a reset. The platform keeps counting versions through it, so the surface is "never saved" again for this device. */
  private async sendReset(surface: PreferenceSurface, tenantId: string): Promise<void> {
    try {
      await firstValueFrom(
        this.api.send<undefined, void>(
          'DELETE',
          staffPaths.mePreference(tenantId, surface),
          command(undefined),
        ),
      );
      this.versions.delete(surface);
    } catch (error) {
      this.noteFailure(error);
    }
  }

  private resetsOf(surface: PreferenceSurface): number {
    return this.resets.get(surface) ?? 0;
  }

  private async send(surface: PreferenceSurface): Promise<void> {
    const changes = this.pending.get(surface);
    const held = this.current();
    const tenantId = this.tenant.tenantId();
    if (changes === undefined || changes.length === 0 || held === null || tenantId === null) {
      return;
    }
    const resetsBefore = this.resetsOf(surface);
    // True once the person has reset this surface since this write began: it then touches nothing again.
    const superseded = (): boolean => this.resetsOf(surface) !== resetsBefore;
    if (held.status === 'loading') {
      await this.ensureLoaded();
      if (superseded()) {
        return;
      }
    }
    this.pending.delete(surface);
    const document = this.current()?.documents[surface] ?? this.emptyOf(surface);
    const path = staffPaths.mePreference(tenantId, surface);
    const attempt = (body: unknown, version: number | undefined): Promise<StoredPreference> =>
      firstValueFrom(
        this.api.put<SavePreferenceRequest<PreferenceSurface>, StoredPreference>(
          path,
          command({ document: body } as SavePreferenceRequest<PreferenceSurface>),
          version === undefined ? {} : { expectedVersion: version },
        ),
      );
    try {
      const saved = await attempt(document, this.versions.get(surface));
      if (!superseded()) {
        this.versions.set(surface, saved.version);
        this.writeFailed.set(false);
      }
      return;
    } catch (error) {
      if (superseded()) {
        return;
      }
      if (!(error instanceof ApiError) || error.code !== ApiErrorCode.STALE_VERSION) {
        this.noteFailure(error);
        return;
      }
    }
    // Another device wrote first. Read what it left, put this device's one change on top, and try once more.
    try {
      const fresh = await firstValueFrom(
        this.api.get<PreferencesResponse>(staffPaths.mePreferences(tenantId)),
      );
      if (superseded()) {
        return;
      }
      const theirs = fresh.value.surfaces?.[surface];
      const merged = this.rebase(surface, theirs?.document, changes);
      if (theirs === undefined || theirs === null) {
        this.versions.delete(surface);
      } else {
        this.versions.set(surface, theirs.version);
      }
      const saved = await attempt(merged, this.versions.get(surface));
      if (superseded()) {
        return;
      }
      this.versions.set(surface, saved.version);
      this.adopt(surface, merged);
      this.writeFailed.set(false);
    } catch (error) {
      // Dropped: the screen keeps what the person sees, and the next load shows the platform's.
      if (!superseded()) {
        this.noteFailure(error);
      }
    }
  }

  private noteFailure(error: unknown): void {
    if (error instanceof ApiError && (error.status === 404 || error.status === 403)) {
      const held = this.current();
      if (held !== null) {
        this.held.set({ ...held, status: 'unavailable' });
      }
      return;
    }
    this.writeFailed.set(true);
  }

  /** Puts `document` on screen as the surface's current one, without queueing a write for it. */
  private adopt(surface: PreferenceSurface, document: unknown): void {
    const held = this.current();
    if (held !== null && !this.pending.has(surface)) {
      this.replace({ ...held, documents: { ...held.documents, [surface]: document } });
    }
  }

  private replace(next: Held): void {
    this.held.set(next);
    // An account the tenant keeps no record for lives for the session only: nothing is left on the browser.
    if (next.status !== 'unavailable') {
      writeMirror(next.subject, next.documents);
    }
  }

  private cancelTimer(surface: PreferenceSurface): void {
    const timer = this.timers.get(surface);
    if (timer !== undefined) {
      clearTimeout(timer);
      this.timers.delete(surface);
    }
  }

  private emptyOf(surface: PreferenceSurface): PreferenceDocuments[PreferenceSurface] {
    return surface === 'ORDER_BOARD' ? EMPTY_ORDER_BOARD_DOCUMENT : EMPTY_SHELL_DOCUMENT;
  }

  private sanitize(
    surface: PreferenceSurface,
    raw: unknown,
  ): PreferenceDocuments[PreferenceSurface] {
    return surface === 'ORDER_BOARD' ? sanitizeOrderBoardDocument(raw) : sanitizeShellDocument(raw);
  }

  private assign(target: Documents, surface: PreferenceSurface, stored: StoredPreference): void {
    if (surface === 'ORDER_BOARD') {
      target.ORDER_BOARD = sanitizeOrderBoardDocument(stored.document);
    } else {
      target.SHELL = sanitizeShellDocument(stored.document);
    }
  }

  /** The platform's document for a surface (`base`, nothing when it holds none) with this device's changes applied on top, in order. */
  private rebase(
    surface: PreferenceSurface,
    base: unknown,
    changes: ReadonlyArray<(document: never) => unknown>,
  ): PreferenceDocuments[PreferenceSurface] {
    return this.sanitize(
      surface,
      changes.reduce<unknown>(
        (current, change) => change(current as never),
        this.sanitize(surface, base ?? this.emptyOf(surface)),
      ),
    );
  }

  private assignRebased(
    target: Documents,
    surface: PreferenceSurface,
    changes: ReadonlyArray<(document: never) => unknown>,
  ): void {
    if (surface === 'ORDER_BOARD') {
      target.ORDER_BOARD = this.rebase(surface, target.ORDER_BOARD, changes) as OrderBoardDocument;
    } else {
      target.SHELL = this.rebase(surface, target.SHELL, changes) as ShellDocument;
    }
  }
}

// -------------------------------------------------------------------- the mirror

/**
 * A short, stable, non-reversible name for the subject's mirror key, so the raw subject is not what the
 * browser's storage lists. Not a security boundary and not meant to be one -- the mirror holds what the
 * signed-in person could read on screen -- only a key that is the same for the same person and different
 * for the next one. Two FNV-1a lanes over the code units, 16 hex digits.
 */
export function mirrorKeyFor(subject: string): string {
  let a = 0x811c9dc5;
  let b = 0x01000193 ^ subject.length;
  for (let index = 0; index < subject.length; index++) {
    const unit = subject.charCodeAt(index);
    a = Math.imul(a ^ unit, 0x01000193) >>> 0;
    b = Math.imul(b ^ ((unit << 5) | (unit >>> 3)), 0x85ebca6b) >>> 0;
  }
  const hex = (value: number): string => value.toString(16).padStart(8, '0');
  return MIRROR_PREFIX + hex(a) + hex(b);
}

function readMirror(subject: string): Documents {
  try {
    const raw = globalThis.localStorage?.getItem(mirrorKeyFor(subject));
    if (!raw) {
      return {};
    }
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== 'object' || parsed === null) {
      return {};
    }
    const documents: Documents = {};
    const record = parsed as Record<string, unknown>;
    if (record['ORDER_BOARD'] !== undefined) {
      documents.ORDER_BOARD = sanitizeOrderBoardDocument(record['ORDER_BOARD']);
    }
    if (record['SHELL'] !== undefined) {
      documents.SHELL = sanitizeShellDocument(record['SHELL']);
    }
    return documents;
  } catch {
    return {};
  }
}

function writeMirror(subject: string, documents: Documents): void {
  try {
    globalThis.localStorage?.setItem(mirrorKeyFor(subject), JSON.stringify(documents));
  } catch {
    // Lost between loads, not lost now -- the same tolerant stance as `CurrentLocation.selectLocation`.
  }
}

/** Every person's mirror on this browser except `keep`'s, not just the last one's: a sign-out leaves nothing behind. */
function removeEveryMirror(keep?: string): void {
  try {
    const storage = globalThis.localStorage;
    if (!storage) {
      return;
    }
    const keys: string[] = [];
    for (let index = 0; index < storage.length; index++) {
      const key = storage.key(index);
      if (key !== null && key.startsWith(MIRROR_PREFIX) && key !== keep) {
        keys.push(key);
      }
    }
    keys.forEach((key) => storage.removeItem(key));
  } catch {
    // Nothing to remove from a browser that keeps nothing.
  }
}
