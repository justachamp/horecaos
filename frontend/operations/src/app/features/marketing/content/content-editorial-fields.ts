import {
  ChangeDetectionStrategy,
  Component,
  booleanAttribute,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { BlockDocument } from '../../../shared/ui/block-document';
import { RichText, RichTextImageUpload } from '../../../shared/ui/rich-text';
import { RuleList, RuleListItem, RuleReorder } from '../../../shared/ui/rule-list';
import { ContentDocument, ContentKind } from './content-api';
import { blockLabels } from './content-block-labels';
import {
  AlbumImageValue,
  ApplyType,
  EmploymentType,
  VacancyValue,
  albumImagesOf,
  bodyOf,
  coverAssetId,
  textOf,
  vacancyOf,
  withAlbumImages,
  withBody,
  withCoverAsset,
  withText,
  withVacancy,
} from './content-document';
import { ContentMedia } from './content-media';
import { ContentMediaField, MediaChange } from './content-media-field';
import { ContentReferences } from './content-references';

export type EditorialKind = Extract<ContentKind, 'NEWS' | 'RECIPE' | 'GALLERY_ALBUM' | 'VACANCY'>;

/** An album holds at most 30 images (the grammar's own limit). */
export const MAX_ALBUM_IMAGES = 30;

const EMPLOYMENT: readonly EmploymentType[] = ['FULL_TIME', 'PART_TIME', 'SHIFT', 'INTERNSHIP'];
const APPLY: readonly ApplyType[] = ['PHONE', 'TELEGRAM', 'URL', 'EMAIL'];

const EMPLOYMENT_KEYS: Readonly<Record<EmploymentType, MessageKey>> = {
  FULL_TIME: 'marketing.content.vacancy.employment.FULL_TIME',
  PART_TIME: 'marketing.content.vacancy.employment.PART_TIME',
  SHIFT: 'marketing.content.vacancy.employment.SHIFT',
  INTERNSHIP: 'marketing.content.vacancy.employment.INTERNSHIP',
};

const APPLY_KEYS: Readonly<Record<ApplyType, MessageKey>> = {
  PHONE: 'marketing.content.vacancy.apply.PHONE',
  TELEGRAM: 'marketing.content.vacancy.apply.TELEGRAM',
  URL: 'marketing.content.vacancy.apply.URL',
  EMAIL: 'marketing.content.vacancy.apply.EMAIL',
};

/**
 * The fields of the editorial tier (ADR 0157, Decisions 6 and 7; row `6.7a`): a news post, a recipe,
 * a photo album and a vacancy listing.
 *
 * A body is **a typed block document, never markup**: `q-rich-text` in its block mode emits the
 * document, the platform validates it, and the storefront renders one fixed component per block
 * type. A vacancy carries a title, a body, an employment type and **one outbound contact** the brand
 * chose to publish; there is no application form and the platform stores no candidate data, which is
 * what keeps the recruitment exclusion standing.
 */
@Component({
  selector: 'q-content-editorial-fields',
  imports: [ContentMediaField, RichText, RuleList, TPipe],
  templateUrl: './content-editorial-fields.html',
  styleUrl: './content-editorial-fields.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ContentEditorialFields {
  private readonly brand = inject(CurrentBrand);
  private readonly media = inject(ContentMedia);
  private readonly i18n = inject(I18n);
  protected readonly refs = inject(ContentReferences);

  readonly kind = input.required<EditorialKind>();
  readonly document = input.required<ContentDocument>();
  readonly locale = input.required<string>();
  readonly disabled = input(false, { transform: booleanAttribute });

  readonly documentChange = output<ContentDocument>();

  protected readonly selectedImage = signal(0);
  protected readonly maxImages = MAX_ALBUM_IMAGES;
  protected readonly employment = EMPLOYMENT;
  protected readonly applyTypes = APPLY;

  protected readonly hasBody = computed(() => this.kind() !== 'GALLERY_ALBUM');
  protected readonly hasCover = computed(() => this.kind() !== 'VACANCY');
  protected readonly isAlbum = computed(() => this.kind() === 'GALLERY_ALBUM');
  protected readonly isVacancy = computed(() => this.kind() === 'VACANCY');

  protected readonly title = computed(() => textOf(this.document(), this.locale(), 'title'));
  protected readonly summary = computed(() => textOf(this.document(), this.locale(), 'summary'));
  protected readonly cover = computed(() => coverAssetId(this.document()));
  protected readonly body = computed(() => bodyOf(this.document(), this.locale()));
  protected readonly vacancy = computed(() => vacancyOf(this.document()));
  protected readonly images = computed(() => albumImagesOf(this.document()));
  protected readonly imageIndex = computed(() => {
    const images = this.images();
    return images.length === 0 ? null : Math.min(this.selectedImage(), images.length - 1);
  });
  protected readonly image = computed(() => {
    const index = this.imageIndex();
    return index === null ? null : this.images()[index];
  });
  protected readonly imageCaption = computed(() => this.image()?.captions[this.locale()] ?? '');

  /** The editor's own words, rebuilt when the language is switched. */
  protected readonly labels = computed(() => {
    this.i18n.locale();
    return blockLabels((key, values) => this.i18n.t(key, values));
  });

  protected readonly imageItems = computed<readonly RuleListItem[]>(() => {
    this.i18n.locale();
    return this.images().map((image, index) => ({
      id: String(index),
      label: this.i18n.t('marketing.content.album.imageLabel', { n: index + 1 }),
      description: image.captions[this.locale()] ?? null,
      enabled: true,
    }));
  });

  /** Previews for the images the body names, so a saved draft shows its pictures. */
  protected readonly previews = computed(() => this.media.thumbnails());

  /** How the host uploads an image block: public, the brand's, verified before it is named. */
  protected readonly upload: RichTextImageUpload = async (file) => {
    const scope = this.brand.scope();
    if (scope === null) {
      throw new Error('No brand scope');
    }
    const assetId = await this.media.uploadImage(scope, file);
    return { assetId, previewUrl: this.media.thumbnails()[assetId] ?? null };
  };

  /** Whether a vacancy's `https` contact points at a site that is not on the allowlist (the gate refuses it). */
  protected readonly applyHostWarning = computed(() => {
    const { applyType, applyValue } = this.vacancy();
    return (
      (applyType === 'TELEGRAM' || applyType === 'URL') &&
      applyValue.trim() !== '' &&
      !this.refs.hostAllowed(applyValue)
    );
  });

  constructor() {
    // Ask for a thumbnail of every picture a saved draft names.
    effect(() => {
      const scope = this.brand.scope();
      if (scope === null) {
        return;
      }
      const ids = new Set<string>(this.images().map((image) => image.assetId));
      for (const block of this.body().blocks) {
        if (block.type === 'IMAGE' && block.assetId !== '') {
          ids.add(block.assetId);
        }
      }
      ids.forEach((id) => this.media.ensureThumbnail(scope, id));
    });
  }

  protected employmentKey(type: EmploymentType): MessageKey {
    return EMPLOYMENT_KEYS[type];
  }

  protected applyKey(type: ApplyType): MessageKey {
    return APPLY_KEYS[type];
  }

  protected setText(field: string, value: string): void {
    this.documentChange.emit(withText(this.document(), this.locale(), field, value));
  }

  protected setCover(change: MediaChange): void {
    this.documentChange.emit(withCoverAsset(this.document(), change.assetId));
  }

  protected setBody(body: BlockDocument): void {
    this.documentChange.emit(withBody(this.document(), this.locale(), body));
  }

  // ------------------------------------------------------------------------------ vacancy

  protected setEmployment(value: string): void {
    const employmentType = EMPLOYMENT.find((type) => type === value) ?? 'FULL_TIME';
    this.setVacancy({ employmentType });
  }

  protected setApplyType(value: string): void {
    const applyType = APPLY.find((type) => type === value) ?? 'PHONE';
    this.setVacancy({ applyType, applyValue: '' });
  }

  protected setApplyValue(applyValue: string): void {
    this.setVacancy({ applyValue });
  }

  private setVacancy(patch: Partial<VacancyValue>): void {
    this.documentChange.emit(withVacancy(this.document(), { ...this.vacancy(), ...patch }));
  }

  // -------------------------------------------------------------------------------- album

  protected selectImage(id: string): void {
    this.selectedImage.set(Number(id));
  }

  protected addImage(change: MediaChange): void {
    if (change.assetId === null || this.images().length >= MAX_ALBUM_IMAGES) {
      return;
    }
    this.selectedImage.set(this.images().length);
    this.writeImages([...this.images(), { assetId: change.assetId, captions: {} }]);
  }

  protected removeImage(): void {
    const index = this.imageIndex();
    if (index !== null) {
      this.writeImages(this.images().filter((_, i) => i !== index));
    }
  }

  protected reorderImages(order: RuleReorder): void {
    const images = this.images();
    const chosen = this.imageIndex();
    this.selectedImage.set(chosen === null ? 0 : order.indexOf(String(chosen)));
    this.writeImages(order.map((id) => images[Number(id)]));
  }

  protected setImageCaption(value: string): void {
    const index = this.imageIndex();
    if (index === null) {
      return;
    }
    const captions = { ...this.images()[index].captions };
    if (value === '') {
      delete captions[this.locale()];
    } else {
      captions[this.locale()] = value;
    }
    this.writeImages(
      this.images().map((image, i) => (i === index ? { ...image, captions } : image)),
    );
  }

  private writeImages(images: readonly AlbumImageValue[]): void {
    this.documentChange.emit(withAlbumImages(this.document(), images));
  }
}
