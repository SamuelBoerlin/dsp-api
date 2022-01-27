FUSEKI_HEAP_SIZE := 3G

KNORA_WEBAPI_DB_CONNECTIONS := 2
KNORA_DB_REPOSITORY_NAME := knora-test

ifeq ($(BUILD_TAG),)
  BUILD_TAG := $(shell git describe --tag --dirty --abbrev=7)
endif
ifeq ($(BUILD_TAG),)
  BUILD_TAG := $(shell git rev-parse --verify HEAD)
endif

# When running a rebuild +run_id is appended to the tag
# to set it apart from the original release
ifeq ($(GITHUB_EVENT_NAME),workflow_dispatch)
	BUILD_TAG := $(BUILD_TAG)+$(GITHUB_RUN_NUMBER)
endif

$(info Build tag: $(BUILD_TAG))

ifeq ($(GIT_EMAIL),)
  GIT_EMAIL := $(shell git config user.email)
endif

ifeq ($(KNORA_DB_IMPORT),)
  KNORA_DB_IMPORT := unknown
endif

ifeq ($(KNORA_DB_HOME),)
  KNORA_DB_HOME := unknown
endif

UNAME := $(shell uname)
ifeq ($(UNAME),Darwin)
  DOCKERHOST :=  $(shell ifconfig en0 | grep inet | grep -v inet6 | cut -d ' ' -f2)
else
  DOCKERHOST := $(shell ip -4 addr show docker0 | grep -Po 'inet \K[\d.]+')
endif
