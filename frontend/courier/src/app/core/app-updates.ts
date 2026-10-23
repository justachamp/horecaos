import { Injectable, inject, signal } from '@angular/core';
import { SwUpdate, type VersionReadyEvent } from '@angular/service-worker';
import { filter } from 'rxjs';

/**
 * A new build of the app has been downloaded and is waiting.
 *
 * Never applied on its own: a courier in the middle of a delivery must not have the page reload
 * under them. The shell shows a banner and the courier taps "Update" when it suits them.
 */
@Injectable({ providedIn: 'root' })
export class AppUpdates {
  private readonly worker = inject(SwUpdate, { optional: true });

  private readonly waiting = signal(false);
  readonly ready = this.waiting.asReadonly();

  constructor() {
    this.worker?.versionUpdates
      .pipe(filter((event): event is VersionReadyEvent => event.type === 'VERSION_READY'))
      .subscribe(() => this.waiting.set(true));
  }

  async apply(): Promise<void> {
    try {
      await this.worker?.activateUpdate();
    } finally {
      globalThis.location.reload();
    }
  }
}
