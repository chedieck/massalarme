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

.PHONY: install venv sync run secret start stop restart reload status logs enable disable \
        apk apk-install clean uninstall stop-alarm

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

stop:
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

secret: sync
	$(UV) run --python $(PYTHON) alarm_manager.py --show-secret

stop-alarm:
	@SECRET=$$(grep '^shared_secret:' "$(CONFIG_DIR)/config.yaml" | awk '{print $$2}'); \
	PORT=$$(grep '^pc_port:' "$(CONFIG_DIR)/config.yaml" | awk '{print $$2}'); \
	PORT=$${PORT:-8888}; \
	if [ -z "$$SECRET" ]; then echo "Error: shared_secret not found in config.yaml"; exit 1; fi; \
	curl -sf "http://localhost:$${PORT}/stop-alarm?key=$${SECRET}" && echo || echo "Failed to reach daemon"

# ── Android ────────────────────────────────────────────────────────────

apk:
	$(ANDROID_DIR)/gradlew -p $(ANDROID_DIR) assembleDebug
	@echo "APK: $(ANDROID_DIR)/app/build/outputs/apk/debug/app-debug.apk"

apk-install:
	$(ANDROID_DIR)/gradlew -p $(ANDROID_DIR) installDebug

# ── Cleanup ────────────────────────────────────────────────────────────

clean:
	rm -rf $(VENV)

uninstall: stop disable
	rm -f "$(SYSTEMD_DIR)/$(SERVICE)"
	systemctl --user daemon-reload
	rm -rf $(VENV)
	rm -rf "$(CONFIG_DIR)" "$(DATA_DIR)"
	@echo "Uninstalled."
