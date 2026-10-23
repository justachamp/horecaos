import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { LatLng } from '../../../shared/ui/map/map-provider';
import { addressEditorFakes } from '../../customers/address-editor-fakes.testing';
import { AddressPinEditor } from './address-pin-editor';

const at = (latitude: number, longitude: number): LatLng => ({ latitude, longitude });

/**
 * ADR 0177: the editor over a saved address the operator cannot read. It places or moves a pin and
 * says where the pin is; it has no field for text, and it does not let the pin be taken away.
 */
describe('AddressPinEditor', () => {
  let fixture: ComponentFixture<AddressPinEditor>;
  let fakes: ReturnType<typeof addressEditorFakes>;

  const byTestId = (id: string): HTMLElement | null =>
    (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${id}"]`);

  async function mount(point: LatLng | null): Promise<(LatLng | null)[]> {
    const reported: (LatLng | null)[] = [];
    fakes = addressEditorFakes();
    await TestBed.configureTestingModule({
      imports: [AddressPinEditor],
      providers: [...fakes.providers],
    }).compileComponents();
    fixture = TestBed.createComponent(AddressPinEditor);
    fixture.componentRef.setInput('point', point);
    fixture.componentRef.setInput('label', 'Home');
    fixture.componentInstance.pointChange.subscribe((next) => reported.push(next));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return reported;
  }

  beforeEach(() => TestBed.resetTestingModule());

  it('starts from the address’s saved point and reports it', async () => {
    const reported = await mount(at(41.31, 69.28));

    expect(reported.at(-1)).toEqual(at(41.31, 69.28));
    expect(fakes.map.map.livePins).toHaveLength(1);
    expect(fakes.map.map.livePins[0].position).toEqual(at(41.31, 69.28));
    expect(byTestId('address-pin-editor-note')).not.toBeNull();
  });

  it('starts with no pin for an address that has none, and reports a typed point', async () => {
    const reported = await mount(null);
    expect(reported.at(-1)).toBeNull();

    const latitude = byTestId('q-map-pin-latitude') as HTMLInputElement;
    const longitude = byTestId('q-map-pin-longitude') as HTMLInputElement;
    latitude.value = '41.3';
    latitude.dispatchEvent(new Event('input'));
    longitude.value = '69.2';
    longitude.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(reported.at(-1)).toEqual(at(41.3, 69.2));
  });

  it('offers no way to take the pin away, because the pin is what makes the address deliverable', async () => {
    await mount(at(41.31, 69.28));

    expect(byTestId('q-map-pin-remove')).toBeNull();
  });

  it('has no text field for the address: its text is not this screen’s', async () => {
    await mount(at(41.31, 69.28));

    for (const id of [
      'q-address-street',
      'q-address-flat',
      'q-address-landmark',
      'address-editor-label',
    ]) {
      expect(byTestId(id), id).toBeNull();
    }
  });

  it('starts over from the new address’s point when the address changes', async () => {
    const reported = await mount(at(41.31, 69.28));

    fixture.componentRef.setInput('point', at(41.2, 69.1));
    fixture.detectChanges();
    await fixture.whenStable();

    expect(reported.at(-1)).toEqual(at(41.2, 69.1));
  });
});
