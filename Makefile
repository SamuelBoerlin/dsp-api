# Determine this makefile's path.
# Be sure to place this BEFORE `include` directives, if any.
# THIS_FILE := $(lastword $(MAKEFILE_LIST))
THIS_FILE := $(abspath $(lastword $(MAKEFILE_LIST)))
CURRENT_DIR := $(shell dirname $(realpath $(firstword $(MAKEFILE_LIST))))

include vars.mk

#################################
# Documentation targets
#################################

.PHONY: docs-publish
docs-publish: ## build and publish docs to Github Pages
	@$(MAKE) -C docs graphvizfigures
	mkdocs gh-deploy

.PHONY: docs-build
docs-build: ## build docs into the local 'site' folder
	@$(MAKE) -C docs graphvizfigures
	mkdocs build

.PHONY: docs-serve
docs-serve: ## serve docs for local viewing
	@$(MAKE) -C docs graphvizfigures
	mkdocs serve

.PHONY: docs-install-requirements
docs-install-requirements: ## install requirements
	pip3 install -r docs/requirements.txt

.PHONY: docs-clean
docs-clean: ## cleans the project directory
	@rm -rf site/

#################################
# Bazel targets
#################################

.PHONY: build
build: ## build all targets (excluding docs)
	@bazel build //...

.PHOBY: check-for-outdated-deps
check-for-outdated-deps: ## check for outdated maven dependencies
	@bazel run @maven//:outdated

.PHONY: buildifier
buildifier: ## format Bazel WORKSPACE and BUILD.bazel files
	@bazel run :buildifier

#################################
# Docker targets
#################################

.PHONY: docker-build-knora-api-image
docker-build-knora-api-image: # build and publish knora-api docker image locally
	@bazel run //docker/knora-api:image

.PHONY: docker-publish-knora-api-image
docker-publish-knora-api-image: # publish knora-api image to Dockerhub
	@bazel run //docker/knora-api:push

.PHONY: docker-build-knora-jena-fuseki-image
docker-build-knora-jena-fuseki-image: # build and publish knora-jena-fuseki docker image locally
	@bazel run //docker/knora-jena-fuseki:image

.PHONY: docker-publish-knora-jena-fuseki-image
docker-publish-knora-jena-fuseki-image: # publish knora-jena-fuseki image to Dockerhub
	@bazel run //docker/knora-jena-fuseki:push

.PHONY: docker-build-knora-sipi-image
docker-build-knora-sipi-image: # build and publish knora-sipi docker image locally
	@bazel run --action_env=PULLER_TIMEOUT=2400 //docker/knora-sipi:image

.PHONY: docker-publish-knora-sipi-image
docker-publish-knora-sipi-image: # publish knora-sipi image to Dockerhub
	@bazel run //docker/knora-sipi:push

.PHONY: docker-build
docker-build: docker-build-knora-api-image docker-build-knora-jena-fuseki-image docker-build-knora-sipi-image ## build and publish all Docker images locally

.PHONY: docker-publish
docker-publish: docker-publish-knora-api-image docker-publish-knora-jena-fuseki-image docker-publish-knora-sipi-image ## publish all Docker images to Dockerhub

#################################
## Docker-Compose targets
#################################

.PHONY: print-env-file
print-env-file: ## prints the env file used by knora-stack
	@cat .env

.PHONY: env-file
env-file: ## write the env file used by knora-stack.
ifeq ($(KNORA_DB_HOME), unknown)
	@echo KNORA_DB_HOME_DIR=db-home > .env
else
	$(info Using $(KNORA_DB_HOME) for the DB home directory.)
	@echo KNORA_DB_HOME_DIR=$(KNORA_DB_HOME) > .env
endif
ifeq ($(KNORA_DB_IMPORT), unknown)
	@echo KNORA_DB_IMPORT_DIR=db-import >> .env
else
	$(info Using $(KNORA_DB_IMPORT) for the DB import directory.)
	@echo KNORA_DB_IMPORT_DIR=$(KNORA_DB_IMPORT) >> .env
endif
	@echo DOCKERHOST=$(DOCKERHOST) >> .env
	@echo KNORA_DB_REPOSITORY_NAME=$(KNORA_DB_REPOSITORY_NAME) >> .env
	@echo LOCAL_HOME=$(CURRENT_DIR) >> .env

