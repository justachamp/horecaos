# Single-command feedback loop for humans, agents, and CI.
# Every target exits non-zero on failure. See docs/sdlc.md.

MVN := ./mvnw

# The OpenApiSurface groups (ADR 0031). Keep in sync with
# src/main/java/uz/horecaos/platform/configuration/OpenApiSurface.java — the
# membership test in OpenApiContractTests fails the build if a path fits none
# of them, but nothing enforces this list matching that one automatically.
OPENAPI_GROUPS := storefront control-plane operations providers

.DEFAULT_GOAL := help

.PHONY: help verify test arch build lint format run up down logs eval fmt-check hooks-test bands-test doc-links openapi-client-check openapi-baseline ci

help: ## List targets
	@grep -hE '^[a-z][a-zA-Z0-9_-]*:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}'

verify: ## Full build and every test — the definition of done
	$(MVN) verify

test: ## Unit and module tests only
	$(MVN) test

arch: ## Spring Modulith boundary and architecture tests
	$(MVN) test -Dtest='ModularArchitectureTests,ExternalCallTransactionBoundaryTests' -DfailIfNoTests=false

build: ## Compile and package without running tests
	$(MVN) -DskipTests package

format: ## Reformat Java sources (ADR 0054)
	$(MVN) spotless:apply

lint: fmt-check doc-links hooks-test bands-test ## Static checks an agent must pass before review

fmt-check: ## Repository hygiene checks that need no JVM
	@python3 tools/checks/repo_hygiene.py

hooks-test: ## Prove the SDLC guardrail hooks block and allow the right things
	@python3 .claude/hooks/test_hooks.py

bands-test: ## Prove control-band detection escalates at the right thresholds
	@python3 ops/test_control_band.py

doc-links: ## Every relative link in the repository's markdown resolves
	@python3 tools/checks/doc_links.py

bands: ## Show the current control bands
	@python3 ops/control_band_watch.py --status

openapi-client-check: ## Regenerate the typed OpenAPI clients (full + per-group) and reject undocumented drift
	$(MVN) test -Dtest=OpenApiContractTests
	@python3 tools/openapi/generate_types.py --input target/openapi/horecaos-api-v1.json --output target/openapi/horecaos-api-v1.ts
	@diff -u api/generated/horecaos-api-v1.ts target/openapi/horecaos-api-v1.ts
	@for group in $(OPENAPI_GROUPS); do \
		python3 tools/openapi/generate_types.py \
			--input "target/openapi/horecaos-api-v1.$$group.json" \
			--output "target/openapi/horecaos-api-v1.$$group.ts" && \
		diff -u "api/generated/horecaos-api-v1.$$group.ts" "target/openapi/horecaos-api-v1.$$group.ts" || exit 1; \
	done

openapi-baseline: ## Reviewably refresh the OpenAPI baselines and generated typed clients (full + per-group)
	$(MVN) test -Dtest=OpenApiContractTests -Dhorecaos.openapi.updateBaseline=true
	@python3 tools/openapi/generate_types.py --input target/openapi/horecaos-api-v1.json --output api/generated/horecaos-api-v1.ts
	@for group in $(OPENAPI_GROUPS); do \
		python3 tools/openapi/generate_types.py \
			--input "target/openapi/horecaos-api-v1.$$group.json" \
			--output "api/generated/horecaos-api-v1.$$group.ts"; \
	done

run: ## Start the API on :8080 with local-only fixture data
	$(MVN) spring-boot:run -Dspring-boot.run.profiles=local

up: ## Start local PostgreSQL, Kafka, and Keycloak
	docker compose up -d
	docker compose ps

down: ## Stop the local dependency stack
	docker compose down

logs: ## Tail the local dependency stack
	docker compose logs -f --tail=100

eval: ## Run the agent-configuration regression suite
	@bash evals/run.sh

ci: lint verify openapi-client-check ## What CI runs
