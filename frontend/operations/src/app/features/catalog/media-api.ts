import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map, switchMap } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
import { mediaPaths } from '../../core/api/operations-paths';

export type MediaOwnerScope = 'TENANT' | 'BRAND' | 'LOCATION';
export type MediaVisibility = 'PUBLIC' | 'PRIVATE';
/** `UPLOADED` is what finalize answers: verification is asynchronous, so `AVAILABLE` or `REJECTED` follows. */
export type MediaAssetStatus = 'PENDING' | 'PENDING_UPLOAD' | 'UPLOADED' | 'AVAILABLE' | 'REJECTED';

/**
 * `CREATIVE` asks for the extra `HERO` rendition (1600 px) a home-screen banner needs (ADR 0157); it
 * is set only by the content editor's upload, so a product photo is never rendered a fourth time.
 */
export type MediaRenditionProfile = 'CREATIVE';

export interface UploadRequestResult {
  readonly assetId: string;
  readonly uploadUrl: string;
  readonly requiredHeaders: Readonly<Record<string, string>>;
  readonly expiresAt: string;
}

export interface MediaAssetView {
  readonly assetId: string;
  readonly status: MediaAssetStatus;
}

/** `DerivativeVariant` — the fixed set of renditions the platform renders (ADR 0010). */
export type MediaDerivativeVariant = 'THUMBNAIL' | 'CARD' | 'DETAIL';

export interface DownloadUrlResponse {
  readonly url: string;
}

/**
 * `MediaController` (ADR 0010) — presigned-upload flow, `operations` surface,
 * tenant-scoped. catalog.md §4.9's own doc names the contract: request a URL,
 * `PUT` the bytes directly to object storage (never through this platform's
 * API — the server never sees the file), then finalize so the server re-reads
 * the object store rather than trusting a client-asserted content type or size.
 *
 * Attaching a finalized asset to a product/variant/category is a *separate*
 * call, `CatalogApi.attachMedia` — `catalog.media_relations` is catalog data,
 * not a media concern (see that method's own doc).
 */
@Injectable({ providedIn: 'root' })
export class MediaApi {
  private readonly api = inject(ApiClient);
  private readonly http = inject(HttpClient);

  requestUpload(
    tenantId: string,
    ownerScope: MediaOwnerScope,
    ownerId: string,
    visibility: MediaVisibility,
    file: File,
    renditionProfile?: MediaRenditionProfile,
  ): Observable<UploadRequestResult> {
    return this.api.post<
      {
        ownerScope: MediaOwnerScope;
        ownerId: string;
        visibility: MediaVisibility;
        contentType: string;
        sizeBytes: number;
        filename: string;
        renditionProfile?: MediaRenditionProfile;
      },
      UploadRequestResult
    >(
      mediaPaths.uploadRequests(tenantId),
      command({
        ownerScope,
        ownerId,
        visibility,
        contentType: file.type || 'application/octet-stream',
        sizeBytes: file.size,
        filename: file.name,
        ...(renditionProfile ? { renditionProfile } : {}),
      }),
    );
  }

  /** Uploads directly to object storage — not a platform call, so `ApiClient`'s conventions do not apply here. */
  private putBytes(result: UploadRequestResult, file: File): Observable<unknown> {
    return this.http.put(result.uploadUrl, file, { headers: result.requiredHeaders });
  }

  finalize(tenantId: string, assetId: string): Observable<MediaAssetView> {
    return this.api.post<undefined, MediaAssetView>(
      mediaPaths.finalize(tenantId, assetId),
      command(undefined),
    );
  }

  /** The whole flow in one call: request → upload the bytes → finalize. Resolves once the server has re-checked the object. */
  upload(
    tenantId: string,
    ownerScope: MediaOwnerScope,
    ownerId: string,
    visibility: MediaVisibility,
    file: File,
    renditionProfile?: MediaRenditionProfile,
  ): Observable<MediaAssetView> {
    return this.requestUpload(
      tenantId,
      ownerScope,
      ownerId,
      visibility,
      file,
      renditionProfile,
    ).pipe(
      switchMap((requested) => this.putBytes(requested, file).pipe(map(() => requested))),
      switchMap((requested) => this.finalize(tenantId, requested.assetId)),
    );
  }

  asset(tenantId: string, assetId: string): Observable<MediaAssetView> {
    return this.api
      .get<MediaAssetView>(mediaPaths.asset(tenantId, assetId))
      .pipe(map((result) => result.value));
  }

  /**
   * A short-lived signed URL, for the original or for one rendition.
   *
   * `MediaAssetService`'s own trap: derivatives were rendered, stored, and
   * never served — `variant` omitted asked for the original regardless of how
   * many kilobytes it was, because there was no way to ask for anything
   * smaller. Pass `'THUMBNAIL'` for a grid, `'CARD'`/`'DETAIL'` for a closer
   * view. A variant that has not rendered yet answers the same 404 as an
   * asset that does not exist — see the endpoint's own doc.
   */
  downloadUrl(
    tenantId: string,
    assetId: string,
    variant?: MediaDerivativeVariant,
  ): Observable<string> {
    return this.api
      .get<DownloadUrlResponse>(mediaPaths.downloadUrl(tenantId, assetId), {
        params: variant ? { variant } : {},
      })
      .pipe(map((result) => result.value.url));
  }
}
