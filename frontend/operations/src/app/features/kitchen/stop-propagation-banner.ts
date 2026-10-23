import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';

import { CurrentLocation } from '../../core/auth/current-location';
import { TimeZone, formatDateTime } from '../../core/format/datetime';
import { LocationScope } from '../../core/api/operations-paths';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { PropagationBinding, StopsApi, WouldPushItem } from './stop-scope-api';

/** See `stop-list-page.ts`'s identical constant — no location carries a timezone on this response yet. */
const PLACEHOLDER_TIME_ZONE: TimeZone = 'Asia/Tashkent';

/** One open list of a dry run's decisions: what has been read so far, and whether more is coming. */
interface WouldPushList {
  readonly items: readonly WouldPushItem[];
  readonly nextCursor: string | null;
  readonly loading: boolean;
  readonly failed: boolean;
}

/**
 * What each connected marketplace has and has not been told about the stop list (ADR 0141,
 * "When the partner is down" and "When there is no push API").
 *
 * Honest in the three ways it has to be. A marketplace whose provider has no availability
 * write API says it is **not propagated automatically** and tells the operator to use the
 * partner portal — never a quiet "in sync". A paused reconciler says so. A live one says how
 * many items the platform has not been able to confirm, and since when: "Yandex: 7 items not
 * confirmed since 14:32 — update in the partner portal". Silence is only for a location with no
 * marketplace binding at all.
 *
 * A dry run (ADR 0141 Phase 3, ADR 0180 Decision 3) is rehearsal output the manager compares with the partner's
 * portal for a week, so the count alone is not enough: the line offers the whole list, a page at a time, dish by
 * dish, with the dish's name and what the platform would have set it to.
 *
 * A failed read renders nothing: a banner that guessed would be worse than none, and the stop
 * list itself is unaffected.
 */
