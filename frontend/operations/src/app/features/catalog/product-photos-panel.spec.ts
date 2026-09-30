import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { MediaUploader } from '../../shared/ui/media-uploader';
import { ChannelView } from '../settings/sales-channels/sales-channels-api';
import { MediaRelation } from './catalog-domain';
import { ProductPhotosPanel } from './product-photos-panel';

const ALL = 'ALL';
const CHANNELS = [
  {
    id: 'c1',
    code: 'STOREFRONT',
    displayName: 'Storefront',
    systemType: 'STOREFRONT',
    status: 'ACTIVE',
  },
] as unknown as readonly ChannelView[];

const photo = (id: string, role: string, sortOrder: number, channelCode = ALL): MediaRelation => ({
  mediaAssetId: id,
  role,
  sortOrder,
  channelCode,
});

interface Inputs {
  photoChannels: readonly ChannelView[];
  selectedPhotoChannel: string;
  allChannelsCode: string;
  selectedChannelPhotos: readonly MediaRelation[];
  photoUrls: Readonly<Record<string, string>>;
  savingField: string | null;
  uploadingPhoto: boolean;
}

function render(overrides: Partial<Inputs> = {}) {
  TestBed.resetTestingModule();
  TestBed.inject(I18n).setLocale('en');
  const inputs: Inputs = {
    photoChannels: CHANNELS,
    selectedPhotoChannel: ALL,
    allChannelsCode: ALL,
    selectedChannelPhotos: [
      photo('a', 'PRIMARY', 0),
      photo('b', 'GALLERY', 1),
      photo('c', 'GALLERY', 2),
    ],
    photoUrls: { a: 'https://img/a.jpg' },
    savingField: null,
    uploadingPhoto: false,
    ...overrides,
  };
  const fixture = TestBed.createComponent(ProductPhotosPanel);
  for (const [name, value] of Object.entries(inputs)) {
    fixture.componentRef.setInput(name, value);
  }
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const tiles = () => [...host.querySelectorAll<HTMLElement>('[data-testid="editor-photo-tile"]')];
  const button = (tile: HTMLElement, id: string) =>
    tile.querySelector<HTMLButtonElement>(`[data-testid="${id}"]`)!;
  return { fixture, host, tiles, button };
}

describe('ProductPhotosPanel', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('offers the universal gallery and each active channel', () => {
    const { host } = render();

    const options = [...host.querySelectorAll('option')].map((option) =>
      option.textContent?.trim(),
    );
    expect(options).toEqual(['Universal (every channel)', 'Storefront']);
  });

  it('draws a tile per photo: the image when its URL is known, a labelled placeholder when not', () => {
    const { tiles } = render();

    expect(tiles()).toHaveLength(3);
    expect(tiles()[0].querySelector('img')?.getAttribute('src')).toBe('https://img/a.jpg');
    expect(tiles()[1].querySelector('img')).toBeNull();
    expect(tiles()[1].querySelector('.editor__photo-placeholder')?.textContent?.trim()).toBe(
      'Gallery',
    );
    expect(tiles()[0].querySelector('.editor__photo-meta span')?.textContent?.trim()).toBe(
      'Primary',
    );
  });

  it('shows the channel a per-channel photo is pinned to, and not for the universal gallery', () => {
    const { tiles } = render({
      selectedPhotoChannel: 'STOREFRONT',
      selectedChannelPhotos: [photo('a', 'PRIMARY', 0, 'STOREFRONT')],
    });

    expect(tiles()[0].querySelector('.q-mono')?.textContent?.trim()).toBe('STOREFRONT');
    expect(render().tiles()[0].querySelector('.q-mono')).toBeNull();
  });

  it('says the gallery is empty, worded for the universal gallery and for a channel', () => {
    const universal = render({ selectedChannelPhotos: [] });
    expect(universal.host.querySelector('.editor__photo-grid')).toBeNull();
    expect(universal.host.textContent).toContain('No photos yet');

    const channel = render({ selectedChannelPhotos: [], selectedPhotoChannel: 'STOREFRONT' });
    expect(channel.host.textContent).not.toContain('No photos yet');
  });

  it('cannot move the first photo up or the last down, and holds a photo while it saves', () => {
    const { tiles, button } = render({ savingField: 'photo-reorder:b' });

    expect(button(tiles()[0], 'editor-photo-move-up').disabled).toBe(true);
    expect(button(tiles()[2], 'editor-photo-move-down').disabled).toBe(true);
    expect(button(tiles()[1], 'editor-photo-move-up').disabled).toBe(true);
    expect(button(tiles()[1], 'editor-photo-move-down').disabled).toBe(true);
    expect(button(tiles()[0], 'editor-photo-move-down').disabled).toBe(false);
  });

  it('holds Remove only for the photo being detached', () => {
    const { tiles, button } = render({ savingField: 'photo-detach:c' });

    expect(button(tiles()[2], 'editor-photo-detach').disabled).toBe(true);
    expect(button(tiles()[0], 'editor-photo-detach').disabled).toBe(false);
  });

  it('reports a move with its direction, a detach, and a channel change', () => {
    const { fixture, host, tiles, button } = render();
    const seen: string[] = [];
    const instance = fixture.componentInstance;
    instance.reorderRequested.subscribe(({ item, direction }) =>
      seen.push(`move:${item.mediaAssetId}:${direction}`),
    );
    instance.detachRequested.subscribe((item) => seen.push(`detach:${item.mediaAssetId}`));
    instance.channelChanged.subscribe((code) => seen.push(`channel:${code}`));

    button(tiles()[1], 'editor-photo-move-up').click();
    button(tiles()[1], 'editor-photo-move-down').click();
    button(tiles()[2], 'editor-photo-detach').click();
    const select = host.querySelector<HTMLSelectElement>(
      '[data-testid="editor-photo-channel-select"]',
    )!;
    select.value = 'STOREFRONT';
    select.dispatchEvent(new Event('change'));

    expect(seen).toEqual(['move:b:-1', 'move:b:1', 'detach:c', 'channel:STOREFRONT']);
  });

  it('passes an uploaded file, a cropped one and a rejection up, and tells the uploader it is busy', () => {
    const { fixture } = render({ uploadingPhoto: true });
    const seen: string[] = [];
    fixture.componentInstance.uploadRequested.subscribe((file) => seen.push(`file:${file.name}`));
    fixture.componentInstance.rejected.subscribe((reason) => seen.push(`rejected:${reason}`));
    const uploader = fixture.debugElement.query(By.directive(MediaUploader))
      .componentInstance as MediaUploader;

    uploader.selected.emit(new File(['x'], 'plain.jpg'));
    uploader.cropped.emit(new File(['x'], 'cropped.jpg'));
    uploader.rejected.emit('TOO_LARGE');

    expect(seen).toEqual(['file:plain.jpg', 'file:cropped.jpg', 'rejected:TOO_LARGE']);
    expect(uploader.uploading()).toBe(true);
  });
});
