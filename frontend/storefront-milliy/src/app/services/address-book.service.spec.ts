import { TestBed } from '@angular/core/testing';

import { AddressBookService } from './address-book.service';
import { CustomerApi, type CustomerAddress } from '../core/api/customer-api';

class FakeCustomerApi {
  addresses = vi.fn();
  addAddress = vi.fn();
  replaceAddress = vi.fn();
  removeAddress = vi.fn();
}

function address(id: string, version: number): CustomerAddress {
  return {
    addressId: id,
    label: 'Home',
    fields: { line1: 'Amir Temur 1' },
    deliveryInstructions: null,
    latitude: 41.3,
    longitude: 69.2,
    coordinateSource: 'CUSTOMER_PIN',
    version,
  };
}

function setUp(): { service: AddressBookService; api: FakeCustomerApi } {
  const api = new FakeCustomerApi();
  TestBed.configureTestingModule({ providers: [{ provide: CustomerApi, useValue: api }] });
  return { service: TestBed.inject(AddressBookService), api };
}

describe('AddressBookService.versionOf (via replace/remove)', () => {
  it('throws for an address that was never read, rather than guessing a version', async () => {
    const { service } = setUp();

    await expect(
      service.replace('never-read-id', { label: null, line1: null, latitude: null, longitude: null }),
    ).rejects.toThrow('Address never-read-id was not read before it was written to.');
  });

  it('throws on remove too, for the same reason', async () => {
    const { service } = setUp();

    await expect(service.remove('never-read-id')).rejects.toThrow(
      'Address never-read-id was not read before it was written to.',
    );
  });

  it('does not call the API at all when the version is unknown -- it refuses before the request', async () => {
    const { service, api } = setUp();

    await service
      .replace('never-read-id', { label: null, line1: null, latitude: null, longitude: null })
      .catch(() => {});

    expect(api.replaceAddress).not.toHaveBeenCalled();
  });

  it('succeeds once the address has been read via list(), using the version from that read', async () => {
    const { service, api } = setUp();
    api.addresses.mockResolvedValue([address('a1', 3)]);
    await service.list();
    api.replaceAddress.mockResolvedValue(address('a1', 4));

    await service.replace('a1', { label: 'Work', line1: 'x', latitude: null, longitude: null });

    expect(api.replaceAddress).toHaveBeenCalledWith(
      expect.objectContaining({ addressId: 'a1', expectedVersion: 3 }),
    );
  });

  it('after a write, the new version is known -- a second write in the same session presents it, not the stale one', async () => {
    const { service, api } = setUp();
    api.addresses.mockResolvedValue([address('a1', 3)]);
    await service.list();
    api.replaceAddress.mockResolvedValue(address('a1', 4));
    await service.replace('a1', { label: 'Work', line1: 'x', latitude: null, longitude: null });

    api.removeAddress.mockResolvedValue(undefined);
    await service.remove('a1');

    expect(api.removeAddress).toHaveBeenCalledWith(
      expect.objectContaining({ addressId: 'a1', expectedVersion: 4 }),
    );
  });

  it('a newly added address is immediately known, without a separate list() read', async () => {
    const { service, api } = setUp();
    api.addAddress.mockResolvedValue(address('new-1', 1));

    await service.add({ label: 'Home', line1: 'x', latitude: null, longitude: null });

    api.replaceAddress.mockResolvedValue(address('new-1', 2));
    await service.replace('new-1', { label: 'Home 2', line1: 'y', latitude: null, longitude: null });

    expect(api.replaceAddress).toHaveBeenCalledWith(
      expect.objectContaining({ addressId: 'new-1', expectedVersion: 1 }),
    );
  });

  it('a removed address is forgotten -- writing to it again throws again', async () => {
    const { service, api } = setUp();
    api.addresses.mockResolvedValue([address('a1', 3)]);
    await service.list();
    api.removeAddress.mockResolvedValue(undefined);
    await service.remove('a1');

    await expect(
      service.replace('a1', { label: null, line1: null, latitude: null, longitude: null }),
    ).rejects.toThrow('Address a1 was not read before it was written to.');
  });
});
