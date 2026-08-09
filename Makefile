SHELL := /bin/bash

SCRIPT_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))
VENV       := $(SCRIPT_DIR).venv
UV         := uv
PYTHON     := $(VENV)/bin/python
SERVICE    := massalarme.service
CONFIG_DIR := $(or $(XDG_CONFIG_HOME),$(HOME)/.config)/massalarme
DATA_DIR   := $(or $(XDG_DATA_HOME),$(HOME)/.local/share)/massalarme
SYSTEMD_DIR := $(HOME)/.config/systemd/user
ANDROID_DIR := $(SCRIPT_DIR)lanalarm

.PHONY: install venv sync run secret start service-stop restart reload status logs enable disable \
        apk apk-install clean uninstall stop-alarm ring alarms listen \
        test test-py test-apk backfill sync-status sync-now set-token check-ontoplano schedule-now

# ── Setup ──────────────────────────────────────────────────────────────

install: venv sync dirs configs service enable
	@echo ""
	@echo "Done. Run 'make secret' to display the QR code."

venv:
	@test -d $(VENV) || $(UV) venv $(VENV)

sync: venv
	$(UV) pip install -r requirements.txt --python $(PYTHON)

dirs:
	@mkdir -p "$(CONFIG_DIR)" "$(DATA_DIR)"

configs: dirs
	@test -f "$(CONFIG_DIR)/config.yaml" \
		|| cp config.yaml.example "$(CONFIG_DIR)/config.yaml" \
		&& echo "Config: $(CONFIG_DIR)/config.yaml"
	@test -f "$(CONFIG_DIR)/alarms.json" \
		|| { test -f alarms.json \
			&& cp alarms.json "$(CONFIG_DIR)/alarms.json" \
			|| cp alarms.json.example "$(CONFIG_DIR)/alarms.json"; } \
		&& echo "Alarms: $(CONFIG_DIR)/alarms.json"
	@test -f "$(DATA_DIR)/weights.db" -o ! -f weights.db \
		|| { cp weights.db "$(DATA_DIR)/weights.db" \
			&& echo "Migrated weights.db"; }

# ── Service ────────────────────────────────────────────────────────────

service:
	@mkdir -p "$(SYSTEMD_DIR)"
	@sed 's|ExecStart=.*|ExecStart=$(UV) run --python $(PYTHON) $(SCRIPT_DIR)alarm_manager.py|' \
		$(SERVICE) > "$(SYSTEMD_DIR)/$(SERVICE)"
	@systemctl --user daemon-reload
	@echo "Installed systemd unit."

start:
	systemctl --user start $(SERVICE)

service-stop:
	systemctl --user stop $(SERVICE)

restart:
	systemctl --user restart $(SERVICE)

reload: service restart
	@echo "Reloaded unit and restarted daemon."

status:
	systemctl --user status $(SERVICE)

logs:
	journalctl --user -u massalarme -f

enable:
	systemctl --user enable $(SERVICE)

disable:
	systemctl --user disable $(SERVICE)

# ── Dev / Run ──────────────────────────────────────────────────────────

run: sync
	$(UV) run --python $(PYTHON) alarm_manager.py

listen: sync
	$(UV) run --python $(PYTHON) listen.py

secret: sync
	$(UV) run --python $(PYTHON) alarm_manager.py --show-secret

alarms: sync
	@$(UV) run --python $(PYTHON) alarm_manager.py --alarms

stop-alarm:
	@SECRET=$$(grep '^shared_secret:' "$(CONFIG_DIR)/config.yaml" | awk '{print $$2}'); \
	PC_PORT=$$(grep '^pc_port:' "$(CONFIG_DIR)/config.yaml" | awk '{print $$2}'); \
	PC_PORT=$${PC_PORT:-8888}; \
	PHONE_PORT=$$(grep '^port:' "$(CONFIG_DIR)/config.yaml" | awk '{print $$2}'); \
	PHONE_PORT=$${PHONE_PORT:-8080}; \
	MAC=$$(grep '^phone_mac:' "$(CONFIG_DIR)/config.yaml" | awk '{print $$2}'); \
	if [ -z "$$SECRET" ]; then echo "Error: shared_secret not found in config.yaml"; exit 1; fi; \
	curl -sf "http://localhost:$${PC_PORT}/stop-alarm?key=$${SECRET}" > /dev/null 2>&1; \
	IP=$$(ip neigh | grep -i "$$MAC" | awk '{print $$1}' | head -1); \
	if [ -n "$$IP" ]; then \
		curl -sf "http://$$IP:$${PHONE_PORT}/stop?key=$${SECRET}" && echo "Alarm stopped" || echo "Failed to reach phone"; \
	else \
		echo "Phone not found on LAN"; \
	fi

