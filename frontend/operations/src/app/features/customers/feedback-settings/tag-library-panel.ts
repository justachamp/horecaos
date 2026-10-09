import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { BrandScope } from '../../../core/api/catalog-paths';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import {
  LabelsByLocale,
  labelDrafts,
  labelsToSend,
  localeDisplayName,
  pickLabel,
} from '../../../core/i18n/locale-labels';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { LocalizedFieldGroup } from '../../../shared/ui/localized-field-group';
import {
  MEDIA_UPLOADER_REJECTION_MESSAGE_KEYS,
  MediaUploader,
} from '../../../shared/ui/media-uploader';
import { MediaApi } from '../../catalog/media-api';
import { describeApiError } from '../../orders/order-errors';
import {
  FeedbackSettingsApi,
  FeedbackTag,
  NewTagInput,
  TAG_CATEGORIES,
  TagCategory,
  TagEditInput,
  TagLibrary,
  TagSentiment,
  TagStatus,
} from './feedback-settings-api';

type LoadState = 'loading' | 'ready' | 'denied' | 'error';

const ICON_ACCEPT = 'image/jpeg,image/png,image/webp';
const CODE_PATTERN = /^[a-z][a-z0-9_]{1,47}$/;
const SENTIMENTS: readonly TagSentiment[] = ['POSITIVE', 'NEGATIVE'];

/** What the editor holds while a tag is being written or changed. */
interface Draft {
  /** Null while creating. */
  readonly id: string | null;
  readonly code: string;
  readonly category: TagCategory;
  readonly sentiment: TagSentiment;
  readonly sortOrder: number;
  readonly iconAssetId: string | null;
  readonly used: boolean;
  readonly status: TagStatus;
  readonly version: number;
}

/**
 * The feedback tag library (ADR 0159, Decision 3, row 5.5): the tenant's one-tap reasons, each with
 * its wording in every language the tenant writes, a category and a sentiment, and a 3:2 icon.
 *
 * <p>A tag is retired, never deleted, and a tag a review carries has its code, category and
 * sentiment frozen because reports group by them -- the editor disables those fields and says why
 * rather than letting the server refuse a save. Wording uses {@code q-localized-field-group}; the
 * editor sends only the languages the person filled in, so a language it does not show keeps its
 * wording on the server. The icon goes up through the same media flow every public image does, as
 * a tenant-owned public asset, and the server checks it is a real 3:2 image under a megabyte.
 */
