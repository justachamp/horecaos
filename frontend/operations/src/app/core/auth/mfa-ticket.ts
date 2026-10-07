import { Injectable, signal } from '@angular/core';

/**
 * The enrolment ticket a refused sign-in answered with (ADR 0148), held for the one screen that
 * redeems it.
 *
 * **In memory only, deliberately.** A ticket opens the enrolment endpoints for fifteen minutes
 * for one account. Putting it in the URL would land it in a history entry and an access log;
 * `sessionStorage` would let a stolen profile outlive the tab. Held here it survives navigation
 * inside the single-page app and nothing else: a reload loses it, and the person signs in again,
 * which is the point.
 */
@Injectable({ providedIn: 'root' })
export class MfaTicket {
  private readonly held = signal<string | null>(null);

  readonly ticket = this.held.asReadonly();

  hold(ticket: string): void {
    this.held.set(ticket);
  }

  clear(): void {
    this.held.set(null);
  }
}
