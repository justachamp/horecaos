import { Injectable, inject } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { command } from '../../../core/api/idempotency';

/**
 * `CommentPresetController` — control-plane, not the operations prefix, the
 * same pre-existing mismatch `catalog-paths.ts` documents at length for
 * every other catalog authoring endpoint this console calls. Kept as its
 * own small constant here rather than reusing `catalogPaths` because these
 * endpoints are `TENANT`-scoped (no `brandId`), a different shape from
 * `catalogPaths`' own `BrandScope`.
 */
const CONTROL_PLANE = '/api/v1/control-plane';

/** Mirrors `CommentPresetController.PresetResponse` (row 2.1b). */
export interface PresetResponse {
  readonly presetId: string;
  readonly code: string;
  readonly labelRu: string;
  readonly labelUz: string;
  readonly labelEn: string;
  readonly posModifierCode: string | null;
  readonly sortOrder: number;
  /** `ACTIVE` or `ARCHIVED`. */
  readonly status: string;
  readonly version: number;
}

export interface NewPreset {
  readonly code: string;
  readonly labelRu: string;
  readonly labelUz: string;
  readonly labelEn: string;
  readonly posModifierCode?: string | null;
  readonly sortOrder?: number;
}

/** Whole-record edit: labels, POS mapping, sort order and status together, with an expected version. */
export interface PresetEdit {
  readonly labelRu: string;
  readonly labelUz: string;
  readonly labelEn: string;
  readonly posModifierCode?: string | null;
  readonly sortOrder: number;
  readonly status: string;
  readonly expectedVersion: number;
}

/** The tenant-wide coded kitchen-instruction vocabulary (row 2.1b) — the settings screen's own API. */
@Injectable({ providedIn: 'root' })
export class CommentPresetsApi {
  private readonly api = inject(ApiClient);

  async list(tenantId: string): Promise<readonly PresetResponse[]> {
    const result = await firstValueFrom(
      this.api.get<readonly PresetResponse[]>(this.path(tenantId)),
    );
    return result.value ?? [];
  }

  /** Refused (409) when the code is already registered for this tenant. */
  create(tenantId: string, body: NewPreset): Observable<PresetResponse> {
    return this.api.post<NewPreset, PresetResponse>(this.path(tenantId), command(body));
  }

  /** Archiving is `status: 'ARCHIVED'` here, not a delete — a preset already on a product must stay resolvable. */
  update(tenantId: string, presetId: string, body: PresetEdit): Observable<PresetResponse> {
    return this.api.put<PresetEdit, PresetResponse>(
      `${this.path(tenantId)}/${encodeURIComponent(presetId)}`,
      command(body),
    );
  }

  private path(tenantId: string): string {
    return `${CONTROL_PLANE}/tenants/${encodeURIComponent(tenantId)}/comment-presets`;
  }
}
