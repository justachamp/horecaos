# Voice platform

A multi-tenant Restaurant Contact Center as a Service — inbound and outbound calls,
restaurant phone numbers, IVR, queues, agents, routing, recording, CDR and a custom agent
application — built on the Wazo Platform (Asterisk underneath) behind an anti-corruption
layer, so that HorecaOS stays the system of record for customers, restaurants, orders and
reservations and Wazo owns only communications infrastructure.

This is a separate project inside the HorecaOS monorepo, decided by the owner on
2026-10-03: it has its own stack, its own decision records and its own release cadence,
and it integrates with the HorecaOS platform through HorecaOS's public APIs only.

- Decisions: [docs/adr/README.md](docs/adr/README.md) — start with
  [ADR 0001](docs/adr/0001-multi-tenant-restaurant-contact-center-platform.md), the
  founding record (Proposed, 2026-10-02).
- Status: decision records only; no code yet.
- Relationship to HorecaOS: ADR 0001 §35 lists what crosses the boundary (the CRM APIs,
  HorecaOS ADR 0064's voice core, personal data at the boundary, numbering and recording,
  hosting).
