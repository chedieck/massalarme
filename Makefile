SHELL := /bin/bash

SCRIPT_DIR  := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))
VENV        := $(SCRIPT_DIR).venv
UV          := uv
PYTHON      := $(VENV)/bin/python
CONFIG_DIR  := $(or $(XDG_CONFIG_HOME),$(HOME)/.config)/massalarme
DATA_DIR    := $(or $(XDG_DATA_HOME),$(HOME)/.local/share)/massalarme
ANDROID_DIR := $(SCRIPT_DIR)lanalarm
GRADLE      := $(ANDROID_DIR)/gradlew -p $(ANDROID_DIR)

# Gradle reads sdk.dir from local.properties in preference to $ANDROID_HOME, so a
# stale path in that file fails the build even on a machine with a perfectly good
# SDK. The file is machine-specific and untracked, so generate it — and
# regenerate it whenever it points somewhere that is no longer there.
LOCAL_PROPS := $(ANDROID_DIR)/local.properties
ANDROID_SDK := $(firstword $(wildcard $(ANDROID_HOME)) $(wildcard $(ANDROID_SDK_ROOT)) \
                 $(wildcard $(HOME)/Android/Sdk) $(wildcard /opt/android-sdk) \
                 $(wildcard /usr/lib/android-sdk))

.DEFAULT_GOAL := help

.PHONY: help apk apk-install sdk test test-py test-apk \
        daemon-setup set-token phone-qr check-ontoplano \
        backfill sync-now sync-status listen alarms run \
        clean remove-service

# ── Help ───────────────────────────────────────────────────────────────

help:
	@echo "Massalarme — the app runs on the phone. These are the tools around it."
	@echo
	@echo "  Android"
	@echo "    make apk               Build the debug APK"
	@echo "    make apk-install       Build and install it (phone on adb)"
	@echo "    make test              Both test suites"
	@echo
	@echo "  Connect the phone to ontoplano (optional)"
	@echo "    make daemon-setup      One-off: venv, deps, config file"
	@echo "    make set-token         Store the API token in a 0600 file"
	@echo "    make phone-qr          Show the QR the app scans"
	@echo "    make check-ontoplano   Verify the token and show its scopes"
	@echo
	@echo "  Move existing history into ontoplano"
	@echo "    make backfill          Collapse the raw scale log into weigh-ins"
	@echo "    make sync-now          Push the queue once"
	@echo "    make sync-status       Pending count, last success, last error"
	@echo
	@echo "  Debugging"
	@echo "    make listen            Watch what the scale is broadcasting"
	@echo "    make alarms            Upcoming alarms per the daemon's copy"
	@echo
	@echo "  Cleanup"
	@echo "    make clean             Remove the venv and Android build output"
	@echo "    make remove-service    Remove the old systemd unit, if you have one"

# ── Android ────────────────────────────────────────────────────────────

apk: sdk
	$(GRADLE) assembleDebug
	@echo "APK: $(ANDROID_DIR)/app/build/outputs/apk/debug/app-debug.apk"

apk-install: sdk
	$(GRADLE) installDebug

# Point Gradle at an SDK, if it is not already pointed at a real one.
sdk:
	@if [ -f "$(LOCAL_PROPS)" ] && \
	   [ -d "$$(sed -n 's|^sdk\.dir=||p' "$(LOCAL_PROPS)")" ]; then exit 0; fi; \
	if [ -z "$(ANDROID_SDK)" ]; then \
		echo "No Android SDK found."; \
		echo "Set ANDROID_HOME, or write 'sdk.dir=<path>' to $(LOCAL_PROPS)."; \
		exit 1; \
	fi; \
	echo "sdk.dir=$(ANDROID_SDK)" > "$(LOCAL_PROPS)"; \
	echo "Wrote $(LOCAL_PROPS) -> $(ANDROID_SDK)"

# ── Tests ──────────────────────────────────────────────────────────────

test: test-py test-apk

test-py: $(VENV)
	$(UV) run --python $(PYTHON) -m pytest -q

test-apk: sdk
	$(GRADLE) testDebugUnitTest

# ── Python tools ───────────────────────────────────────────────────────
#
# The daemon no longer runs as a service and the app does not talk to it. What
# is left is a handful of one-shot commands: getting a token onto the phone, and
# moving the weight history this repo already holds into ontoplano.

$(VENV):
	$(UV) venv $(VENV)
	$(UV) pip install -r requirements.txt --python $(PYTHON)

daemon-setup: $(VENV)
	@mkdir -p "$(CONFIG_DIR)" "$(DATA_DIR)"
	@test -f "$(CONFIG_DIR)/config.yaml" \
		|| { cp config.yaml.example "$(CONFIG_DIR)/config.yaml"; \
		     echo "Config: $(CONFIG_DIR)/config.yaml — set ontoplano.base_url"; }
	@test -f "$(DATA_DIR)/weights.db" -o ! -f weights.db \
		|| { cp weights.db "$(DATA_DIR)/weights.db"; echo "Migrated weights.db"; }

# Store the API token in a 0600 file (never in config.yaml, never in git).
# Usage: make set-token TOKEN=onto_xxx   — or omit TOKEN to be prompted.
set-token: $(VENV)
	@if [ -n "$(TOKEN)" ]; then \
		printf '%s' "$(TOKEN)" | $(UV) run --python $(PYTHON) alarm_manager.py --set-token -; \
	else \
		read -r -s -p "ontoplano token: " TOK; echo; \
		printf '%s' "$$TOK" | $(UV) run --python $(PYTHON) alarm_manager.py --set-token -; \
	fi

# The QR the app scans: base URL plus token. Printed only, never written to an
# image — a token saved as a PNG is a token in someone's photo roll.
phone-qr: $(VENV)
	@$(UV) run --python $(PYTHON) alarm_manager.py --phone-qr

check-ontoplano: $(VENV)
	@$(UV) run --python $(PYTHON) alarm_manager.py --check-ontoplano

# Collapse the raw scale log into weigh-ins and queue the full history.
# Idempotent — safe to run as often as you like.
backfill: $(VENV)
	@$(UV) run --python $(PYTHON) alarm_manager.py --backfill

sync-now: $(VENV)
	@$(UV) run --python $(PYTHON) alarm_manager.py --sync-now

sync-status: $(VENV)
	@$(UV) run --python $(PYTHON) alarm_manager.py --sync-status

# ── Debugging ──────────────────────────────────────────────────────────

listen: $(VENV)
	$(UV) run --python $(PYTHON) listen.py

alarms: $(VENV)
	@$(UV) run --python $(PYTHON) alarm_manager.py --alarms

# The legacy daemon in the foreground. Nothing on the phone talks to it.
run: $(VENV)
	$(UV) run --python $(PYTHON) alarm_manager.py

# ── Cleanup ────────────────────────────────────────────────────────────

clean:
	rm -rf $(VENV)
	@$(GRADLE) clean >/dev/null 2>&1 || true

# For anyone who installed the old systemd unit back when the daemon had to be
# running. Nothing needs it now.
remove-service:
	@systemctl --user disable --now massalarme.service 2>/dev/null || true
	@rm -f "$(HOME)/.config/systemd/user/massalarme.service"
	@systemctl --user daemon-reload 2>/dev/null || true
	@echo "systemd unit removed."
