import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { InlineAlert } from '../../../shared/ui/inline-alert';
import { PhoneFrame } from '../../../shared/ui/phone-frame';
import { describeApiError } from '../../orders/order-errors';
import {
  NotificationsApi,
  VariableCatalogueEntry,
  VersionResult,
  WIRED_CHANNELS,
  WordingResponse,
} from './notifications-api';

/** The three locales ADR 0035 requires, in the tag shape the platform stores. */
type LocaleTag = 'ru' | 'uz-Latn' | 'en';
const LOCALE_TAGS: readonly LocaleTag[] = ['ru', 'uz-Latn', 'en'];

/** Mirrors `TemplateRenderer`'s own placeholder grammar exactly: `{{name}}`, letters/digits/underscore. */
const PLACEHOLDER = /\{\{\s*([A-Za-z][A-Za-z0-9_]*)\s*\}\}/g;

/** Sample values for the live preview — the same defaults `TemplateTestSendService` renders a test send with. */
const SAMPLE_VALUES: Readonly<Record<string, string>> = {
  orderNumber: 'A-1042',
  amount: '85 000 UZS',
  currency: 'UZS',
  reasonCode: 'OUT_OF_STOCK',
  code: '482913',
};

/** Every placeholder a piece of text names, in first-appearance order. */
function variablesUsedIn(text: string): readonly string[] {
  const found = new Set<string>();
  for (const match of text.matchAll(PLACEHOLDER)) {
    found.add(match[1]);
  }
  return [...found];
}

function renderPreview(text: string): string {
  return text.replace(PLACEHOLDER, (whole, name: string) => SAMPLE_VALUES[name] ?? `[${name}]`);
}

/**
 * `TemplateEditor` (gap map rows `10.9a`/`X.27`, wave P36) — VariableChip
 * insertion and a live `q-phone-frame` preview, over the defect this wave
 * fixes underneath: the page used to hard-code `variablesSchema: {}` on
 * every version, and `TemplateRenderer.validate` correctly refuses any
 * placeholder that schema does not declare, so no variable-bearing template
 * could ever be authored. There is no separate "declare a variable" step
 * here: the schema submitted with a draft is exactly the set of `{{name}}`
 * placeholders the three bodies actually use, which is what
 * `TemplateRenderer.validate` checks against — declared and used can no
 * longer disagree, because one is derived from the other.
 */
