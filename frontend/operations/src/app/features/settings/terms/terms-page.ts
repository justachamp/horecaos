import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { I18n } from '../../../core/i18n/i18n';
import { PlatformLocales } from '../../../core/i18n/platform-locales';
import { resolveLocaleSet } from '../../../core/i18n/locale-set';
import { TPipe } from '../../../core/i18n/t.pipe';
import { RichText, RichTextBlockKindLabels } from '../../../shared/ui/rich-text';
import { sanitizeRichHtml } from '../../../shared/ui/rich-text-sanitizer';
import { describeApiError } from '../../orders/order-errors';
import { BrandProfileApi, BrandView } from '../brand-profile/brand-profile-api';
import {
  PublishTermsRequest,
  TermsApi,
  TermsVersionSummaryView,
  TermsVersionView,
} from './terms-api';

type PageState = 'loading' | 'ready' | 'denied' | 'error';

/**
 * 10.12 Terms of service (ADR 0067) — the tenant's own console for writing
 * and publishing its storefront terms-of-service text, replacing the
 * legacy-brand text the storefront used to ship hardcoded.
 *
 * **Tenant-scoped with its own brand picker, not `CurrentLocation` — read
 * this before touching scope resolution.** `TERMS_READ`/`TERMS_MANAGE` are
 * granted only to the `tenant-owner` role bundle, and that bundle is
 * `TENANT`-scoped: it carries no `BRAND`- or `LOCATION`-scoped grant row at
 * all. `CurrentBrand` and `CurrentLocation` both derive their answer by
 * scanning the session's `BRAND`/`LOCATION`-scoped grants (see
 * `current-tenant.ts`'s own doc comment), so both would resolve to
 * null/denied for exactly the principal this screen exists for — the same
 * trap `data-privacy-page.ts` documents and the same fix: resolve the
 * tenant from `CurrentTenant`, then let the operator pick which brand's
 * terms to author from `BrandProfileApi.list`. With one brand (the common
 * pilot case) the picker auto-selects it with no visible friction; with more
 * than one, a plain `<select>` appears.
 *
 * **Row 10.12 — the languages are the brand's own, not a fixed ru/uz/en
 * triple.** Whatever brand the picker holds decides which editors appear,
 * from that brand's own supported-language set (`BrandView.locales`, run
 * through {@link resolveLocaleSet} — the rule `LocaleSet` applies to the
 * operator's own brand, which this screen cannot use because the tenant owner
 * it exists for has no brand scope of their own): default language first and
 * marked, the platform triple for a brand that has chosen none. The server's
 * only rule is at least one language ({@link TermsPublishingService}), so no
 * language is required here beyond that.
 *
 * **A version is a whole new document, so a language the brand does not offer
 * is carried forward, never dropped.** Publishing inserts the next version with
 * exactly the languages in the request — the server does not copy the ones a
 * caller leaves out — so a brand that narrowed its languages after publishing
 * in a third would silently lose that text from the live terms the moment any
 * unrelated edit republished. {@link submitPublish} therefore sends the
 * current version's text for every language the editors do not show, unchanged
 * (the same rule `channel-setup-page.ts` applies to a static page).
 *
 * **Switching brands empties the form until the new brand's text has
 * arrived.** The picker changes {@link locales} at once (they derive from the
 * picked brand), but the text in {@link current} and {@link drafts} belongs to
 * the brand that was picked before, and a publish sends the current version's
 * text for every language the editors do not show. Left in place through the
 * two reads, that would publish the previous brand's hidden-language text — and
 * its drafts — as the new brand's legal terms. {@link selectBrand} therefore
 * clears them, shows the form as loading ({@link brandLoading}) and takes a
 * ticket ({@link brandLoadSeq}); a reply for a brand that is no longer the
 * picked one is dropped, and a publish that lands after the operator moved on
 * does not write its result into the other brand's form.
 *
 * **What "never published" means, concretely.** `TermsApi.current` returns
 * `published: false` with empty `contentsByLocale` for a brand that has
 * never published — not an error. The storefront is, right now, serving the
 * platform's own neutral default terms text for that brand, and the screen
 * says so plainly rather than showing an empty state that looks broken.
 */
