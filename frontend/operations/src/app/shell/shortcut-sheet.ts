import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';

import { I18n } from '../core/i18n/i18n';
import { ShortcutRegistry } from '../shared/keyboard/shortcut-registry';
import { Modal } from '../shared/ui/modal';
import { ShellMessageKey, shellMessages } from './shell-messages';

/**
 * The keyboard cheat-sheet, opened by `?` (orders.md §2.12, brands-and-locations.md §1.8,
 * statistics.md §1.4: «`?` shortcut sheet»).
 *
 * It draws the registry, not a list of its own: a scope's keys are on it for exactly as long as
 * that screen is mounted, so the sheet over the order board names the board's keys and the sheet
 * over Settings names Settings', and it cannot promise a key nothing handles. It is a `q-modal`,
 * so Escape closes it, focus is held inside it and returns to where it was.
 */
@Component({
  selector: 'q-shortcut-sheet',
  imports: [Modal],
  templateUrl: './shortcut-sheet.html',
  styleUrl: './shortcut-sheet.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ShortcutSheet {
  protected readonly registry = inject(ShortcutRegistry);
  private readonly i18n = inject(I18n);

  /** Scopes that have at least one key to show, the open screen's first. */
  protected readonly scopes = computed(() =>
    this.registry.scopes().filter((scope) => scope.shortcuts.length > 0),
  );

  protected text(key: ShellMessageKey): string {
    return shellMessages.text(this.i18n.locale(), key);
  }
}
