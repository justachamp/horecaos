import { Injectable, inject, signal } from '@angular/core';

import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG } from '../core/config/app-config';
import { newIdempotencyKey } from '../core/api/idempotency';
import { isNotFound } from '../core/api/problem-details';

/**
 * The customer's own profile picture.
 *
 * Three steps, because the bytes never pass through the platform: ask for a key
 * and a constrained URL, PUT the file straight at the object store, then tell
 * the platform to verify and attach it. The middle step is the only request in
 * this application that does not go to the HorecaOS API, and it deliberately
 * carries no bearer token -- `PLATFORM_API_REQUEST` is not set on it, so
 * `bearerInterceptor` leaves it alone. Handing a platform credential to an
 * object store because it happened to be in the URL bar is exactly what that
 * gate exists to prevent.
 *
 * The asset is PRIVATE. It is fetched through a short-lived signed URL the
 * platform mints for its owner, and never through the anonymous storefront
 * media path that menu pictures use.
 */
@Injectable({ providedIn: 'root' })
export class AvatarService {
  private readonly api = inject(ApiClient);
  private readonly config = inject(APP_CONFIG);

  /** A signed URL for this customer's picture, or null when they have none. */
  readonly url = signal<string | null>(null);

  private get path(): string {
    return `/storefront/tenants/${this.config.tenantId}/brands/${this.config.brandId}/me/avatar`;
  }

  /** Reads the current picture. A customer without one is not an error. */
  async load(): Promise<void> {
    try {
      const response = await this.api.get<{ url: string | null }>(this.path);
      this.url.set(response?.url ?? null);
    } catch (failure) {
      if (!isNotFound(failure)) {
        throw failure;
      }
      this.url.set(null);
    }
  }

  /**
   * Uploads a file and attaches it.
   *
   * The size and type are declared before the URL is signed, so the platform
   * refuses an oversized or unsupported file before any bytes move rather than
   * after. What the client claims is a constraint on the signature, never
   * evidence -- the platform reads the object's own metadata at attach time.
   */
  async replace(file: File): Promise<void> {
    const ticket = await this.api.mutate<UploadTicket>('POST', `${this.path}/upload-requests`, {
      body: {
        contentType: file.type,
        sizeBytes: file.size,
        originalFilename: file.name,
      },
      idempotencyKey: newIdempotencyKey(),
    });

    // Straight to the object store. Not through ApiClient: this is not the
    // platform, and nothing about it should look like it is.
    const uploaded = await fetch(ticket.uploadUrl, {
      method: 'PUT',
      headers: { ...ticket.requiredHeaders, 'Content-Type': file.type },
      body: file,
    });
    if (!uploaded.ok) {
      throw new Error(`The image could not be uploaded (${uploaded.status}).`);
    }

    const attached = await this.api.mutate<{ url: string | null }>('PUT', this.path, {
      body: { assetId: ticket.assetId },
      idempotencyKey: newIdempotencyKey(),
    });
    this.url.set(attached?.url ?? null);
  }

  async remove(): Promise<void> {
    await this.api.mutate<void>('DELETE', this.path, { idempotencyKey: newIdempotencyKey() });
    this.url.set(null);
  }

  /** Dropped at sign-out: the next customer's face is not this one's. */
  forget(): void {
    this.url.set(null);
  }
}

interface UploadTicket {
  readonly assetId: string;
  readonly uploadUrl: string;
  readonly requiredHeaders: Record<string, string>;
  readonly expiresAt: string;
}
