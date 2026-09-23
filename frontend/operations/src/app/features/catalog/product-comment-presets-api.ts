import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { BrandScope } from '../../core/api/catalog-paths';
import { command } from '../../core/api/idempotency';

/**
 * `ProductCommentPresetController` (row 2.1b) — which of the tenant's coded
 * kitchen-instruction presets one product offers on a line.
 *
 * <p>Its own file, and its own path helper, rather than folding into {@code
 * catalog-api.ts}/{@code catalog-paths.ts}: {@code ProductCommentPresetController}
 * is mapped at {@code .../tenants/{tenantId}/brands/{brandId}/products/{productId}/comment-presets}
 * — no {@code /catalog} segment, unlike every {@code catalogPaths} entry (confirmed
 * by reading the controller's own {@code @RequestMapping} directly, the same
 * "grep before assuming" discipline {@code catalog-paths.ts}'s own doc comment
 * asks for). Attaching a preset chooses among the tenant-wide vocabulary
 * {@link CommentPresetsApi} already lists on the settings screen — this file
 * only adds the product-scoped half.
 */
const CONTROL_PLANE = '/api/v1/control-plane';

function path(scope: BrandScope, productId: string): string {
  return `${CONTROL_PLANE}/tenants/${encodeURIComponent(scope.tenantId)}/brands/${encodeURIComponent(
    scope.brandId,
  )}/products/${encodeURIComponent(productId)}/comment-presets`;
}

/** Mirrors `ProductCommentPresetController.ProductPresetResponse`. */
export interface ProductPresetResponse {
  readonly presetId: string;
  readonly code: string;
  readonly labelRu: string;
  readonly labelUz: string;
  readonly labelEn: string;
  readonly posModifierCode: string | null;
  readonly sortOrder: number;
  /** `ACTIVE` or `ARCHIVED` — the preset's own status, not this attachment's (there is no separate one). */
  readonly status: string;
}

/** Mirrors `ProductCommentPresetController.AttachPresetRequest`. `sortOrder` is a required number — the server binds it as a primitive `int`, so an omitted field is refused as `MALFORMED_BODY` rather than defaulting to 0. */
export interface AttachPresetRequest {
  readonly presetId: string;
  readonly sortOrder: number;
}

/** Which presets one product offers on a line (row 2.1b) — the product editor's own picker. */
@Injectable({ providedIn: 'root' })
export class ProductCommentPresetsApi {
  private readonly api = inject(ApiClient);

  /** Every preset attached to this product, unfiltered. */
  list(scope: BrandScope, productId: string): Observable<readonly ProductPresetResponse[]> {
    return this.api
      .get<readonly ProductPresetResponse[]>(path(scope, productId))
      .pipe(map((result) => result.value ?? []));
  }

  /** Attaches a preset, or re-sorts it if already attached — the same call. Refused (404) if the preset or the product does not exist. */
  attach(
    scope: BrandScope,
    productId: string,
    request: AttachPresetRequest,
  ): Observable<ProductPresetResponse> {
    return this.api.post<AttachPresetRequest, ProductPresetResponse>(
      path(scope, productId),
      command(request),
    );
  }

  /** Idempotent — detaching a pair that is already gone still resolves. */
  detach(scope: BrandScope, productId: string, presetId: string): Observable<void> {
    return this.api
      .send<null, void>(
        'DELETE',
        `${path(scope, productId)}/${encodeURIComponent(presetId)}`,
        command(null),
      )
      .pipe(map(() => undefined));
  }
}