@Component({
  selector: 'q-tag-library-panel',
  imports: [TPipe, LocalizedFieldGroup, MediaUploader],
  templateUrl: './tag-library-panel.html',
  styleUrl: './tag-library-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TagLibraryPanel {
  private readonly api = inject(FeedbackSettingsApi);
  private readonly mediaApi = inject(MediaApi);
  protected readonly i18n = inject(I18n);

  readonly scope = input.required<BrandScope>();
  readonly canWrite = input(false);

  protected readonly state = signal<LoadState>('loading');
  protected readonly library = signal<TagLibrary | null>(null);
  protected readonly errorText = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly busy = signal(false);

  protected readonly draft = signal<Draft | null>(null);
  protected readonly wording = signal<Record<string, string>>({});
  protected readonly activeLocale = signal('');
  protected readonly iconPreview = signal<string | null>(null);
  protected readonly uploading = signal(false);

  protected readonly categories = TAG_CATEGORIES;
  protected readonly sentiments = SENTIMENTS;
  protected readonly iconAccept = ICON_ACCEPT;
  protected readonly iconRatios = ['3:2'] as const;

  protected readonly tags = computed(() => this.library()?.tags ?? []);
  protected readonly activeCount = computed(
    () => this.tags().filter((tag) => tag.status === 'ACTIVE').length,
  );
  protected readonly locales = computed(() => this.library()?.locales ?? []);
  protected readonly defaultLocale = computed(() => this.library()?.defaultLocale ?? 'ru');
  protected readonly localeLabels = computed<LabelsByLocale>(() =>
    Object.fromEntries(this.locales().map((l) => [l, localeDisplayName(this.i18n, l)])),
  );
  protected readonly completeness = computed<Record<string, boolean>>(() => {
    const written = this.wording();
    return Object.fromEntries(this.locales().map((l) => [l, (written[l] ?? '').trim() !== '']));
  });
  protected readonly iconMaximumBytes = computed(
    () => this.library()?.iconMaximumBytes ?? 1_048_576,
  );

  /** The editor can save when the code is well formed, the default language is written and nothing is in flight. */
  protected readonly canSave = computed(() => {
    const current = this.draft();
    if (current === null || this.busy() || this.uploading()) {
      return false;
    }
    const defaultWritten = (this.wording()[this.defaultLocale()] ?? '').trim() !== '';
    return (current.id !== null || CODE_PATTERN.test(current.code)) && defaultWritten;
  });

  protected readonly canImportStarter = computed(() => this.tags().length === 0);

  constructor() {
    // Read when the input arrives, not in the constructor: a required input has no value yet there.
    effect(() => {
      const tenantId = this.scope().tenantId;
      untracked(() => void this.load(tenantId));
    });
  }

  protected retry(): void {
    void this.load(this.scope().tenantId);
  }

  // ------------------------------------------------------------------------------ display

  protected label(tag: FeedbackTag): string {
    return pickLabel(tag.labels, this.i18n.locale(), [this.defaultLocale()]) || tag.code;
  }

  protected categoryKey(category: TagCategory): MessageKey {
    return `customers.feedback.tags.category.${category}`;
  }

  protected sentimentKey(sentiment: TagSentiment): MessageKey {
    return `customers.feedback.tags.sentiment.${sentiment}`;
  }

  protected statusKey(status: TagStatus): MessageKey {
    return `customers.feedback.tags.status.${status}`;
  }

  // ------------------------------------------------------------------------------ editing

  protected startNew(): void {
    this.begin(
      {
        id: null,
        code: '',
        category: 'FOOD',
        sentiment: 'NEGATIVE',
        sortOrder: (this.tags().reduce((max, tag) => Math.max(max, tag.sortOrder), 0) || 90) + 10,
        iconAssetId: null,
        used: false,
        status: 'ACTIVE',
        version: 0,
      },
      {},
    );
  }

  protected edit(tag: FeedbackTag): void {
    this.begin(
      {
        id: tag.id,
        code: tag.code,
        category: tag.category,
        sentiment: tag.sentiment,
        sortOrder: tag.sortOrder,
        iconAssetId: tag.iconAssetId,
        used: tag.used,
        status: tag.status,
        version: tag.version,
      },
      tag.labels,
    );
    void this.loadIconPreview(tag.iconAssetId);
  }

  protected cancel(): void {
    this.draft.set(null);
    this.revokePreview();
  }

  protected patch(change: Partial<Draft>): void {
    const current = this.draft();
    if (current !== null) {
      this.draft.set({ ...current, ...change });
    }
  }

  protected onCode(value: string): void {
    this.patch({ code: value.trim().toLowerCase() });
  }

  protected onCategory(value: string): void {
    this.patch({ category: value as TagCategory });
  }

  protected onSentiment(value: string): void {
    this.patch({ sentiment: value as TagSentiment });
  }

  protected onSortOrder(value: string): void {
    const parsed = Number.parseInt(value, 10);
    this.patch({ sortOrder: Number.isFinite(parsed) ? parsed : 0 });
  }

  protected onWording(locale: string, value: string): void {
    this.wording.update((current) => ({ ...current, [locale]: value }));
  }

  protected removeIcon(): void {
    this.patch({ iconAssetId: null });
    this.revokePreview();
  }

  protected onIconRejected(reason: string): void {
    const key = MEDIA_UPLOADER_REJECTION_MESSAGE_KEYS[reason] ?? 'ui.mediaUploader.unsupportedType';
    this.errorText.set(this.i18n.t(key));
  }

  protected async uploadIcon(file: File): Promise<void> {
    const scope = this.scope();
    if (this.uploading()) {
      return;
    }
    this.uploading.set(true);
    this.errorText.set(null);
    try {
      const asset = await firstValueFrom(
        this.mediaApi.upload(scope.tenantId, 'TENANT', scope.tenantId, 'PUBLIC', file),
      );
      this.patch({ iconAssetId: asset.assetId });
      this.revokePreview();
      this.iconPreview.set(URL.createObjectURL(file));
    } catch (error) {
      this.errorText.set(this.describe(error));
    } finally {
      this.uploading.set(false);
    }
  }

  protected async save(): Promise<void> {
    const current = this.draft();
    if (current === null || !this.canSave()) {
      return;
    }
    const locales = this.locales();
    const labels = labelsToSend(locales, this.wording());
    await this.run(async () => {
      const tenantId = this.scope().tenantId;
      if (current.id === null) {
        const input: NewTagInput = {
          code: current.code,
          category: current.category,
          sentiment: current.sentiment,
          iconAssetId: current.iconAssetId,
          sortOrder: current.sortOrder,
          labels,
        };
        await this.api.createTag(tenantId, input);
      } else {
        const input: TagEditInput = {
          category: current.category,
          sentiment: current.sentiment,
          iconAssetId: current.iconAssetId,
          sortOrder: current.sortOrder,
          status: current.status,
          labels,
        };
        await this.api.updateTag(tenantId, current.id, input, current.version);
      }
      this.draft.set(null);
      this.revokePreview();
    });
  }

  protected async setStatus(tag: FeedbackTag, status: TagStatus): Promise<void> {
    await this.run(async () => {
      await this.api.updateTag(
        this.scope().tenantId,
        tag.id,
        {
          category: tag.category,
          sentiment: tag.sentiment,
          iconAssetId: tag.iconAssetId,
          sortOrder: tag.sortOrder,
          status,
          labels: null,
        },
        tag.version,
      );
    });
  }

  protected async importStarter(): Promise<void> {
    await this.run(async () => {
      await this.api.importStarterPack(this.scope().tenantId);
      this.notice.set(this.i18n.t('customers.feedback.tags.editor.starterImported'));
    });
  }

  // ------------------------------------------------------------------------------ plumbing

  private begin(draft: Draft, existing: LabelsByLocale): void {
    this.errorText.set(null);
    this.notice.set(null);
    this.revokePreview();
    this.wording.set(labelDrafts(this.locales(), existing));
    this.activeLocale.set(this.defaultLocale());
    this.draft.set(draft);
  }

  /** Runs one write, then reads the library again; a lost race reloads and says so. */
  private async run(action: () => Promise<void>): Promise<void> {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.errorText.set(null);
    try {
      await action();
      this.library.set(await this.api.library(this.scope().tenantId));
    } catch (error) {
      this.errorText.set(this.describe(error));
      if (error instanceof ApiError && error.code === ApiErrorCode.STALE_VERSION) {
        this.library.set(await this.api.library(this.scope().tenantId).catch(() => this.library()));
      }
    } finally {
      this.busy.set(false);
    }
  }

  private async load(tenantId: string): Promise<void> {
    this.state.set('loading');
    try {
      this.library.set(await this.api.library(tenantId));
      this.state.set('ready');
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.state.set('denied');
      } else {
        this.errorText.set(this.describe(error));
        this.state.set('error');
      }
    }
  }

  private async loadIconPreview(assetId: string | null): Promise<void> {
    if (assetId === null) {
      return;
    }
    try {
      const url = await firstValueFrom(
        this.mediaApi.downloadUrl(this.scope().tenantId, assetId, 'THUMBNAIL'),
      );
      if (this.draft()?.iconAssetId === assetId && this.iconPreview() === null) {
        this.iconPreview.set(url);
      }
    } catch {
      // Non-critical: the editor shows that an icon is set, without the picture.
    }
  }

  private revokePreview(): void {
    const url = this.iconPreview();
    if (url?.startsWith('blob:')) {
      URL.revokeObjectURL(url);
    }
    this.iconPreview.set(null);
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return error.code === ApiErrorCode.STALE_VERSION
        ? this.i18n.t('customers.feedback.error.stale')
        : describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    throw error;
  }
}