@Component({
  selector: 'q-notification-template-editor',
  imports: [TPipe, InlineAlert, PhoneFrame],
  templateUrl: './template-editor.html',
  styleUrl: './template-editor.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TemplateEditor {
  private readonly api = inject(NotificationsApi);
  protected readonly i18n = inject(I18n);

  readonly scope = input.required<LocationScope>();
  readonly templateId = input.required<string>();
  readonly templateKey = input.required<string>();
  readonly notificationClass = input.required<string>();
  readonly channel = input.required<string>();
  /** The version to start from — the active version's own text, or null for a blank draft. */
  readonly prefill = input<readonly WordingResponse[] | null>(null);

  readonly saved = output<VersionResult>();
  readonly cancelled = output<void>();

  protected readonly localeTags = LOCALE_TAGS;
  protected readonly activeLocale = signal<LocaleTag>('ru');
  protected readonly bodies = signal<Record<LocaleTag, string>>(emptyBodies());
  protected readonly subjects = signal<Record<LocaleTag, string>>(emptyBodies());
  protected readonly hasSubject = computed(() => this.channel() === 'EMAIL');
  protected readonly isWired = computed(() => WIRED_CHANNELS.has(this.channel()));

  protected readonly catalogue = signal<readonly VariableCatalogueEntry[]>([]);
  protected readonly catalogueLoading = signal(true);
  protected readonly variablesForClass = computed(
    () => this.catalogue().find((entry) => entry.notificationClass === this.notificationClass())?.variables ?? [],
  );

  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly result = signal<VersionResult | null>(null);

  protected readonly preview = computed(() => renderPreview(this.bodies()[this.activeLocale()]));

  protected readonly canSave = computed(
    () => !this.submitting() && LOCALE_TAGS.every((tag) => this.bodies()[tag].trim().length > 0),
  );
  protected readonly anyBodyStarted = computed(() =>
    LOCALE_TAGS.some((tag) => this.bodies()[tag].trim().length > 0),
  );

  /**
   * Guards the two constructor effects below against re-running: both read a
   * required input (`scope`) that a caller must set before this component's
   * first change-detection pass — reading it eagerly in the constructor body
   * itself (rather than inside `effect()`) would throw in exactly the test
   * harness pattern that sets inputs via `componentRef.setInput` *after*
   * `TestBed.createComponent` instantiates it, so both are deferred here and
   * fire once each is satisfied rather than on every future signal change.
   */
  private prefillApplied = false;
  private catalogueRequested = false;

  constructor() {
    effect(() => {
      if (this.prefillApplied) {
        return;
      }
      const prefill = this.prefill();
      if (!prefill || prefill.length === 0) {
        return;
      }
      this.prefillApplied = true;
      const bodies = emptyBodies();
      const subjects = emptyBodies();
      for (const row of prefill) {
        if (isLocaleTag(row.locale)) {
          bodies[row.locale] = row.body;
          subjects[row.locale] = row.subject ?? '';
        }
      }
      this.bodies.set(bodies);
      this.subjects.set(subjects);
    });

    effect(() => {
      // scope() is read only to establish the dependency; the catalogue is
      // tenant/brand-scoped but identical across notification classes, so
      // this loads exactly once, the moment the required input is available.
      this.scope();
      if (this.catalogueRequested) {
        return;
      }
      this.catalogueRequested = true;
      void this.loadCatalogue();
    });
  }

  protected selectLocale(tag: LocaleTag): void {
    this.activeLocale.set(tag);
  }

  protected setBody(tag: LocaleTag, value: string): void {
    this.bodies.update((current) => ({ ...current, [tag]: value }));
  }

  protected setSubject(tag: LocaleTag, value: string): void {
    this.subjects.update((current) => ({ ...current, [tag]: value }));
  }

  /** Appends the placeholder to the active locale's own body — see the class doc for why nothing else is needed. */
  protected insertVariable(name: string): void {
    const tag = this.activeLocale();
    const current = this.bodies()[tag];
    const separator = current.length === 0 || current.endsWith(' ') ? '' : ' ';
    this.setBody(tag, `${current}${separator}{{${name}}}`);
  }

  protected async submit(): Promise<void> {
    if (!this.canSave()) {
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    try {
      const bodies = this.bodies();
      const subjects = this.subjects();
      const schema: Record<string, string> = {};
      for (const tag of LOCALE_TAGS) {
        for (const name of variablesUsedIn(bodies[tag])) {
          schema[name] = 'string';
        }
        if (this.hasSubject()) {
          for (const name of variablesUsedIn(subjects[tag])) {
            schema[name] = 'string';
          }
        }
      }

      const outcome = await this.api.addVersion(this.scope(), this.templateId(), {
        wordings: Object.fromEntries(
          LOCALE_TAGS.map((tag) => [
            tag,
            this.hasSubject() && subjects[tag].trim().length > 0
              ? { subject: subjects[tag].trim(), body: bodies[tag] }
              : { body: bodies[tag] },
          ]),
        ),
        variablesSchema: schema,
      });
      this.result.set(outcome);
      this.saved.emit(outcome);
    } catch (thrown) {
      this.error.set(this.describe(thrown));
    } finally {
      this.submitting.set(false);
    }
  }

  protected cancel(): void {
    this.cancelled.emit();
  }

  private async loadCatalogue(): Promise<void> {
    this.catalogueLoading.set(true);
    try {
      this.catalogue.set(await this.api.variableCatalogue(this.scope()));
    } finally {
      this.catalogueLoading.set(false);
    }
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}

function emptyBodies(): Record<LocaleTag, string> {
  return { ru: '', 'uz-Latn': '', en: '' };
}

function isLocaleTag(value: string): value is LocaleTag {
  return (LOCALE_TAGS as readonly string[]).includes(value);
}
