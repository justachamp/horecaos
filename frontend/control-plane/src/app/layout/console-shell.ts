import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { AuthService } from '../core/auth/auth.service';
import { SessionContextService } from '../core/auth/session-context.service';
import { I18nService, LOCALES, Locale } from '../core/i18n/i18n.service';
import { MessageKey } from '../core/i18n/messages.en';
import { ROUTED_SECTIONS, Section } from './sections';

/** One rail row: the section, and the group heading to print above it, if any. */
interface SectionRow {
  readonly section: Section;
  readonly groupHeader?: MessageKey;
}

/**
 * The CONSOLE shell: a near-black rail, a hairline-bordered content area, and
 * nothing decorative.
 *
 * Structure and density are taken from the prototype at
 * frontend/prototypes/control-plane in the platform repository, which is
 * throwaway React and is never imported — only read. Every colour, size and
 * duration below comes from the vendored token sheet.
 */
@Component({
  selector: 'app-console-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './console-shell.html',
  styleUrl: './console-shell.css',
})
export class ConsoleShell {
  protected readonly i18n = inject(I18nService);
  protected readonly auth = inject(AuthService);

  private readonly session = inject(SessionContextService);

  protected readonly locales = LOCALES;

  /**
   * The rail, filtered to what this principal may reach, with a group heading
   * attached to the first row of each run of same-`group` sections.
   *
   * Hiding a section is a courtesy: the API refuses the calls behind it either
   * way. Sections with no capability requirement are always shown.
   */
  protected readonly sections = computed<readonly SectionRow[]>(() => {
    const visible = ROUTED_SECTIONS.filter(
      (section) => section.capability === undefined || this.session.has(section.capability),
    );

    let lastGroup: MessageKey | undefined;
    return visible.map((section) => {
      const groupHeader = section.group !== lastGroup ? section.group : undefined;
      lastGroup = section.group;
      return { section, groupHeader };
    });
  });

  protected readonly operatorName = computed(
    () => this.auth.displayName() ?? this.i18n.t('shell.unknownOperator'),
  );

  protected readonly today = computed(() => this.i18n.day(new Date()));

  /**
   * The four `auth.*` keys exist for exactly the four `AuthStatus` values, so
   * the cast is safe. A `Record<AuthStatus, MessageKey>` lookup table would be
   * safer still and would have to be kept in step by hand; this cannot drift
   * because the compiler rejects an `auth.*` key that is not in the catalogue.
   */
  protected readonly statusLabel = computed(() =>
    this.i18n.t(`auth.${this.auth.status()}` as MessageKey),
  );

  protected localeLabel(locale: Locale): string {
    return this.i18n.t(`locale.${locale}` as MessageKey);
  }

  protected switchLocale(locale: Locale): void {
    this.i18n.use(locale);
  }
}
