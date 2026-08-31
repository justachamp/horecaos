# Root composition only. Each project owns its build; this file routes to them.
# Backend targets delegate to platform/Makefile — see `make -C platform help`.

.DEFAULT_GOAL := help

.PHONY: help verify lint test run up down eval frontend-install frontend-build ci \
        run-storefront run-operations run-control-plane run-frontends

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

# Each app serves in the foreground on its own port. The API (`make run`) must be
# up for any of them to do real work; the ports match .claude/launch.json and the
# Keycloak realm's registered redirect URIs, so keep the two files in step.
# Storefront sits on 5001 because macOS ControlCenter (AirPlay) squats on 5000.

run-storefront: ## Serve the customer storefront on :5001 (foreground)
	npm --prefix frontend/storefront start -- --port 5001

run-operations: ## Serve the operations app on :4200 (foreground)
	npm --prefix frontend/operations start

run-control-plane: ## Serve the control-plane app on :4300 (foreground)
	npm --prefix frontend/control-plane start -- --port 4300

run-frontends: ## Serve all three apps (background, logs in /tmp/horecaos-*.log; stop with pkill -f 'ng serve')
	@npm --prefix frontend/storefront start -- --port 5001 > /tmp/horecaos-storefront.log 2>&1 & echo "storefront    :5001  (log: /tmp/horecaos-storefront.log)"
	@npm --prefix frontend/operations start > /tmp/horecaos-operations.log 2>&1 & echo "operations    :4200  (log: /tmp/horecaos-operations.log)"
	@npm --prefix frontend/control-plane start -- --port 4300 > /tmp/horecaos-control-plane.log 2>&1 & echo "control-plane :4300  (log: /tmp/horecaos-control-plane.log)"

ci: lint verify ## What backend CI runs at the root
