import { Injectable, Signal, signal } from '@angular/core';

/**
 * A toast's tone. `error` is the only one that interrupts a screen reader;
 * see `ToastHost`'s two live regions.
 */
export type ToastTone = 'success' | 'info' | 'error';

export interface Toast {
  readonly id: number;
  /** Already translated. A toast is raised from a feature, and features hold their own keys. */
  readonly message: string;
  readonly tone: ToastTone;
}

export interface ToastRequest {
  readonly message: string;
  readonly tone?: ToastTone;
  /** How long before it dismisses itself. `0` pins it until the operator dismisses it. */
  readonly timeoutMs?: number;
}

/** Long enough to read a short sentence twice, short enough not to cover a row. */
export const DEFAULT_TOAST_TIMEOUT_MS = 5000;

/**
 * Where a mutation's outcome is announced (ADR 0101, row `X.17`).
 *
 * The gap this closes is stated plainly in the gap map and it is an
 * accessibility failure, not a polish item: "a successful save is announced
 * nowhere the operator is looking, and nothing is announced to a screen reader
 * at all; an action taken inside a dialog that closes on success gives no
 * confirmation that it happened."
 *
 * **A service plus one host, rather than a component each feature renders.**
 * The dialog case is what forces it: a dialog that succeeds closes, and a
 * confirmation rendered inside a component that no longer exists is not a
 * confirmation. The host lives in `shell.html`, outside the router outlet, so
 * it also survives the navigation a successful mutation frequently triggers.
 *
 * **Nothing here is persisted and nothing is replayed.** A toast is an
 * announcement about something that just happened in this tab. An operator who
 * was not looking has the screen itself as the record — which is why a failure
 * that needs acting on belongs in `q-inline-alert` beside the thing that
 * failed, not here.
 *
 * **No PII, ever** (ADR 0029). A toast is transient text on a shared terminal
 * in a restaurant; «Клиент создан» is the confirmation, not the phone number
 * that was typed. Callers pass a translated sentence, never interpolated
 * customer data.
 */
@Injectable({ providedIn: 'root' })
export class Toasts {
  private readonly items = signal<readonly Toast[]>([]);
  private sequence = 0;

  /** Read by `ToastHost` and by nothing else. Features call {@link show}. */
  readonly visible: Signal<readonly Toast[]> = this.items.asReadonly();

  show(request: ToastRequest): number {
    this.sequence += 1;
    const id = this.sequence;
    const toast: Toast = {
      id,
      message: request.message,
      tone: request.tone ?? 'info',
    };
    this.items.update((current) => [...current, toast]);

    const timeoutMs = request.timeoutMs ?? DEFAULT_TOAST_TIMEOUT_MS;
    if (timeoutMs > 0) {
      // Deliberately not tied to a `DestroyRef`: this service is root-provided
      // and lives as long as the application does, so there is no scope for the
      // timer to outlive. Dismissing by id is idempotent.
      setTimeout(() => this.dismiss(id), timeoutMs);
    }
    return id;
  }

  dismiss(id: number): void {
    this.items.update((current) => current.filter((toast) => toast.id !== id));
  }

  /** Used by specs and by a sign-out, which must not leave the previous session's words on screen. */
  clear(): void {
    this.items.set([]);
  }
}
