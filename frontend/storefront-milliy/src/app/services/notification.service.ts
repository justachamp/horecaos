import { Injectable, signal } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class NotificationService {
  private readonly message = signal<string | null>(null);
  private timeoutId: ReturnType<typeof setTimeout> | null = null;

  readonly currentMessage = this.message.asReadonly();

  show(msg: string, durationMs = 3000): void {
    if (this.timeoutId) clearTimeout(this.timeoutId);
    this.message.set(msg);
    this.timeoutId = setTimeout(() => {
      this.message.set(null);
      this.timeoutId = null;
    }, durationMs);
  }
}