#################################
## Knora Stack Targets
#################################

.PHONY: stack-up
stack-up: docker-build env-file ## starts the knora-stack: fuseki, sipi, redis, api.
	docker-compose -f docker-compose.yml up -d db
	$(CURRENT_DIR)/webapi/scripts/wait-for-db.sh
	docker-compose -f docker-compose.yml up -d
	$(CURRENT_DIR)/webapi/scripts/wait-for-knora.sh

.PHONY: stack-up-with-metadata
stack-up-with-metadata: stack-up metadata ## starts stack and adds metadata

.PHONY: stack-up-fast
stack-up-fast: docker-build-knora-api-image env-file ## starts the knora-stack by skipping rebuilding most of the images (only api image is rebuilt).
	docker-compose -f docker-compose.yml up -d

.PHONY: stack-up-ci
stack-up-ci: KNORA_DB_REPOSITORY_NAME := knora-test-unit
stack-up-ci: docker-build env-file print-env-file ## starts the knora-stack using 'knora-test-unit' repository: fuseki, sipi, redis, api.
	docker-compose -f docker-compose.yml up -d

.PHONY: stack-restart
stack-restart: stack-up ## re-starts the knora-stack: fuseki, sipi, redis, api.
	@docker-compose -f docker-compose.yml restart

.PHONY: stack-restart-api
stack-restart-api: ## re-starts the api. Usually used after loading data into fuseki.
	docker-compose -f docker-compose.yml restart api
	@$(CURRENT_DIR)/webapi/scripts/wait-for-knora.sh

.PHONY: stack-logs
stack-logs: ## prints out and follows the logs of the running knora-stack.
	@docker-compose -f docker-compose.yml logs -f

.PHONY: stack-logs-db
stack-logs-db: ## prints out and follows the logs of the 'db' container running in knora-stack.
	@docker-compose -f docker-compose.yml logs -f db

.PHONY: stack-logs-db-no-follow
stack-logs-db-no-follow: ## prints out the logs of the 'db' container running in knora-stack.
	docker-compose -f docker-compose.yml logs db

.PHONY: stack-logs-sipi
stack-logs-sipi: ## prints out and follows the logs of the 'sipi' container running in knora-stack.
	@docker-compose -f docker-compose.yml logs -f sipi

.PHONY: stack-logs-sipi-no-follow
stack-logs-sipi-no-follow: ## prints out the logs of the 'sipi' container running in knora-stack.
	@docker-compose -f docker-compose.yml logs sipi

.PHONY: stack-logs-redis
stack-logs-redis: ## prints out and follows the logs of the 'redis' container running in knora-stack.
	@docker-compose -f docker-compose.yml logs -f redis

.PHONY: stack-logs-api
stack-logs-api: ## prints out and follows the logs of the 'api' container running in knora-stack.
	@docker-compose -f docker-compose.yml logs -f api

.PHONY: stack-logs-api-no-follow
stack-logs-api-no-follow: ## prints out the logs of the 'api' container running in knora-stack.
	docker-compose -f docker-compose.yml logs api

.PHONY: stack-health
stack-health:
	curl -f 0.0.0.0:3333/health

.PHONY: stack-status
stack-status:
	docker-compose -f docker-compose.yml ps

.PHONY: stack-down
stack-down: ## stops the knora-stack.
	docker-compose -f docker-compose.yml down

.PHONY: stack-down-delete-volumes
stack-down-delete-volumes: ## stops the knora-stack and deletes any created volumes (deletes the database!).
	docker-compose -f docker-compose.yml down --volumes

.PHONY: stack-config
stack-config: env-file
	docker-compose -f docker-compose.yml config

## stack without api
.PHONY: stack-without-api
stack-without-api: stack-up ## starts the knora-stack without knora-api: fuseki, sipi, redis.
	@docker-compose -f docker-compose.yml stop api

.PHONY: stack-without-api-and-sipi
stack-without-api-and-sipi: stack-up ## starts the knora-stack without knora-api and sipi: fuseki, redis.
	@docker-compose -f docker-compose.yml stop api
	@docker-compose -f docker-compose.yml stop sipi

.PHONY: stack-db-only
stack-db-only: docker-build-knora-jena-fuseki-image env-file ## starts only fuseki.
	docker-compose -f docker-compose.yml up -d db
	$(CURRENT_DIR)/webapi/scripts/wait-for-db.sh

