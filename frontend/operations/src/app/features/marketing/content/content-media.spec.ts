import { TestBed } from '@angular/core/testing';
import { Observable, of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { MediaApi, MediaAssetStatus, MediaAssetView } from '../../catalog/media-api';
import { ContentMedia, MediaNotReadyError, MediaRefusedError } from './content-media';

const SCOPE = { tenantId: 't1', brandId: 'b1' };

class FakeMediaApi {
  readonly uploads: {
    brandId: string;
    visibility: string;
    name: string;
    profile: string | undefined;
  }[] = [];
  readonly thumbnailAsks: { brandId: string; assetId: string; variant: string | undefined }[] = [];
  /** The statuses `asset()` answers, in order; the last repeats. */
  statuses: MediaAssetStatus[] = ['UPLOADED', 'AVAILABLE'];
  private looks = 0;
  private counter = 0;
  downloadFails = false;

  uploadForBrand(
    _tenantId: string,
    brandId: string,
    visibility: string,
    file: File,
    profile?: string,
  ): Observable<MediaAssetView> {
    this.uploads.push({ brandId, visibility, name: file.name, profile });
    this.counter += 1;
    return of({ assetId: `asset-${this.counter}`, status: 'UPLOADED' });
  }

  brandAsset(_tenantId: string, _brandId: string, assetId: string): Observable<MediaAssetView> {
    const status = this.statuses[Math.min(this.looks, this.statuses.length - 1)];
    this.looks += 1;
    return of({ assetId, status });
  }

  brandDownloadUrl(
    _tenantId: string,
    brandId: string,
    assetId: string,
    variant?: string,
  ): Observable<string> {
    this.thumbnailAsks.push({ brandId, assetId, variant });
    return this.downloadFails
      ? new Observable<string>((subscriber) => subscriber.error(new Error('404')))
      : of('https://cdn.example/thumb.jpg');
  }
}

class FastContentMedia extends ContentMedia {
  constructor() {
    super();
    this.pollMilliseconds = 0;
    this.imageAttempts = 3;
    this.videoAttempts = 3;
  }
}

describe("ContentMedia (ADR 0157: a creative is public, the brand's and, for an image, CREATIVE)", () => {
  let api: FakeMediaApi;
  let media: ContentMedia;

  beforeEach(() => {
    api = new FakeMediaApi();
    TestBed.configureTestingModule({
      providers: [
        { provide: MediaApi, useValue: api },
        { provide: ContentMedia, useClass: FastContentMedia },
      ],
    });
    media = TestBed.inject(ContentMedia);
    URL.createObjectURL = () => 'blob:local';
  });

  it('uploads an image PUBLIC, through the brand’s own media routes, with the CREATIVE rendition profile', async () => {
    const id = await media.uploadImage(
      SCOPE,
      new File(['x'], 'banner.jpg', { type: 'image/jpeg' }),
    );

    expect(id).toBe('asset-1');
    expect(api.uploads).toEqual([
      {
        brandId: 'b1',
        visibility: 'PUBLIC',
        name: 'banner.jpg',
        profile: 'CREATIVE',
      },
    ]);
  });

  it('waits for the platform to verify the file and only then hands the asset back', async () => {
    api.statuses = ['UPLOADED', 'UPLOADED', 'AVAILABLE'];

    const id = await media.uploadImage(SCOPE, new File(['x'], 'a.jpg', { type: 'image/jpeg' }));

    expect(id).toBe('asset-1');
  });

  it('shows the file the operator chose as the thumbnail straight away', async () => {
    await media.uploadImage(SCOPE, new File(['x'], 'a.jpg', { type: 'image/jpeg' }));

    expect(media.thumbnails()['asset-1']).toBe('blob:local');
  });

  it('reports a refusal as a refusal, not as a timeout', async () => {
    api.statuses = ['UPLOADED', 'REJECTED'];

    await expect(
      media.uploadImage(SCOPE, new File(['x'], 'a.jpg', { type: 'image/jpeg' })),
    ).rejects.toBeInstanceOf(MediaRefusedError);
  });

  it('gives up with a not-ready error when verification does not finish in time', async () => {
    api.statuses = ['UPLOADED'];

    await expect(
      media.uploadImage(SCOPE, new File(['x'], 'a.jpg', { type: 'image/jpeg' })),
    ).rejects.toBeInstanceOf(MediaNotReadyError);
  });

  it('uploads a video with its poster: the poster as a CREATIVE image, the video with no profile', async () => {
    const result = await media.uploadVideo(
      SCOPE,
      new File(['v'], 'clip.mp4', { type: 'video/mp4' }),
      new File(['p'], 'clip.jpg', { type: 'image/jpeg' }),
    );

    expect(result).toEqual({ assetId: 'asset-2', posterAssetId: 'asset-1' });
    expect(api.uploads.map((u) => [u.name, u.profile, u.visibility])).toEqual([
      ['clip.jpg', 'CREATIVE', 'PUBLIC'],
      ['clip.mp4', undefined, 'PUBLIC'],
    ]);
  });

  it('names a refused video as a video', async () => {
    // The poster is verified first (AVAILABLE), then the video is refused.
    api.statuses = ['AVAILABLE', 'REJECTED'];

    const failure = await media
      .uploadVideo(
        SCOPE,
        new File(['v'], 'clip.mp4', { type: 'video/mp4' }),
        new File(['p'], 'clip.jpg', { type: 'image/jpeg' }),
      )
      .catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(MediaRefusedError);
    expect((failure as MediaRefusedError).kind).toBe('VIDEO');
  });

  it('fetches a rendition thumbnail for an asset a saved draft names, once', async () => {
    media.ensureThumbnail(SCOPE, 'saved-1');
    media.ensureThumbnail(SCOPE, 'saved-1');
    // The thumbnail is remembered a runtime-defined number of microtask turns after the
    // download-URL observable emits; wait for it rather than counting turns.
    await vi.waitFor(() =>
      expect(media.thumbnails()['saved-1']).toBe('https://cdn.example/thumb.jpg'),
    );

    expect(api.thumbnailAsks).toEqual([
      { brandId: 'b1', assetId: 'saved-1', variant: 'THUMBNAIL' },
    ]);
  });

  it('leaves no thumbnail, and no error, when the rendition has not rendered', async () => {
    api.downloadFails = true;

    media.ensureThumbnail(SCOPE, 'saved-2');
    // An absence check: a failed fetch leaves nothing to wait for, so this only gives the
    // rejection handler its turns before looking for a thumbnail that must not be there.
    await Promise.resolve();
    await Promise.resolve();

    expect(media.thumbnails()['saved-2']).toBeUndefined();
  });
});