@Component({
  selector: 'q-terms-page',
  imports: [TPipe, RichText],
  templateUrl: './terms-page.html',
  styleUrl: './terms-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TermsPage {
  private readonly tenant = inject(CurrentTenant);
  private readonly brandsApi = inject(BrandProfileApi);
  private readonly api = inject(TermsApi);
  protected readonly i18n = inject(I18n);
  private readonly registry = inject(PlatformLocales);

  protected readonly state = signal<PageState>('loading');
  protected readonly loadErrorText = signal<string | null>(null);

  protected readonly brands = signal<readonly BrandView[]>([]);
  protected readonly selectedBrandId = signal<string | null>(null);

  /** True from the moment a brand is picked until its terms and history have arrived. */
  protected readonly brandLoading = signal(false);

  /**
   * A ticket for the latest brand load: a reply that arrives under an older one
   * belongs to a brand the operator has since left, and must change nothing.
   */
  private brandLoadSeq = 0;

  protected readonly current = signal<TermsVersionView | null>(null);
  protected readonly history = signal<readonly TermsVersionSummaryView[]>([]);

  /** The text typed per offered language, keyed by locale tag. */
  protected readonly drafts = signal<Readonly<Record<string, string>>>({});
  protected readonly note = signal('');

  /** The picked brand's own languages (row 10.12): default first, the platform triple when it has chosen none. */
  private readonly localeSet = computed(() =>
    resolveLocaleSet(
      this.brands().find((brand) => brand.id === this.selectedBrandId())?.locales,
      this.registry,
    ),
  );
  protected readonly locales = computed(() => this.localeSet().locales);

  /**
   * Text the current version carries in a language the editors do not offer.
   * Not shown, not editable here — and sent back unchanged on every publish.
   */
  private readonly hiddenContents = computed<Readonly<Record<string, string>>>(() => {
    const offered = new Set<string>(this.locales());
    return Object.fromEntries(
      Object.entries(this.current()?.contentsByLocale ?? {}).filter(
        ([locale, text]) => !offered.has(locale) && text.trim().length > 0,
      ),
    );
  });

  /** `q-rich-text`'s own shape, row `X.31` — one set of labels for all three locale editors. */
  protected readonly richTextKindLabels = computed<RichTextBlockKindLabels>(() => ({
    paragraph: this.i18n.t('ui.richText.kind.paragraph'),
    heading: this.i18n.t('ui.richText.kind.heading'),
    bullet: this.i18n.t('ui.richText.kind.bullet'),
    numbered: this.i18n.t('ui.richText.kind.numbered'),
  }));

  protected readonly publishSubmitting = signal(false);
  protected readonly publishError = signal<string | null>(null);
  protected readonly publishedNotice = signal<string | null>(null);

  protected readonly expandedVersion = signal<number | null>(null);
  protected readonly previewContent = signal<TermsVersionView | null>(null);
  protected readonly previewLoading = signal(false);
  protected readonly previewError = signal<string | null>(null);

  private tenantId: string | null = null;

  constructor() {
    void this.load();
  }

  protected retry(): void {
    void this.load();
  }

  /**
   * At least one offered language must carry text — the server's "at least one
   * language" rule, mirrored so the button disables rather than a submit bouncing
   * off a 400. A language kept only because it is hidden does not count: an
   * operator who cleared every language they can see is not publishing.
   */
  protected canPublish(): boolean {
    return (
      !this.publishSubmitting() &&
      !this.brandLoading() &&
      this.locales().some((locale) => this.draftFor(locale).trim().length > 0)
    );
  }

  protected draftFor(locale: string): string {
    return this.drafts()[locale] ?? '';
  }

  protected setDraft(locale: string, value: string): void {
    this.drafts.update((current) => ({ ...current, [locale]: value }));
  }

  protected isDefaultLocale(locale: string): boolean {
    return this.localeSet().defaultLocale === locale;
  }

  /** The language's name for the editor's label: "Russian (ru)". `MessageKey` refuses a key built by concatenation, on purpose. */
  protected fieldLabel(locale: string): string {
    switch (locale) {
      case 'ru':
        return this.i18n.t('settings.terms.field.ru');
      case 'uz-Latn':
        return this.i18n.t('settings.terms.field.uz');
      case 'en':
        return this.i18n.t('settings.terms.field.en');
      default:
        return locale;
    }
  }

  /**
   * The panels a past version's preview shows: every language the version
   * carries — including one the brand no longer offers, which is exactly the
   * text a hidden language would otherwise never let anyone read — plus each
   * language the brand offers that this version lacks, named "not included".
   * Canonical order, not the editors' default-first one: it is a document
   * being read, not a form being filled.
   */
  protected previewLocales(preview: TermsVersionView): readonly string[] {
    const present = Object.keys(preview.contentsByLocale);
    const offered = new Set<string>(this.locales());
    // The registry's order, over every language it declares: a version can carry text in a language
    // the registry has since stopped offering, and that text is still a document someone may read.
    const known = this.registry.fallbackOrder();
    return [
      ...known.filter((locale) => offered.has(locale) || present.includes(locale)),
      ...present.filter((locale) => !known.includes(locale)),
    ];
  }

  protected async selectBrand(brandId: string): Promise<void> {
    if (brandId === this.selectedBrandId()) {
      return;
    }
    const ticket = ++this.brandLoadSeq;
    this.selectedBrandId.set(brandId);
    // The previous brand's document, drafts and history are not this brand's: out
    // of the form now, not when the replacement arrives.
    this.current.set(null);
    this.history.set([]);
    this.drafts.set({});
    this.note.set('');
    this.brandLoading.set(true);
    this.expandedVersion.set(null);
    this.previewContent.set(null);
    this.previewError.set(null);
    this.publishedNotice.set(null);
    this.publishError.set(null);
    this.loadErrorText.set(null);
    try {
      await this.loadBrandData(brandId, ticket);
    } catch (error) {
      if (ticket === this.brandLoadSeq) {
        this.handleLoadFailure(error);
      }
    } finally {
      if (ticket === this.brandLoadSeq) {
        this.brandLoading.set(false);
      }
    }
  }

  protected async submitPublish(): Promise<void> {
    const tenantId = this.tenantId;
    const brandId = this.selectedBrandId();
    if (!tenantId || !brandId || !this.canPublish()) {
      return;
    }

    const contentsByLocale: Record<string, string> = { ...this.hiddenContents() };
    for (const locale of this.locales()) {
      const text = this.draftFor(locale).trim();
      if (text) {
        contentsByLocale[locale] = text;
      }
    }
    const request: PublishTermsRequest = {
      contentsByLocale,
      note: this.note().trim() || undefined,
    };

    this.publishSubmitting.set(true);
    this.publishError.set(null);
    this.publishedNotice.set(null);
    try {
      const published = await this.api.publish(tenantId, brandId, request);
      const history = await this.api.list(tenantId, brandId);
      if (this.selectedBrandId() !== brandId) {
        // The operator picked another brand while this one was publishing: its
        // result is not that brand's form to fill.
        return;
      }
      this.current.set(published);
      this.history.set(history);
      this.note.set('');
      this.publishedNotice.set(
        this.i18n.t('settings.terms.publish.success', { version: published.version ?? 0 }),
      );
    } catch (error) {
      this.publishError.set(this.describe(error));
    } finally {
      this.publishSubmitting.set(false);
    }
  }

  protected async togglePreview(entry: TermsVersionSummaryView): Promise<void> {
    if (this.expandedVersion() === entry.version) {
      this.expandedVersion.set(null);
      this.previewContent.set(null);
      this.previewError.set(null);
      return;
    }
    const tenantId = this.tenantId;
    const brandId = this.selectedBrandId();
    if (!tenantId || !brandId) {
      return;
    }
    this.expandedVersion.set(entry.version);
    this.previewContent.set(null);
    this.previewError.set(null);
    this.previewLoading.set(true);
    try {
      this.previewContent.set(await this.api.version(tenantId, brandId, entry.version));
    } catch (error) {
      this.previewError.set(this.describe(error));
    } finally {
      this.previewLoading.set(false);
    }
  }

  private async load(): Promise<void> {
    this.state.set('loading');
    await this.tenant.ensureLoaded();
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      this.state.set(this.tenant.denied() ? 'denied' : 'error');
      return;
    }
    this.tenantId = tenantId;
    const ticket = ++this.brandLoadSeq;
    try {
      const brands = await this.brandsApi.list(tenantId);
      this.brands.set(brands);
      const firstBrand = brands[0];
      if (!firstBrand) {
        this.loadErrorText.set(this.i18n.t('settings.terms.noBrands'));
        this.state.set('error');
        return;
      }
      this.selectedBrandId.set(firstBrand.id);
      await this.loadBrandData(firstBrand.id, ticket);
      if (ticket !== this.brandLoadSeq) {
        return;
      }
      this.state.set('ready');
    } catch (error) {
      this.handleLoadFailure(error);
    }
  }

  /**
   * The published version's own stored content, re-sanitized before it ever
   * reaches `[innerHTML]` — `q-rich-text` only ever emits sanitized HTML, but
   * this read has no way to know a given row came from that editor rather
   * than an older plain-text publish or a direct write elsewhere, and a
   * preview is exactly where an unsanitized value would run. A legacy
   * plain-text value passes through unchanged: nothing in it is a tag.
   */
  protected previewHtml(preview: TermsVersionView, locale: string): string | null {
    const content = preview.contentsByLocale[locale];
    return content === undefined ? null : sanitizeRichHtml(content);
  }

  private async loadBrandData(brandId: string, ticket: number): Promise<void> {
    const tenantId = this.tenantId;
    if (!tenantId) {
      return;
    }
    const [current, history] = await Promise.all([
      this.api.current(tenantId, brandId),
      this.api.list(tenantId, brandId),
    ]);
    if (ticket !== this.brandLoadSeq) {
      // The operator picked another brand while these reads were in flight.
      return;
    }
    this.current.set(current);
    this.history.set(history);
    this.drafts.set(
      Object.fromEntries(
        this.locales().map((locale) => [locale, current.contentsByLocale[locale] ?? '']),
      ),
    );
  }

  private handleLoadFailure(error: unknown): void {
    if (error instanceof ApiError && error.status === 403) {
      this.state.set('denied');
    } else {
      this.loadErrorText.set(this.describe(error));
      this.state.set('error');
    }
  }

  /**
   * `describeApiError`'s own mapping has no dedicated entry for
   * VALIDATION_FAILED, so it falls through to that helper's shared
   * unmapped-4xx branch: the server's own validation message for a publish
   * with no non-blank locale ("at least one locale is required") plus the
   * correlation id, exactly as every other unmapped 400 in the console
   * renders (`order-errors.ts`). This used to short-circuit before reaching
   * that branch and return `problem.detail` alone, which quietly dropped the
   * reference an operator would otherwise have to hand to support — the one
   * screen in the console whose validation failures rendered differently
   * from the rest.
   */
  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
