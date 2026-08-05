# ST

Home automation config for the villa. Two unrelated halves:

- `homeassistant/` — the live work. Guest Wi-Fi presence on Home Assistant.
- `smartapps/`, `devicetypes/` — legacy SmartThings Groovy. Superseded; the
  SmartThings Groovy platform was shut down at the end of 2022. Kept for
  reference only. Nothing in `homeassistant/` depends on it.

## The goal

Anyone joining the **Gorcho** guest Wi-Fi makes a Home Assistant person called
**Guest** present. When Guest is home and Matthew, Justin and Helen are all
away, **guest mode** turns on.

```
sensor.gorcho_clients (UniFi Network integration, per-WLAN "Clients" sensor)
  -> sensor.gorcho_guest_client_count      normalise unknown/unavailable to 0
  -> binary_sensor.gorcho_guests_online    > 0, with 10 min delay_off
  -> device_tracker.guest_wifi             in_zones: ['zone.home'] | []
  -> person.guest                          home / not_home
  -> input_boolean.guest_mode              + household all away
```

## State as of the last session

Done:

- UniFi Network integration is installed and working. `sensor.gorcho_clients`
  increments when a device joins Gorcho — confirmed by the user.
- `homeassistant/packages/villa_guest_presence.yaml` — the full package.
- `homeassistant/scripts/create_guest_presence.py` — creates the tracker helper
  and the person over HA's APIs. Never run against a real instance.

Not done:

1. **`person.guest` does not exist yet.** This is the immediate task. Either run
   the script, or create the helper + person through the UI (both routes are in
   `homeassistant/README.md`).
2. **The household entity IDs are unverified.** The guest mode automation
   assumes `person.matthew`, `person.justin`, `person.helen`. Check
   Developer tools -> States and fix the four references in the automation if
   they differ. The automation cannot work until this is confirmed.
3. **`input_boolean.guest_mode` drives nothing.** It is a flag with no
   behaviour attached yet.

## Facts established by reading HA source (do not re-derive)

- The per-SSID client counter is **built into the core UniFi Network
  integration** (`wlan_clients`, enabled by default). There is no separate
  "UniFi Counter" integration to install.
- `device_tracker.see` is **deprecated, removal in HA 2027.5**. Do not use it.
- The `template` integration gained a `device_tracker` platform in **2026.6**.
  That is the mechanism in use here, and it is the reason for the version gate.
- `in_zones: ['zone.home']` resolves to state `home`; `in_zones: []` resolves to
  `not_home`. Verified in `device_tracker/entity.py`.
- Person creation is **websocket only** (`person/create`). It is not exposed
  over REST. The template helper is created through the config flow REST API
  (`POST /api/config/config_entries/flow`, handler `template`, menu step
  `device_tracker`).

## Gotchas

- **Do not run both the UI path and the package's `person:` block.** Two persons
  named Guest means the second becomes `person.guest_2`. Pick one; if going the
  UI route, delete the `person:` and `template: - device_tracker:` blocks from
  the package.
- `sensor.gorcho_clients` is the *expected* entity ID but was never confirmed
  against the live instance. If another device is named Gorcho, HA may have
  slugified it to `sensor.gorcho_clients_2`. Check before wiring anything to it.
- The household condition tests "not home" rather than `== not_home` on purpose,
  so someone in a Work or School zone still counts as away.

## Running things locally

```sh
export HA_URL=http://homeassistant.local:8123
export HA_TOKEN=<long-lived token>    # Profile -> Security. Must be an admin user.

python3 homeassistant/scripts/create_guest_presence.py --dry-run
```

The script is standard-library only and idempotent. It never deletes anything.

Handy for poking at the live instance:

```sh
curl -sH "Authorization: Bearer $HA_TOKEN" "$HA_URL/api/states" \
  | python3 -c "import json,sys; [print(s['entity_id'], '=', s['state']) for s in json.load(sys.stdin) if 'gorcho' in s['entity_id'] or s['entity_id'].startswith('person.')]"
```

## Testing

There is no test runner in this repo. The scratchpad harnesses from the last
session (a mock Home Assistant speaking real REST + websocket, and a Groovy stub
harness for the SmartThings SmartApp) were not committed. If you change
`create_guest_presence.py`, test it against a real instance with `--dry-run`
first.
