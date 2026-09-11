import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';

import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { Toast, Toasts } from './toast';

/**
 * The one place toasts are drawn. Mounted exactly once, in `shell.html`.
 *
 * **Two live regions, not one, and that is the whole reason this component has
 * any logic at all.** `aria-live="polite"` waits for the reader to finish its
 * current sentence; `role="alert"` interrupts. A failed save must interrupt —
 * the operator is about to move on believing it worked — and a successful one
 * must not, because interrupting a reader mid-row to say «Сохранено» is how a
 * screen-reader user loses their place on the order board. Toasts are therefore
 * partitioned by tone into two regions that are both always present in the DOM:
 * a live region created at the moment it gains content is frequently not
 * announced at all, which is the single most common way this pattern is built
 * wrong.
 */
@Component({
  selector: 'q-toast-host',
  imports: [TPipe],
  templateUrl: './toast-host.html',
  styleUrl: './toast-host.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ToastHost {
  private readonly toasts = inject(Toasts);

  protected readonly dismissLabelKey: MessageKey = 'ui.toast.dismiss';

  protected readonly alerts = computed(() =>
    this.toasts.visible().filter((toast) => toast.tone === 'error'),
  );

  protected readonly statuses = computed(() =>
    this.toasts.visible().filter((toast) => toast.tone !== 'error'),
  );

  protected trackId(_index: number, toast: Toast): number {
    return toast.id;
  }

  protected dismiss(id: number): void {
    this.toasts.dismiss(id);
  }
}