ring:
	@SECRET=$$(grep '^shared_secret:' "$(CONFIG_DIR)/config.yaml" | awk '{print $$2}'); \
	PORT=$$(grep '^port:' "$(CONFIG_DIR)/config.yaml" | awk '{print $$2}'); \
	MAC=$$(grep '^phone_mac:' "$(CONFIG_DIR)/config.yaml" | awk '{print $$2}'); \
	PORT=$${PORT:-8080}; \
	if [ -z "$$SECRET" ]; then echo "Error: shared_secret not found in config.yaml"; exit 1; fi; \
	if [ -z "$$MAC" ]; then echo "Error: phone_mac not found in config.yaml"; exit 1; fi; \
	IP=$$(ip neigh | grep -i "$$MAC" | awk '{print $$1}' | head -1); \
	if [ -z "$$IP" ]; then echo "Phone not found on LAN (MAC: $$MAC)"; exit 1; fi; \
	echo "Ringing phone at $$IP..."; \
	curl -sf "http://$$IP:$${PORT}/alarm?key=$${SECRET}" && echo || echo "Failed to reach phone"

# ── ontoplano ──────────────────────────────────────────────────────────

# Store the API token in a 0600 file (never in config.yaml, never in git).
# Usage: make set-token TOKEN=onto_xxx   — or omit TOKEN to be prompted.
set-token: sync
	@if [ -n "$(TOKEN)" ]; then \
		printf '%s' "$(TOKEN)" | $(UV) run --python $(PYTHON) alarm_manager.py --set-token -; \
	else \
		read -r -s -p "ontoplano token: " TOK; echo; \
		printf '%s' "$$TOK" | $(UV) run --python $(PYTHON) alarm_manager.py --set-token -; \
	fi

check-ontoplano: sync
	@$(UV) run --python $(PYTHON) alarm_manager.py --check-ontoplano

# Collapse the raw scale log into weigh-ins and queue the full history.
# Idempotent — safe to run as often as you like.
backfill: sync
	@$(UV) run --python $(PYTHON) alarm_manager.py --backfill

sync-status: sync
	@$(UV) run --python $(PYTHON) alarm_manager.py --sync-status

sync-now: sync
	@$(UV) run --python $(PYTHON) alarm_manager.py --sync-now

# Pull the ontoplano planner once and turn matching occurrences into alarms.
schedule-now: sync
	@$(UV) run --python $(PYTHON) alarm_manager.py --schedule-now

# ── Tests ──────────────────────────────────────────────────────────────

test: test-py test-apk

test-py: sync
	$(UV) run --python $(PYTHON) -m pytest -q

test-apk:
	$(ANDROID_DIR)/gradlew -p $(ANDROID_DIR) testDebugUnitTest

# ── Android ────────────────────────────────────────────────────────────

apk:
	$(ANDROID_DIR)/gradlew -p $(ANDROID_DIR) assembleDebug
	@echo "APK: $(ANDROID_DIR)/app/build/outputs/apk/debug/app-debug.apk"

apk-install:
	$(ANDROID_DIR)/gradlew -p $(ANDROID_DIR) installDebug

# ── Cleanup ────────────────────────────────────────────────────────────

clean:
	rm -rf $(VENV)

uninstall: service-stop disable
	rm -f "$(SYSTEMD_DIR)/$(SERVICE)"
	systemctl --user daemon-reload
	rm -rf $(VENV)
	rm -rf "$(CONFIG_DIR)" "$(DATA_DIR)"
	@echo "Uninstalled."
