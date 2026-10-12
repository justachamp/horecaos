import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { CurrentLocation } from '../../core/auth/current-location';
import { OwnProfile } from '../../core/auth/own-profile';
import { I18n, Locale, isLocale } from '../../core/i18n/i18n';
import { localeDisplayName } from '../../core/i18n/locale-labels';
import { MessageKey } from '../../core/i18n/messages.en';
import { PlatformLocales } from '../../core/i18n/platform-locales';
import { TPipe } from '../../core/i18n/t.pipe';
import { OwnPreferences } from '../../core/preferences/own-preferences';
import { SavedView } from '../../core/preferences/preference-documents';
import {
  SavedViewLabelProblem,
  normalisedSavedViewLabel,
  savedViewLabelProblem,
  withSavedViews,
  withoutColumnChoice,
} from '../../core/preferences/preference-edits';
import { ORDER_TAB_DEFINITIONS } from '../orders/order-tabs';

const PROBLEM_KEYS: Readonly<Record<SavedViewLabelProblem, MessageKey>> = {
  empty: 'orders.queue.views.problem.empty',
  tooLong: 'orders.queue.views.problem.tooLong',
  control: 'orders.queue.views.problem.control',
  number: 'orders.queue.views.problem.number',
};

/**
 * «Интерфейс» on «Мой профиль» (ADR 0160, decision 8, gap map row `0.2d`): what the console remembers for
 * the person, in one place they can read and undo.
 *
 *  - **Язык.** The language has an owner of its own: the account's (`uiLocale`, edited in «Личные данные»)
 *    and this device's (the rail's switcher). The device's wins, which is the whole reason the screen says
 *    so when the two differ and offers «Использовать мой язык на этом устройстве» -- the one way back that
 *    does not need the switcher.
 *  - **Филиал по умолчанию.** The branch the console opens on where this device has not chosen one. The
 *    platform checks the branch is one the person works at when it is saved.
 *  - **Мои виды.** The saved views of the order board, renamed or deleted here; they are made on the board.
 *  - **Столбцы доски.** A reset of the optional columns, and of everything the board remembers.
 *
 * Every change is written through {@link OwnPreferences} (debounced, versioned) and shown at once. A
 * person the tenant keeps no record for -- a support session, a device -- is told their choices last for
 * this session only. Nothing here is audited, and nothing is shown that the person did not make themselves.
 */
@Component({
  selector: 'q-my-profile-interface-card',
  imports: [TPipe],
  templateUrl: './my-profile-interface-card.html',
  styleUrl: './my-profile-interface-card.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MyProfileInterfaceCard {
  protected readonly i18n = inject(I18n);
  private readonly ownProfile = inject(OwnProfile);
  private readonly registry = inject(PlatformLocales);
  private readonly location = inject(CurrentLocation);
  protected readonly preferences = inject(OwnPreferences);

  // ----------------------------------------------------------------- language

  /** The language the account carries, as the registry's tag, or `null`. */
  private readonly accountLocale = computed<Locale | null>(() => {
    const code = this.ownProfile.member()?.uiLocale ?? null;
    if (code === null) {
      return null;
    }
    const tag = this.registry.canonical(code);
    return isLocale(tag) ? tag : null;
  });

  /** A choice made on this device that differs from the account's: the one case the screen has to explain. */
  protected readonly override = computed<{
    readonly device: string;
    readonly account: string;
  } | null>(() => {
    const device = this.i18n.deviceLocale();
    const account = this.accountLocale();
    if (device === null || account === null || device === account) {
      return null;
    }
    return { device: this.languageName(device), account: this.languageName(account) };
  });

  protected readonly languageLine = computed<{ readonly key: MessageKey; readonly name: string }>(
    () => {
      const current = this.languageName(this.i18n.locale());
      if (this.i18n.deviceLocale() !== null && this.accountLocale() === null) {
        return { key: 'staff.myProfile.interface.language.deviceOnly', name: current };
      }
      return { key: 'staff.myProfile.interface.language.current', name: current };
    },
  );

  protected useMyLanguage(): void {
    const account = this.accountLocale();
    this.i18n.clearDeviceLocale();
    if (account !== null) {
      this.i18n.setLocale(account, { persist: false });
    }
  }

  private languageName(locale: Locale): string {
    return localeDisplayName(this.i18n, locale, this.registry);
  }

  // ------------------------------------------------------------------- branch

  protected readonly branches = this.location.options;
  protected readonly defaultBranchId = computed(
    () => this.preferences.shell().defaultLocationId ?? '',
  );
  /** A default whose branch is no longer among the ones this person may switch to: the console ignores it. */
  protected readonly defaultIsStale = computed(() => {
    const chosen = this.defaultBranchId();
    return (
      chosen !== '' &&
      this.branches().length > 0 &&
      !this.branches().some((branch) => branch.id === chosen)
    );
  });

  protected chooseDefaultBranch(locationId: string): void {
    this.preferences.updateShell((document) => ({
      ...document,
      defaultLocationId: locationId === '' ? null : locationId,
    }));
  }

  // -------------------------------------------------------------------- views

  protected readonly views = computed(() => this.preferences.orderBoard().savedViews ?? []);

  /** The view being renamed, and what is typed so far. */
  protected readonly renaming = signal<string | null>(null);
  protected readonly renameDraft = signal('');
  protected readonly renameProblem = computed<MessageKey | null>(() => {
    if (this.renaming() === null) {
      return null;
    }
    const problem = savedViewLabelProblem(this.renameDraft());
    return problem === null ? null : PROBLEM_KEYS[problem];
  });

  protected tabLabelKey(view: SavedView): MessageKey {
    return ORDER_TAB_DEFINITIONS[view.tab].labelKey;
  }

  protected startRename(view: SavedView): void {
    this.renaming.set(view.id);
    this.renameDraft.set(view.label);
  }

  protected cancelRename(): void {
    this.renaming.set(null);
  }

  protected saveRename(view: SavedView): void {
    if (savedViewLabelProblem(this.renameDraft()) !== null) {
      return;
    }
    const label = normalisedSavedViewLabel(this.renameDraft());
    this.preferences.updateOrderBoard((document) =>
      withSavedViews(
        document,
        (document.savedViews ?? []).map((candidate) =>
          candidate.id === view.id ? { ...candidate, label } : candidate,
        ),
      ),
    );
    this.renaming.set(null);
  }

  protected deleteView(view: SavedView): void {
    this.preferences.updateOrderBoard((document) =>
      withSavedViews(
        document,
        (document.savedViews ?? []).filter((candidate) => candidate.id !== view.id),
      ),
    );
  }

  // ------------------------------------------------------------------ columns

  protected readonly hasColumnChoice = computed(() => {
    const columns = this.preferences.orderBoard().columns;
    return (columns?.show?.length ?? 0) + (columns?.hide?.length ?? 0) > 0;
  });

  protected resetColumns(): void {
    this.preferences.updateOrderBoard((document) => withoutColumnChoice(document));
  }

  /** The two-step reset of everything the board remembers (columns and views). */
  protected readonly confirmingReset = signal(false);
  protected readonly hasAnythingToReset = computed(
    () => this.hasColumnChoice() || this.views().length > 0,
  );

  protected askToReset(): void {
    this.confirmingReset.set(true);
  }

  protected cancelReset(): void {
    this.confirmingReset.set(false);
  }

  protected async resetBoard(): Promise<void> {
    this.confirmingReset.set(false);
    await this.preferences.reset('ORDER_BOARD');
  }
}
