# Frontends moved

The applications this directory once described as four sibling repositories now live in
the monorepo, at [`../../frontend`](../../frontend) (Angular apps and the canonical
design tokens) and [`../../mobile`](../../mobile) (Flutter) — see
[ADR 0052](../docs/adr/partial/0052-one-repository-for-the-whole-platform.md), which
supersedes the submodule plan this file used to carry.

What remains here is [`prototypes/`](prototypes/) — throwaway React design prototypes
kept as reference material (the documented ADR 0035 exception). They are not products;
do not extend them.
