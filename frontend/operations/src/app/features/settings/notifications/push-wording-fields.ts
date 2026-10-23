import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import {
  MEDIA_UPLOADER_REJECTION_MESSAGE_KEYS,
  MediaUploader,
} from '../../../shared/ui/media-uploader';
import { MediaApi } from '../../catalog/media-api';
import { describeApiError } from '../../orders/order-errors';

/** The cover is a 3:1 picture (ADR 0182, D14); the server checks the shape again when the wording is saved. */
const COVER_RATIOS = ['3:1'] as const;
const COVER_ACCEPT = 'image/jpeg,image/png,image/webp';
const COVER_MAXIMUM_BYTES = 2 * 1024 * 1024;

/**
 * What the server accepts as a push target (`NotificationTemplateService.TARGET_PATH`): one leading
 * slash and not two, printable ASCII, no space, no backslash, at most 199 characters. Restated here so
 * the author hears it before a save round trip, not instead of the server's own check.
 */
export const PUSH_TARGET_PATTERN = /^\/(?!\/)[\x21-\x5B\x5D-\x7E]{0,198}$|^\/$/;

/** Whether a push target is empty (the front page) or a path the server will accept. */
export function pushTargetIsValid(path: string): boolean {
  const trimmed = path.trim();
  return trimmed.length === 0 || PUSH_TARGET_PATTERN.test(trimmed);
}

/**
 * The two things a push wording adds to its text (ADR 0182, D14): a 3:1 cover the customer sees under
 * the words, and the storefront path a tap opens.
 *
 * <p>The cover goes up through the same media flow every public image does, as the tenant's own PUBLIC
 * asset, and the asset's id is all the wording keeps. Nothing about its shape is decided here: the
 * uploader crops to 3:1 and the server checks the shape, the owner and the visibility when the wording is
 * saved, and says which of them failed.
 */
@Component({
  selector: 'q-push-wording-fields',
  imports: [TPipe, MediaUploader],
  templateUrl: './push-wording-fields.html',
  styleUrl: './push-wording-fields.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PushWordingFields implements OnDestroy {
  private readonly mediaApi = inject(MediaApi);
  private readonly i18n = inject(I18n);

  readonly scope = input.required<LocationScope>();
  readonly coverAssetId = input<string | null>(null);
  readonly targetPath = input<string>('');

  readonly coverChange = output<string | null>();
  readonly targetPathChange = output<string>();

  protected readonly coverRatios = COVER_RATIOS;
  protected readonly coverAccept = COVER_ACCEPT;
  protected readonly coverMaximumBytes = COVER_MAXIMUM_BYTES;

  protected readonly uploading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly preview = signal<string | null>(null);
  protected readonly targetInvalid = computed(() => !pushTargetIsValid(this.targetPath()));

  protected onTarget(value: string): void {
    this.targetPathChange.emit(value);
  }

  protected removeCover(): void {
    this.revokePreview();
    this.coverChange.emit(null);
  }

  protected onRejected(reason: string): void {
    const key = MEDIA_UPLOADER_REJECTION_MESSAGE_KEYS[reason] ?? 'ui.mediaUploader.unsupportedType';
    this.error.set(this.i18n.t(key));
  }

  protected async upload(file: File): Promise<void> {
    if (this.uploading()) {
      return;
    }
    const scope = this.scope();
    this.uploading.set(true);
    this.error.set(null);
    try {
      const asset = await firstValueFrom(
        this.mediaApi.upload(scope.tenantId, 'TENANT', scope.tenantId, 'PUBLIC', file),
      );
      this.revokePreview();
      this.preview.set(URL.createObjectURL(file));
      this.coverChange.emit(asset.assetId);
    } catch (thrown) {
      this.error.set(
        thrown instanceof ApiError
          ? describeApiError(thrown, (key, values) => this.i18n.t(key, values))
          : this.i18n.t('error.unknown.noReference'),
      );
    } finally {
      this.uploading.set(false);
    }
  }

  ngOnDestroy(): void {
    this.revokePreview();
  }

  private revokePreview(): void {
    const url = this.preview();
    if (url?.startsWith('blob:')) {
      URL.revokeObjectURL(url);
    }
    this.preview.set(null);
  }
}
