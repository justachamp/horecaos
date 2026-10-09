import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { BrandScope } from '../../../core/api/catalog-paths';
import { MediaApi } from '../../catalog/media-api';

/** The platform refused the file after reading it (ADR 0157, Decision 8); the media API names no code. */
export class MediaRefusedError extends Error {
  constructor(readonly kind: 'IMAGE' | 'VIDEO') {
    super(`The platform refused the ${kind.toLowerCase()}`);
    this.name = 'MediaRefusedError';
  }
}

/** The platform did not finish verifying within the time this screen waits. */
export class MediaNotReadyError extends Error {
  constructor() {
    super('The upload has not been verified yet');
    this.name = 'MediaNotReadyError';
  }
}

export interface UploadedVideo {
  readonly assetId: string;
  readonly posterAssetId: string;
}

/**
 * The content editor's uploads (ADR 0157, Decision 8 and the media section).
 *
 * Every creative is allocated **`PUBLIC`**, owned by the brand, and -- for an image -- with the
 * `CREATIVE` rendition profile, which is what makes the platform render the extra `HERO` size a
 * home banner needs. The storefront redirect answers 404 for anything that is not public and
 * displayable, and never says which, so a private asset would pass the screen and show every
 * customer a broken image.
 *
 * Verification is asynchronous: finalize answers `UPLOADED`, and a worker later settles `AVAILABLE`
 * or `REJECTED`. Nothing can be published until the former, so this waits for the verdict and
 * reports a refusal as itself, not as a timeout. The thumbnails it hands the editor are the file the
 * operator just chose (a local object URL, free and immediate) or, for an asset a saved draft names,
 * the platform's own `THUMBNAIL` rendition, best effort.
 */
@Injectable({ providedIn: 'root' })
export class ContentMedia {
  private readonly media = inject(MediaApi);

  /** asset id -> a URL to draw it by. */
  readonly thumbnails = signal<Readonly<Record<string, string>>>({});
  private readonly requested = new Set<string>();

  /** How long to wait between looks at an asset's status, and for how many looks (overridden in specs). */
  protected pollMilliseconds = 1000;
  protected imageAttempts = 30;
  protected videoAttempts = 60;

  async uploadImage(scope: BrandScope, file: File): Promise<string> {
    const assetId = await this.send(scope, file, 'CREATIVE');
    this.remember(assetId, URL.createObjectURL(file));
    await this.untilAvailable(scope, assetId, 'IMAGE', this.imageAttempts);
    return assetId;
  }

  /** The video, then its poster; the poster is an image like any other creative. */
  async uploadVideo(scope: BrandScope, video: File, poster: File): Promise<UploadedVideo> {
    const posterAssetId = await this.uploadImage(scope, poster);
    const assetId = await this.send(scope, video, undefined);
    await this.untilAvailable(scope, assetId, 'VIDEO', this.videoAttempts);
    return { assetId, posterAssetId };
  }

  /** Asks for a thumbnail of an asset a saved draft names, once. Failure leaves no thumbnail. */
  ensureThumbnail(scope: BrandScope, assetId: string): void {
    if (this.requested.has(assetId) || this.thumbnails()[assetId] !== undefined) {
      return;
    }
    this.requested.add(assetId);
    firstValueFrom(this.media.downloadUrl(scope.tenantId, assetId, 'THUMBNAIL')).then(
      (url) => this.remember(assetId, url),
      () => undefined,
    );
  }

  private async send(
    scope: BrandScope,
    file: File,
    profile: 'CREATIVE' | undefined,
  ): Promise<string> {
    const uploaded = await firstValueFrom(
      this.media.upload(scope.tenantId, 'BRAND', scope.brandId, 'PUBLIC', file, profile),
    );
    return uploaded.assetId;
  }

  private async untilAvailable(
    scope: BrandScope,
    assetId: string,
    kind: 'IMAGE' | 'VIDEO',
    attempts: number,
  ): Promise<void> {
    for (let attempt = 0; attempt < attempts; attempt++) {
      const view = await firstValueFrom(this.media.asset(scope.tenantId, assetId));
      if (view.status === 'AVAILABLE') {
        return;
      }
      if (view.status === 'REJECTED') {
        throw new MediaRefusedError(kind);
      }
      await this.wait(this.pollMilliseconds);
    }
    throw new MediaNotReadyError();
  }

  protected wait(milliseconds: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, milliseconds));
  }

  private remember(assetId: string, url: string): void {
    this.thumbnails.update((current) => ({ ...current, [assetId]: url }));
  }
}
