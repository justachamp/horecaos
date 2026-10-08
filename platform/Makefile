# Single-command feedback loop for humans, agents, and CI.
# Every target exits non-zero on failure. See docs/sdlc.md.

# Maven runs through tools/mvn-serial, which holds a per-worktree lock: every
# Maven invocation in a single-module project compiles into the same
# target/classes, so two concurrent runs in one tree corrupt each other and
# fail as NoClassDefFoundError rather than cleanly. Plain ./mvnw bypasses that
# lock. The path is anchored to this Makefile so `make -f platform/Makefile`
# from elsewhere still finds it. Override with `make MVN=./mvnw verify` (or an
# MVN environment variable) when the lock is unwanted, e.g. inside a container
# that only ever runs one build.
PLATFORM_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))
MVN ?= $(PLATFORM_DIR)tools/mvn-serial

# The OpenApiSurface groups (ADR 0031). Keep in sync with
# src/main/java/uz/horecaos/platform/configuration/OpenApiSurface.java — the
# membership test in OpenApiContractTests fails the build if a path fits none
# of them, but nothing enforces this list matching that one automatically.
OPENAPI_GROUPS := storefront control-plane operations providers

.DEFAULT_GOAL := help

.PHONY: help verify test arch build lint format run up down logs eval fmt-check hooks-test bands-test shards-test makefile-test doc-links openapi-client-check openapi-baseline seed-payments pay-fake-click ci

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

lint: fmt-check doc-links hooks-test bands-test shards-test makefile-test routing-gate-test ## Static checks an agent must pass before review

fmt-check: ## Repository hygiene checks that need no JVM
	@python3 tools/checks/repo_hygiene.py

hooks-test: ## Prove the SDLC guardrail hooks block and allow the right things
	@python3 .claude/hooks/test_hooks.py

bands-test: ## Prove control-band detection escalates at the right thresholds
	@python3 ops/test_control_band.py

shards-test: ## Prove CI's Java test shards cover every test class exactly once, and ci.yml agrees
	@python3 tools/ci/test_shard_tests.py
	@python3 tools/ci/shard_tests.py check

routing-gate-test: ## Prove the routing accuracy gate can say no (ADR 0147)
	@cd tools/routing && python3 -m unittest test_accuracy_gate

makefile-test: ## Prove Maven targets run under the build lock and `make run` does not hold it while serving
	@python3 tools/checks/test_makefile.py

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

# `run` is the one target that must not sit inside the lock for its whole life:
# mvn-serial would hold this tree for as long as the server is up, so every
# other target here (`make test`, `make arch`) would queue behind it, and after
# mvn-serial's stale limit (30 minutes) it would stop protecting anything
# anyway. So the compile, which is what the lock exists for, runs under it, and
# the server starts after it is released. The second Maven has nothing left to
# compile.
run: ## Start the API on :8080 with local-only fixture data
	$(MVN) -q test-compile
	cd $(PLATFORM_DIR) && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local

up: ## Start local PostgreSQL, Kafka, and Keycloak
	docker compose up -d
	docker compose ps
	@echo "==> Assigning ADR 0009 Keycloak service-account roles"
	./infra/keycloak/assign-service-account-roles.sh
	@echo "==> Declaring the ADR 0148 OTP policy and the password-only staff client"
	./infra/keycloak/create-staff-password-check-client.sh

down: ## Stop the local dependency stack
	docker compose down

logs: ## Tail the local dependency stack
	docker compose logs -f --tail=100

eval: ## Run the agent-configuration regression suite
	@bash evals/run.sh

seed-payments: ## Seed a local dev CLICK payment setup for the docs/local-fixtures.md demo tenant
	@./tools/seed-payments

pay-fake-click: ## Pay an order's open CLICK checkout as a fake customer (ORDER_ID=<uuid> [TENANT_ID=<uuid>])
	@./tools/pay-fake-click $(ORDER_ID) $(TENANT_ID)

ci: lint verify openapi-client-check ## What CI runs