@Component({
  selector: 'q-stop-propagation-banner',
  imports: [TPipe],
  templateUrl: './stop-propagation-banner.html',
  styleUrl: './stop-propagation-banner.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StopPropagationBanner {
  private readonly stops = inject(StopsApi);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  /** Bump to re-read — the page does after every stop it makes or lifts. */
  readonly refreshKey = input(0);

  protected readonly bindings = signal<readonly PropagationBinding[]>([]);

  /** The dry runs whose list is open, by binding id. */
  protected readonly lists = signal<Readonly<Record<string, WouldPushList>>>({});

  /** Bumped on every read of a binding's list, so an answer for a list that was closed or restarted is dropped. */
  private readonly listSequence = new Map<string, number>();

  constructor() {
    effect(() => {
      this.refreshKey();
      untracked(() => void this.load());
    });
  }

  private async load(): Promise<void> {
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    try {
      this.bindings.set(await this.stops.propagation(scope));
    } catch {
      this.bindings.set([]);
    }
    await this.refreshOpenLists(scope);
  }

  /** A list stays open across a refresh and restarts from its first page; one whose binding left dry run closes. */
  private async refreshOpenLists(scope: LocationScope): Promise<void> {
    const open = Object.keys(this.lists());
    for (const bindingId of open) {
      const binding = this.bindings().find((candidate) => candidate.bindingId === bindingId);
      if (binding?.mode === 'DRY_RUN') {
        await this.readList(scope, bindingId, false);
      } else {
        this.closeList(bindingId);
      }
    }
  }

  protected hasList(binding: PropagationBinding): boolean {
    return binding.mode === 'DRY_RUN' && (binding.wouldPush ?? 0) > 0;
  }

  protected list(binding: PropagationBinding): WouldPushList | null {
    return this.lists()[binding.bindingId] ?? null;
  }

  protected toggleList(binding: PropagationBinding): void {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    if (this.list(binding)) {
      this.closeList(binding.bindingId);
    } else {
      void this.readList(scope, binding.bindingId, false);
    }
  }

  protected moreOfList(binding: PropagationBinding): void {
    const scope = this.location.scope();
    if (scope) {
      void this.readList(scope, binding.bindingId, true);
    }
  }

  private closeList(bindingId: string): void {
    this.listSequence.set(bindingId, (this.listSequence.get(bindingId) ?? 0) + 1);
    this.lists.update(({ [bindingId]: _closed, ...rest }) => rest);
  }

  /** The first page of a binding's list, or the page after what is already shown when `next` is set. */
  private async readList(scope: LocationScope, bindingId: string, next: boolean): Promise<void> {
    const sequence = (this.listSequence.get(bindingId) ?? 0) + 1;
    this.listSequence.set(bindingId, sequence);
    const before = next ? this.lists()[bindingId] : undefined;
    this.lists.update((lists) => ({
      ...lists,
      [bindingId]: {
        items: before?.items ?? [],
        nextCursor: before?.nextCursor ?? null,
        loading: true,
        failed: false,
      },
    }));
    try {
      const page = await this.stops.wouldPush(scope, bindingId, before?.nextCursor);
      if (this.listSequence.get(bindingId) !== sequence) {
        return;
      }
      this.lists.update((lists) => ({
        ...lists,
        [bindingId]: {
          items: [...(before?.items ?? []), ...page.items],
          nextCursor: page.nextCursor,
          loading: false,
          failed: false,
        },
      }));
    } catch {
      if (this.listSequence.get(bindingId) !== sequence) {
        return;
      }
      this.lists.update((lists) => ({
        ...lists,
        [bindingId]: {
          items: before?.items ?? [],
          nextCursor: before?.nextCursor ?? null,
          loading: false,
          failed: true,
        },
      }));
    }
  }

  /** What the platform would have set a dish to. One literal `t` key per case. */
  protected decision(item: WouldPushItem): string {
    return item.wouldPushAvailable
      ? this.i18n.t('kitchen.stopList.propagation.dryRun.available')
      : this.i18n.t('kitchen.stopList.propagation.dryRun.stopped');
  }

  /** What the platform last knew the partner to hold, or that it has never been told. */
  protected partnerHolds(item: WouldPushItem): string {
    if (item.confirmedAvailable === null || item.confirmedAvailable === undefined) {
      return this.i18n.t('kitchen.stopList.propagation.dryRun.partnerUnknown');
    }
    return item.confirmedAvailable
      ? this.i18n.t('kitchen.stopList.propagation.dryRun.partnerAvailable')
      : this.i18n.t('kitchen.stopList.propagation.dryRun.partnerStopped');
  }

  /** The one line a binding reads as. One literal `t` key per case, so a typo is a build error. */
  protected line(binding: PropagationBinding): string {
    const name = binding.displayName || binding.providerType;
    switch (binding.mode) {
      case 'MANUAL':
        return this.i18n.t('kitchen.stopList.propagation.manual', { name });
      case 'SUSPENDED':
        return this.i18n.t('kitchen.stopList.propagation.suspended', { name });
      case 'DRY_RUN':
        return this.i18n.t('kitchen.stopList.propagation.dryRun', {
          name,
          count: binding.wouldPush ?? binding.unconfirmed,
        });
      case 'AUTOMATIC':
        return binding.unconfirmed > 0
          ? this.i18n.t('kitchen.stopList.propagation.pending', {
              name,
              count: binding.unconfirmed,
              since: this.since(binding),
            })
          : this.i18n.t('kitchen.stopList.propagation.inSync', { name });
    }
  }

  protected needsAttention(binding: PropagationBinding): boolean {
    return binding.mode !== 'AUTOMATIC' || binding.unconfirmed > 0;
  }

  /**
   * The open stale-channel incident of a binding, if one is open (ADR 0180 Decision 1): the alert the manager may
   * have missed in the chat, kept in view until the partner confirms again, when it closes by itself.
   */
  protected incident(binding: PropagationBinding): string | null {
    return binding.incidentOpenSince
      ? this.i18n.t('kitchen.stopList.propagation.incident', {
          name: binding.displayName || binding.providerType,
          since: formatDateTime(new Date(binding.incidentOpenSince), PLACEHOLDER_TIME_ZONE),
        })
      : null;
  }

  private since(binding: PropagationBinding): string {
    return binding.oldestUnconfirmedSince
      ? formatDateTime(new Date(binding.oldestUnconfirmedSince), PLACEHOLDER_TIME_ZONE)
      : '—';
  }
}
