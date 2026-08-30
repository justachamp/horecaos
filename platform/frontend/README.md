# Qoida frontend

Two kinds of thing live here, and the distinction matters.

```text
control-plane/   Qoida staff console.      Angular.  Standalone repository.
operations/      Restaurant staff console. Angular.  Standalone repository.
storefront/      Customer web + Telegram.  Angular.  Standalone repository.
mobile/          Customer iOS + Android.   Flutter.  Standalone repository.
prototypes/      Throwaway design prototypes. React, and deliberately so.
```

## The four applications are not in this repository yet

Each of the four is a real git repository with its own history, sitting in this
directory and **ignored by the platform repository**. That is a deliberate
interim state, not an oversight.

[ADR 0035](../docs/adr/partial/0035-angular-frontend-platform-and-design-system-adoption.md)
makes them submodules. A submodule is a recorded commit plus a URL, and the URLs
do not exist yet — the four GitHub repositories have not been created. Committing
them now would record a gitlink with no matching `.gitmodules` entry, and every
`git clone --recursive` of the platform would then fail on a submodule that has
no URL to fetch from. So they are listed in `.gitignore` until there is somewhere
to point.

### Promoting them, once the remotes exist

Create four empty repositories under the `qoida-one` organisation named
`qoida-control-plane`, `qoida-operations`, `qoida-storefront` and
`qoida-mobile` — empty, with no README and no initial commit, or the first push
will conflict with the history that already exists here.

Then, for each of the four:

```bash
cd frontend/control-plane
git remote add origin git@github.com:qoida-one/qoida-control-plane.git
git push -u origin main
```

Then, from the platform repository root, remove the four `/frontend/*/` lines
from `.gitignore` and register them:

```bash
git submodule add git@github.com:qoida-one/qoida-control-plane.git frontend/control-plane
git submodule add git@github.com:qoida-one/qoida-operations.git   frontend/operations
git submodule add git@github.com:qoida-one/qoida-storefront.git   frontend/storefront
git submodule add git@github.com:qoida-one/qoida-mobile.git       frontend/mobile
```

`git submodule add` on a path that already contains a repository with the right
remote adopts it rather than re-cloning, so the histories are preserved. Commit
the resulting `.gitmodules` and the four gitlinks together — a `.gitmodules`
without its gitlinks, or the reverse, is a broken tree.

Verify by cloning somewhere else with `--recursive` before you push anything
else. That is the check that catches a wrong URL, and it costs a minute.

### What is built in them

Foundations, not screens: the shell, the design tokens, Keycloak PKCE
authentication, an API client honouring ADR 0031, localisation for ru / uz-Latn /
en, and enough tests to prove those work. Screens are built against
[docs/operations-spec/](../docs/operations-spec/) and the prototypes.

The three Angular applications build and their tests pass. **The Flutter
application has never been compiled** — Flutter and Dart are not installed on the
machine that scaffolded it, so its `pubspec.yaml`, structure and tokens are
written by hand and unverified. The first person with a Flutter toolchain should
expect `flutter analyze` to find real errors, and its README says what to check.

## Why prototypes are React when production is Angular

[ADR 0035](../docs/adr/partial/0035-angular-frontend-platform-and-design-system-adoption.md)
standardises production on Angular. Prototypes are the documented exception, for
one reason: the Qoida Design System is authored in React at `claude.ai/design`,
so a React prototype consumes the real components and renders the real visual
contract on day one. An Angular prototype would have to wait for the component
port, which is the slowest item on the critical path.

The exchange is explicit. A prototype answers "is this the right screen, in the
right order, with the right density" before anyone builds it in Angular. It is
never promoted to production, never imported by an application, and never
deployed. When the Angular application catches up, the prototype is deleted.

If a prototype starts acquiring an API client, routing, or tests, it has stopped
being a prototype and the work belongs in `apps/`.

## Prototypes

| Prototype | Surface | Purpose |
|---|---|---|
| `control-plane` | `.console` | SaaS administration. Validates the information architecture in [docs/frontend-information-architecture.md](../docs/frontend-information-architecture.md) |

Run one:

```bash
cd frontend/prototypes/control-plane && npm install && npm run dev
```

## The design contract

Prototypes follow the Qoida Design System's `.console` surface exactly, because a
prototype that invents its own visual language validates nothing:

- **0px corners.** Flat-square is the brand.
- **Hairline elevation only** — `1px solid #e0e0e0`. No drop shadows on console.
- **IBM Plex Sans**, closed type scale. No inline font sizes.
- **Platform blue `#0f62fe` scarcely** — links, primary actions, focus, selection.
  Never decoration, and never a tenant accent on console.
- **Sentence case. No emoji. No gradients. No illustration.**
- **UZS thousands-separated with spaces, no decimals** — `84 000 so'm` — in
  tabular figures.
- **24h clock, `DD.MM`** for ru and uz.
- Content is authentic Uzbek and Russian, not machine-translated placeholder.
