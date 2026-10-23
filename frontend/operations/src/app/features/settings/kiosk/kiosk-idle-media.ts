import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { environment } from '../../../../environments/environment';
import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import {
  MEDIA_UPLOADER_REJECTION_MESSAGE_KEYS,
  MediaUploader,
} from '../../../shared/ui/media-uploader';
import { KioskFrame } from '../../../shared/ui/kiosk-frame';
import { describeApiError } from '../../orders/order-errors';
import {
  IdleMediaItem,
  IdleMediaList,
  IdleUploadRejected,
  IdleUploadTimedOut,
  KioskApi,
  KioskChannelChoice,
} from './kiosk-api';

/** The only shape a kiosk's idle picture takes (ADR 0187, D25). */
const IDLE_RATIOS = ['9:16'] as const;

/** What a kiosk screen can show: still images, no video. */
const IDLE_ACCEPT = 'image/jpeg,image/png,image/webp';

/**
 * The long edge a crop is exported at: 720 x 1280, which keeps a photographic picture under the 1 MB cap with room to spare
 * where 1080 x 1920 would not.
 */
const IDLE_EXPORT_EDGE_PX = 1280;

/**
 * The largest original the uploader takes in before the crop. The platform's 1 MB cap is on the 720 x 1280 picture the crop
 * exports, not on the photograph the manager picks (an ordinary phone photo is several megabytes), so the cap is checked
 * after the crop in {@link KioskIdleMedia.onPicture} and never handed to the uploader, which applies its limit to the original.
 */
const IDLE_ORIGINAL_MAX_BYTES = 10 * 1024 * 1024;

/** The platform's `reason` for a refused attach, to the sentence that explains it. */
const REFUSAL_KEYS: Readonly<Record<string, MessageKey>> = {
  WRONG_ASPECT_RATIO: 'settings.kiosk.idle.refused.WRONG_ASPECT_RATIO',
  TOO_LARGE: 'settings.kiosk.idle.refused.TOO_LARGE',
  NOT_A_RASTER_IMAGE: 'settings.kiosk.idle.refused.NOT_A_RASTER_IMAGE',
  NOT_AVAILABLE: 'settings.kiosk.idle.refused.NOT_AVAILABLE',
  UNKNOWN_ASSET: 'settings.kiosk.idle.refused.UNKNOWN_ASSET',
  LIMIT: 'settings.kiosk.idle.refused.LIMIT',
  DUPLICATE: 'settings.kiosk.idle.refused.DUPLICATE',
};

/**
 * The idle media of a KIOSK channel, in the KIOSK card (ADR 0187, D25): up to eight 9:16 pictures of at most 1 MB that every
 * kiosk on the channel plays on its status screen. Upload (cropped to 9:16 in the manager's browser), put in order, remove,
 * and see the first picture in a kiosk frame.
 *
 * It is one list per channel, not per kiosk, and the card says so. The platform is the authority on the limits and sends
 * them with the list; the crop and the cap here are only so a manager hears "too large" before an upload and not after it.
 * A change is sent against the version the list was read at; a second manager's change in between is reloaded, not lost.
 */
