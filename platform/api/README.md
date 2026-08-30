# API contract releases

[OpenAPI v1](openapi/v1/qoida-api.json) is the reviewed released contract. It
is generated from Springdoc through the real MVC surface, not maintained by
hand.

Run `make openapi-baseline` after an intentional additive API change. The
command first rejects breaking changes against the current v1 baseline, then
updates the reviewed document and its TypeScript client artifact. CI regenerates
both from the server and fails on a mismatch.

The generated [TypeScript client contract](generated/qoida-api-v1.ts) contains
schema types plus the typed transport interface that standalone frontend
repositories implement. It deliberately does not choose a browser fetch,
Keycloak, retry, or cache library for them.
