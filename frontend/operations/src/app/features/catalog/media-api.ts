import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map, switchMap } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
import { mediaPaths } from '../../core/api/operations-paths';

export type MediaOwnerScope = 'TENANT' | 'BRAND' | 'LOCATION';
export type MediaVisibility = 'PUBLIC' | 'PRIVATE';
export type MediaAssetStatus = 'PENDING' | 'AVAILABLE' | 'REJECTED';

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
  ): Observable<UploadRequestResult> {
    return this.api.post<
      {
        ownerScope: MediaOwnerScope;
        ownerId: string;
        visibility: MediaVisibility;
        contentType: string;
        sizeBytes: number;
        filename: string;
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
  ): Observable<MediaAssetView> {
    return this.requestUpload(tenantId, ownerScope, ownerId, visibility, file).pipe(
      switchMap((requested) => this.putBytes(requested, file).pipe(map(() => requested))),
      switchMap((requested) => this.finalize(tenantId, requested.assetId)),
    );
  }

  asset(tenantId: string, assetId: string): Observable<MediaAssetView> {
    return this.api
      .get<MediaAssetView>(mediaPaths.asset(tenantId, assetId))
      .pipe(map((result) => result.value));
  }
}