@Component({
  selector: 'q-kiosk-idle-media',
  imports: [TPipe, MediaUploader, KioskFrame],
  templateUrl: './kiosk-idle-media.html',
  styleUrl: './kiosk-idle-media.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class KioskIdleMedia implements OnInit {
  private readonly api = inject(KioskApi);
  protected readonly i18n = inject(I18n);

  readonly scope = input.required<LocationScope>();
  /** The KIOSK channels active at this branch, as the card already read them. */
  readonly channels = input.required<readonly KioskChannelChoice[]>();
  /** Whether the caller holds `kiosk.device.manage`; without it the list is shown and nothing can change. */
  readonly canManage = input(false);

  protected readonly ratios = IDLE_RATIOS;
  protected readonly accept = IDLE_ACCEPT;
  protected readonly exportEdge = IDLE_EXPORT_EDGE_PX;
  protected readonly originalMaxBytes = IDLE_ORIGINAL_MAX_BYTES;

  protected readonly channelId = signal<string>('');
  protected readonly list = signal<IdleMediaList | null>(null);
  protected readonly state = signal<'idle' | 'loading' | 'ready' | 'denied' | 'error'>('idle');
  protected readonly busy = signal(false);
  protected readonly uploading = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly problem = signal<string | null>(null);

  protected readonly items = computed(() => this.list()?.items ?? []);
  protected readonly maximumCount = computed(() => this.list()?.maximumCount ?? 8);
  protected readonly full = computed(() => this.items().length >= this.maximumCount());
  protected readonly first = computed<IdleMediaItem | null>(() => this.items()[0] ?? null);

  async ngOnInit(): Promise<void> {
    const channels = this.channels();
    if (channels.length === 0) {
      return;
    }
    this.channelId.set(channels[0].channelId);
    await this.load();
  }

  protected async chooseChannel(channelId: string): Promise<void> {
    if (channelId === this.channelId()) {
      return;
    }
    this.channelId.set(channelId);
    this.notice.set(null);
    this.problem.set(null);
    await this.load();
  }

  private async load(): Promise<void> {
    this.state.set('loading');
    try {
      this.list.set(await this.api.idleMedia(this.scope(), this.channelId()));
      this.state.set('ready');
    } catch (error) {
      this.list.set(null);
      this.state.set(error instanceof ApiError && error.status === 403 ? 'denied' : 'error');
    }
  }

  /** A path on the API's own origin, as the list carries it. */
  protected src(item: IdleMediaItem): string {
    return `${environment.apiBaseUrl}${item.url}`;
  }

  protected positionLabel(item: IdleMediaItem): string {
    return this.i18n.t('settings.kiosk.idle.position', { n: item.position + 1 });
  }

  // ------------------------------------------------------------------------------- upload

  protected onRejected(reason: string): void {
    const key = MEDIA_UPLOADER_REJECTION_MESSAGE_KEYS[reason] ?? 'ui.mediaUploader.unsupportedType';
    this.notice.set(null);
    this.problem.set(this.i18n.t(key));
  }

  /** The cropped picture: checked against the cap here, uploaded, verified by the platform, then put at the end. */
  protected async onPicture(file: File): Promise<void> {
    const current = this.list();
    if (current === null || this.uploading() || this.busy()) {
      return;
    }
    this.notice.set(null);
    this.problem.set(null);
    if (file.size > current.maximumBytes) {
      this.problem.set(this.i18n.t('settings.kiosk.idle.tooLarge'));
      return;
    }
    this.uploading.set(true);
    try {
      const assetId = await this.api.uploadIdlePicture(this.scope(), this.channelId(), file);
      this.list.set(
        await firstValueFrom(
          this.api.attachIdle(this.scope(), this.channelId(), assetId, current.version),
        ),
      );
      this.notice.set(this.i18n.t('settings.kiosk.idle.added'));
    } catch (error) {
      await this.explain(error);
    } finally {
      this.uploading.set(false);
    }
  }

  // ------------------------------------------------------------------------------- order and remove

  protected async move(item: IdleMediaItem, by: -1 | 1): Promise<void> {
    const current = this.list();
    if (current === null || this.busy() || this.uploading()) {
      return;
    }
    const ids = current.items.map((entry) => entry.id);
    const at = ids.indexOf(item.id);
    const to = at + by;
    if (at < 0 || to < 0 || to >= ids.length) {
      return;
    }
    [ids[at], ids[to]] = [ids[to], ids[at]];
    await this.change(() =>
      firstValueFrom(this.api.reorderIdle(this.scope(), this.channelId(), ids, current.version)),
    );
  }

  protected async remove(item: IdleMediaItem): Promise<void> {
    const current = this.list();
    if (current === null || this.busy() || this.uploading()) {
      return;
    }
    await this.change(() =>
      firstValueFrom(this.api.removeIdle(this.scope(), this.channelId(), item.id, current.version)),
    );
  }

  private async change(call: () => Promise<IdleMediaList>): Promise<void> {
    this.busy.set(true);
    this.notice.set(null);
    this.problem.set(null);
    try {
      this.list.set(await call());
    } catch (error) {
      await this.explain(error);
    } finally {
      this.busy.set(false);
    }
  }

  private async explain(error: unknown): Promise<void> {
    if (error instanceof IdleUploadRejected) {
      this.problem.set(
        this.i18n.t(
          error.rejectionCode === 'PURPOSE_ASPECT_RATIO'
            ? 'settings.kiosk.idle.refused.WRONG_ASPECT_RATIO'
            : 'settings.kiosk.idle.rejected',
        ),
      );
    } else if (error instanceof IdleUploadTimedOut) {
      this.problem.set(this.i18n.t('settings.kiosk.idle.timedOut'));
    } else if (error instanceof ApiError && error.code === ApiErrorCode.STALE_VERSION) {
      // Someone else changed the loop first: show what is true now and ask for the change again.
      await this.load();
      this.problem.set(this.i18n.t('settings.kiosk.idle.stale'));
    } else if (error instanceof ApiError && error.code === 'IDLE_MEDIA_REFUSED') {
      const reason = error.problem?.['reason'];
      const key = typeof reason === 'string' ? REFUSAL_KEYS[reason] : undefined;
      this.problem.set(key ? this.i18n.t(key) : this.i18n.t('settings.kiosk.idle.rejected'));
    } else if (error instanceof ApiError) {
      this.problem.set(describeApiError(error, (key, values) => this.i18n.t(key, values)));
    } else {
      this.problem.set(this.i18n.t('error.unknown.noReference'));
    }
  }
}
