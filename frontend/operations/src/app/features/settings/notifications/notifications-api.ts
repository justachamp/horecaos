import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { command } from '../../../core/api/idempotency';
import { LocationScope } from '../../../core/api/operations-paths';
import { settingsPaths } from '../../../core/api/settings-paths';

/** Mirrors uz.horecaos.platform.notifications.web.NotificationTemplateController.TemplateResponse. */
export interface TemplateResponse {
  readonly id: string;
  readonly brandId: string | null;
  readonly templateKey: string;
  readonly notificationClass: string;
  readonly channel: string;
  readonly consentPurpose: string | null;
  readonly status: string;
  readonly activeVersion: number | null;
  readonly version: number;
}

export interface CreateTemplateRequest {
  readonly templateKey: string;
  readonly notificationClass: string;
  readonly channel: string;
  readonly consentPurpose?: string;
}

export interface Wording {
  readonly subject?: string;
  readonly body: string;
}

export interface AddVersionRequest {
  readonly wordings: Readonly<Record<string, Wording>>;
  readonly variablesSchema: Readonly<Record<string, string>>;
}

/**
 * 10.9 Notifications, Tab 1 (`NotificationTemplateController`, ADR 0020).
 * Already on the operations surface (`/api/v1/tenants/**`, pre-ADR-0031) —
 * no cross-surface call here, unlike most of this wave's other screens.
 */
@Injectable({ providedIn: 'root' })
export class NotificationsApi {
  private readonly api = inject(ApiClient);

  async list(scope: LocationScope): Promise<readonly TemplateResponse[]> {
    const result = await firstValueFrom(
      this.api.get<readonly TemplateResponse[]>(settingsPaths.notificationTemplates(scope)),
    );
    return result.value ?? [];
  }

  async create(scope: LocationScope, request: CreateTemplateRequest): Promise<string> {
    const response = await firstValueFrom(
      this.api.post<CreateTemplateRequest, { id: string }>(
        settingsPaths.notificationTemplates(scope),
        command(request),
      ),
    );
    return response.id;
  }

  async addVersion(
    scope: LocationScope,
    templateId: string,
    request: AddVersionRequest,
  ): Promise<number> {
    const response = await firstValueFrom(
      this.api.post<AddVersionRequest, { templateId: string; versionNumber: number }>(
        settingsPaths.notificationTemplateVersions(scope, templateId),
        command(request),
      ),
    );
    return response.versionNumber;
  }

  async activate(scope: LocationScope, templateId: string, versionNumber: number): Promise<void> {
    await firstValueFrom(
      this.api.post<null, void>(
        settingsPaths.notificationTemplateActivate(scope, templateId, versionNumber),
        command(null),
      ),
    );
  }
}
