import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { BrandScope, catalogPaths } from '../../core/api/catalog-paths';
import { command } from '../../core/api/idempotency';
import {
  ChannelMediaImage,
  ChannelMediaOverride,
  ChannelPreviewPageBody,
  PreviewTarget,
} from './channel-preview-domain';

/** Which branch to preview: a channel that sells at exactly one needs neither. */
export interface PreviewTargetChoice {
  readonly locationId?: string;
  readonly bindingId?: string;
}

/**
 * `GET .../catalogs/{id}/channels/{id}/preview` and the channel media overrides —
 * `CatalogPublicationController` and `CatalogAuthoringController` (ADR 0138).
 *
 * The preview is a read, and a deliberately unreferenceable one: every call recomputes
 * from the current draft, so there is no id to hold and nothing to cache. A page that
 * wants the whole menu follows `nextCursor` until it is null.
 */
@Injectable({ providedIn: 'root' })
export class ChannelPreviewApi {
  private readonly api = inject(ApiClient);

  /** One page of the preview. Pass `cursor` from the previous page's `nextCursor` to continue. */
  previewPage(
    scope: BrandScope,
    catalogId: string,
    channelId: string,
    target: PreviewTargetChoice,
    locale: string,
    cursor: string | null = null,
    limit = 100,
  ): Observable<ChannelPreviewPageBody> {
    return this.api
      .get<ChannelPreviewPageBody>(catalogPaths.channelPreview(scope, catalogId, channelId), {
        params: {
          locationId: target.locationId,
          bindingId: target.bindingId,
          locale,
          cursor: cursor ?? undefined,
          limit,
        },
      })
      .pipe(map((result) => result.value));
  }

  targets(scope: BrandScope, channelId: string): Observable<readonly PreviewTarget[]> {
    return this.api
      .get<readonly PreviewTarget[]>(catalogPaths.previewTargets(scope, channelId))
      .pipe(map((result) => result.value));
  }

  /** Every image override a channel carries; with `entity`, only that item's. */
  mediaOverrides(
    scope: BrandScope,
    channelId: string,
    entity?: { readonly entityType: string; readonly entityId: string },
  ): Observable<readonly ChannelMediaOverride[]> {
    return this.api
      .get<{ images: readonly ChannelMediaOverride[] }>(
        catalogPaths.channelMediaOverrides(scope, channelId),
        { params: entity ? { entityType: entity.entityType, entityId: entity.entityId } : {} },
      )
      .pipe(map((result) => result.value.images));
  }

  /**
   * Replaces the whole set of images a channel shows for one item — the whole set every time,
   * matching the photo editor's own whole-set save. An empty list removes the override and the
   * item goes back to its own images on that channel.
   */
  replaceMediaOverride(
    scope: BrandScope,
    channelId: string,
    entityType: string,
    entityId: string,
    images: readonly ChannelMediaImage[],
  ): Observable<readonly ChannelMediaOverride[]> {
    return this.api
      .put<{ images: readonly ChannelMediaImage[] }, { images: readonly ChannelMediaOverride[] }>(
        catalogPaths.channelMediaOverride(scope, channelId, entityType, entityId),
        command({ images }),
      )
      .pipe(map((result) => result.images));
  }
}