#################################
## Test Targets
#################################

.PHONY: test-docker
test-docker: docker-build ## runs Docker image tests
	bazel test //docker/...

.PHONY: test-webapi
test-webapi: docker-build ## runs all dsp-api tests.
	bazel test //webapi/...

.PHONY: test-unit
test-unit: docker-build ## runs the dsp-api unit tests.
	bazel test \
	//webapi/src/test/scala/org/knora/webapi/contributors/... \
	//webapi/src/test/scala/org/knora/webapi/http/... \
	//webapi/src/test/scala/org/knora/webapi/messages/... \
	//webapi/src/test/scala/org/knora/webapi/other/... \
	//webapi/src/test/scala/org/knora/webapi/responders/... \
	//webapi/src/test/scala/org/knora/webapi/routing/... \
	//webapi/src/test/scala/org/knora/webapi/store/... \
	//webapi/src/test/scala/org/knora/webapi/util/... \

.PHONY: test-e2e
test-e2e: docker-build ## runs the dsp-api e2e tests.
	bazel test //webapi/src/test/scala/org/knora/webapi/e2e/...

.PHONY: client-test-data
client-test-data: docker-build ## runs the dsp-api e2e tests and generates client test data.
	docker-compose -f docker-compose.yml up -d redis
	$(CURRENT_DIR)/webapi/scripts/clear-client-test-data.sh
	bazel test --cache_test_results=no //webapi/src/test/scala/org/knora/webapi/e2e/... --action_env=KNORA_WEBAPI_COLLECT_CLIENT_TEST_DATA=true
	$(CURRENT_DIR)/webapi/scripts/dump-client-test-data.sh

.PHONY: test-it
test-it: docker-build ## runs the dsp-api integration tests.
	bazel test //webapi/src/test/scala/org/knora/webapi/it/...

.PHONY: test-repository-upgrade
test-repository-upgrade: init-db-test-minimal ## runs DB upgrade integration test
	@rm -rf $(CURRENT_DIR)/.tmp/knora-test-data/v13/
	@mkdir -p $(CURRENT_DIR)/.tmp/knora-test-data/v13/
	@unzip $(CURRENT_DIR)/test_data/v13/v13-knora-test.trig.zip -d $(CURRENT_DIR)/.tmp/knora-test-data/v13/
	# empty repository
	$(CURRENT_DIR)/webapi/scripts/fuseki-empty-repository.sh -r knora-test -u admin -p test -h localhost:3030
	# load v13 data
	$(CURRENT_DIR)/webapi/scripts/fuseki-upload-repository.sh -r knora-test -u admin -p test -h localhost:3030 $(CURRENT_DIR)/.tmp/knora-test-data/v13/v13-knora-test.trig
	# call target which restarts the API and emits error if API does not start
	# after a certain time. at startup, data should be upgraded.
	@$(MAKE) -f $(THIS_FILE) stack-up

.PHONY: test
test: docker-build ## runs all test targets.
	bazel test //webapi/...

#################################
## Database Management
#################################

.PHONY: init-db-test
init-db-test: stack-down-delete-volumes stack-db-only ## initializes the knora-test repository
	@echo $@
	@$(MAKE) -C webapi/scripts fuseki-init-knora-test

.PHONY: init-db-test-minimal
init-db-test-minimal: stack-down-delete-volumes stack-db-only ## initializes the knora-test repository with minimal data
	@echo $@
	@$(MAKE) -C webapi/scripts fuseki-init-knora-test-minimal

.PHONY: init-db-test-empty
init-db-test-empty: stack-down-delete-volumes stack-db-only ## initializes the knora-test repository with minimal data
	@echo $@
	@$(MAKE) -C webapi/scripts fuseki-init-knora-test-empty

.PHONY: init-db-test-unit
init-db-test-unit: stack-down-delete-volumes stack-db-only ## initializes the knora-test-unit repository
	@echo $@
	@$(MAKE) -C webapi/scripts fuseki-init-knora-test-unit

.PHONY: init-db-test-unit-minimal
init-db-test-unit-minimal: stack-down-delete-volumes stack-db-only ## initializes the knora-test-unit repository with minimal data
	@echo $@
	@$(MAKE) -C webapi/scripts fuseki-init-knora-test-unit-minimal

