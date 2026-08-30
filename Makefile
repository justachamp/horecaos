# Root composition only. Each project owns its build; this file routes to them.
# Backend targets delegate to platform/Makefile — see `make -C platform help`.

.DEFAULT_GOAL := help

.PHONY: help verify lint test run up down eval frontend-install frontend-build ci

help: ## List targets
	@grep -hE '^[a-z][a-zA-Z0-9_-]*:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'

verify: ## Backend: full build and every test — the definition of done
	$(MAKE) -C platform verify

lint: ## Backend: repository rules, no JVM, under a minute
	$(MAKE) -C platform lint

test: ## Backend: tests only
	$(MAKE) -C platform test

run: ## Backend: start the API on :8080 with local fixtures
	$(MAKE) -C platform run

up: ## Start local PostgreSQL, Kafka, Keycloak
	$(MAKE) -C platform up

down: ## Stop the local dependency stack
	$(MAKE) -C platform down

eval: ## Agent-configuration regression suite
	$(MAKE) -C platform eval

frontend-install: ## Install dependencies for every frontend app
	cd frontend/control-plane && npm ci
	cd frontend/operations && npm ci
	cd frontend/storefront && npm ci

frontend-build: ## Build every frontend app
	cd frontend/control-plane && npm run build
	cd frontend/operations && npm run build
	cd frontend/storefront && npm run build

ci: lint verify ## What backend CI runs at the root
