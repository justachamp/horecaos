import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { TranslatePipe } from '../../shared/translate/translate.pipe';
import { TermsSectionsPipe } from '../../shared/pipes/terms-sections.pipe';
import { BackDirective } from '../../shared/back/back.directive';
import { LangService } from '../../services/lang.service';
import { TermsService } from '../../services/terms.service';

type LoadState = 'loading' | 'ready' | 'error';

/**
 * The terms of service every tenant's customer reads (ADR 0067).
 *
 * Replaces the hardcoded, legacy-brand `TERMS_{EN,RU,UZ}_CONTENT` this
 * component used to render for every tenant regardless of who they actually
 * were. Now fetched from `TermsService.current()`: the tenant's own
 * published text, or the platform's brand-neutral default naming this
 * tenant's own brand.
 *
 * **Two ways to arrive here.** From the sign-in screen's "Terms of service"
 * link, with nobody signed in — read-only, exactly like before. Or from
 * `AuthCodeComponent` right after a sign-in whose acceptance-status check
 * came back false (a first-time customer, or a returning one whose accepted
 * version no longer matches what is in force) — carried via router state as
 * `{ mustAccept: true, returnTo }`, which renders an explicit "I agree"
 * action bar and, on tap, records acceptance before continuing to
 * `returnTo`.
 */
@Component({
  selector: 'app-terms-of-conditions',
  standalone: true,
  imports: [CommonModule, TranslatePipe, TermsSectionsPipe, BackDirective],
  templateUrl: './terms-of-conditions.component.html',
  styleUrl: './terms-of-conditions.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TermsOfConditionsComponent {
  private readonly lang = inject(LangService);
  private readonly terms = inject(TermsService);
  private readonly router = inject(Router);

  readonly selectedLangId = this.lang.langId;

  readonly state = signal<LoadState>('loading');
  readonly body = signal('');
  readonly isPlatformDefault = signal(false);
  readonly accepting = signal(false);
  readonly acceptError = signal<string | null>(null);

  /**
   * Set only when this screen was opened from `AuthCodeComponent`'s
   * post-sign-in gate. `history.state` rather than a route param: this is
   * navigation-local, one-shot state, the same mechanism `AuthCodeComponent`
   * itself reads `AuthCodeState` through.
   */
  private readonly returnTo: string | null;
  readonly mustAccept: boolean;

  constructor() {
    const state = history.state as { mustAccept?: boolean; returnTo?: string } | undefined;
    this.mustAccept = state?.mustAccept === true;
    this.returnTo = state?.returnTo ?? null;

    // Refetch whenever the customer switches language on this screen, not
    // only on first load -- the language selector on the login screen this
    // page is reached from stays live here too.
    effect(() => {
      this.selectedLangId();
      void this.load();
    });
  }

  private async load(): Promise<void> {
    this.state.set('loading');
    try {
      const document = await this.terms.current();
      this.body.set(document.body);
      this.isPlatformDefault.set(document.isPlatformDefault);
      this.state.set('ready');
    } catch {
      this.state.set('error');
    }
  }

  async agree(): Promise<void> {
    if (this.accepting()) return;
    this.acceptError.set(null);
    this.accepting.set(true);
    try {
      await this.terms.accept();
      await this.router.navigate([this.returnTo ?? '/locations']);
    } catch {
      this.acceptError.set('terms.acceptError');
    } finally {
      this.accepting.set(false);
    }
  }
}