## Dump staging data
db_staging_dump.trig:
	@echo $@
	@curl -X GET -H "Accept: application/trig" -u "admin:${DB_STAGING_PASSWORD}" "https://db.staging.dasch.swiss/dsp-repo" > "db_staging_dump.trig"

## Dump production data
db_prod_dump.trig:
	@echo $@
	@curl -X GET -H "Accept: application/trig" -u "admin:${DB_PROD_PASSWORD}" "https://db.dasch.swiss/dsp-repo" > "db_prod_dump.trig"

.PHONY: init-db-test-from-staging
init-db-test-from-staging: db_staging_dump.trig init-db-test-empty ## init local database with data from staging
	@echo $@
	@curl -X POST -H "Content-Type: application/sparql-update" -d "DROP ALL" -u "admin:test" "http://localhost:3030/knora-test"
	@curl -X POST -H "Content-Type: application/trig" --data-binary "@${CURRENT_DIR}/db_staging_dump.trig" -u "admin:test" "http://localhost:3030/knora-test"

.PHONY: init-db-test-from-prod
init-db-test-from-prod: db_prod_dump.trig init-db-test-empty ## init local database with data from production
	@echo $@
	@curl -X POST -H "Content-Type: application/sparql-update" -d "DROP ALL" -u "admin:test" "http://localhost:3030/knora-test"
	@curl -X POST -H "Content-Type: application/trig" --data-binary "@${CURRENT_DIR}/db_prod_dump.trig" -u "admin:test" "http://localhost:3030/knora-test"

.PHONY: metadata
metadata: metadata-standard metadata-minimal metadata-maximal ## add three example metadata sets

.PHONY: metadata-standard
metadata-standard: ## add pseudo-realistic metadata set to anything project
	@echo $@
	@mkdir -p .tmp
	@curl https://raw.githubusercontent.com/dasch-swiss/dsp-ontologies/main/example/example-metadata.ttl -o .tmp/metadata.ttl -s
	@curl -X PUT -u root@example.com:test -H "Content-Type: text/turtle" -T ".tmp/metadata.ttl" "localhost:3333/v2/metadata/http%3A%2F%2Frdfh.ch%2Fprojects%2F0001" -s -o /dev/null

.PHONY: metadata-minimal
metadata-minimal: ## add minimal metadata set to images project
	@echo $@
	@mkdir -p .tmp
	@curl https://raw.githubusercontent.com/dasch-swiss/dsp-ontologies/main/example/example-metadata-minimal.ttl -o .tmp/metadata.ttl -s
	@curl -X PUT -u root@example.com:test -H "Content-Type: text/turtle" -T ".tmp/metadata.ttl" "localhost:3333/v2/metadata/http%3A%2F%2Frdfh.ch%2Fprojects%2F00FF" -s -o /dev/null

.PHONY: metadata-maximal
metadata-maximal: ## add maximal metadata set to dokubib project
	@echo $@
	@mkdir -p .tmp
	@curl https://raw.githubusercontent.com/dasch-swiss/dsp-ontologies/main/example/example-metadata-maximal.ttl -o .tmp/metadata.ttl -s
	@curl -X PUT -u root@example.com:test -H "Content-Type: text/turtle" -T ".tmp/metadata.ttl" "localhost:3333/v2/metadata/http%3A%2F%2Frdfh.ch%2Fprojects%2F0804" -s -o /dev/null

#################################
## Other
#################################

clean-docker: ## cleans the docker installation
	@docker system prune -af

.PHONY: clean-local-tmp
clean-local-tmp:
	@rm -rf .tmp
	@mkdir .tmp

clean: docs-clean clean-local-tmp clean-docker ## clean build artifacts
	@rm -rf .env
	@bazel clean

.PHONY: info
info: ## print out all variables
	@echo "BUILD_TAG: \t\t $(BUILD_TAG)"
	@echo "GIT_EMAIL: \t\t $(GIT_EMAIL)"
	@echo "KNORA_DB_IMPORT: \t $(KNORA_DB_IMPORT)"
	@echo "KNORA_DB_HOME: \t\t $(KNORA_DB_HOME)"

.PHONY: help
help: ## this help
	@awk 'BEGIN {FS = ":.*?## "} /^[a-zA-Z_-]+:.*?## / {printf "\033[36m%-30s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST) | sort

.DEFAULT_GOAL := help
