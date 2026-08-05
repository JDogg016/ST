# Villa guest presence (Home Assistant)

Turns activity on the **Gorcho** guest Wi-Fi into a person called **Guest**, and
switches on **guest mode** when Guest is home and Matthew, Justin and Helen are
all away.

Everything lives in one package file: [`packages/villa_guest_presence.yaml`](packages/villa_guest_presence.yaml).

## 1. The UniFi counter

There is no separate "UniFi Counter" integration to install — the per-SSID
client counter is **built into the core UniFi Network integration**, so this is
a UI step rather than a HACS one:

**Settings → Devices & services → Add integration → UniFi Network**, point it at
your controller and pick your site.

The integration creates a device per WLAN, each with a `Clients` sensor holding
the number of clients currently associated with that SSID. It is enabled by
default. For the Gorcho WLAN that gives you:

```
sensor.gorcho_clients
```

Check the exact entity ID in **Developer tools → States** before going further —
if you have another device called Gorcho, HA may have slugified it to
`sensor.gorcho_clients_2`. If it differs, change the one reference to it at the
top of the package file.

> The UniFi sensor counts clients whose ESSID matches *and* whose last-seen
> timestamp is within the integration's detection time (**Configure → Detection
> time**, 300s by default). That is already a few minutes of stickiness before
> anything in this package gets involved.

## 2a. The quick path (no YAML)

Everything below can be done from the UI instead, which is the fastest way to
get a working `person.guest`. You lose only the extra grace period.

1. **Settings → Devices & services → Helpers → Create helper → Template →
   Template a device tracker**
   - Name: `Guest WiFi`
   - In zones:
     ```jinja
     {{ ['zone.home'] if states('sensor.gorcho_clients') | int(0) > 0 else [] }}
     ```
2. **Settings → People → Add person**, name it `Guest`, and select
   `device_tracker.guest_wifi` under "Select the device trackers for this
   person". The helper has to exist first.

UniFi's own detection time (300s by default) is already acting as a grace
period here, so presence will not flap when a phone sleeps.

Do **not** combine this with the package's `person:` and
`template: - device_tracker:` blocks — you would end up with two persons named
Guest and the second would become `person.guest_2`. If you later want the
longer grace period, install the package with those two blocks deleted and
repoint the helper's `in_zones` at `binary_sensor.gorcho_guests_online`.

## 2b. Install the package

Copy `packages/villa_guest_presence.yaml` into `<config>/packages/`, and make
sure `configuration.yaml` has:

```yaml
homeassistant:
  packages: !include_dir_named packages
```

Then **Developer tools → YAML → Check configuration**, and restart.

Requires **Home Assistant 2026.6 or newer** — that is when the `template`
integration gained a `device_tracker` platform, which is what lets a person be
driven from a template without MQTT or the deprecated `device_tracker.see`.
See [older versions](#older-home-assistant-versions) if you are behind.

## What it creates

| Entity | What it is |
| --- | --- |
| `sensor.gorcho_guest_client_count` | The Gorcho client count, with unknown/unavailable coerced to `0` so nothing downstream ever sees a non-numeric state |
| `binary_sensor.gorcho_guests_online` | `on` when the count is > 0. Carries the grace period (`delay_off`) |
| `device_tracker.guest_wifi` | `home` while guests are online, `not_home` otherwise |
| `person.guest` | The person, backed by that tracker |
| `input_boolean.guest_mode` | Guest mode |
| `automation.villa_guest_mode_when_guest_is_home_alone` | Drives guest mode |

Flow:

```
sensor.gorcho_clients (UniFi)
  -> sensor.gorcho_guest_client_count      normalise
  -> binary_sensor.gorcho_guests_online    > 0, with 10 min delay_off
  -> device_tracker.guest_wifi             in_zones: [zone.home] | []
  -> person.guest                          home / not_home
  -> input_boolean.guest_mode              + household all away
```

`person.guest` is an ordinary person, so it shows on the map, in the people
card, and in any `condition: state` you already use for the household.

## Tuning

**Grace period.** `delay_off` on `binary_sensor.gorcho_guests_online` (default
10 minutes) is how long Gorcho must stay empty before Guest leaves. It stops a
phone that briefly drops Wi-Fi from flapping Guest away and back. Arrival has no
delay — the first client to associate marks Guest home on the next UniFi poll.

Total time from "last guest actually leaves" to "Guest goes away" is UniFi's
detection time **plus** this delay: about 15 minutes at the defaults. Shorten
both if that is too sluggish.

**Household.** The entity IDs `person.matthew`, `person.justin` and
`person.helen` are assumed. Confirm them in **Developer tools → States** and fix
the four references in the automation if yours differ.

## Guest mode turns itself off

You asked for guest mode to be *triggered*; the package also turns it off again
as soon as Guest leaves or anyone in the household comes home. A mode that can
only be switched on would latch forever after the first visitor. If you want it
to latch instead, delete the `default:` branch at the end of the automation.

Right now `input_boolean.guest_mode` is just a flag — nothing hangs off it yet.
Hang your actual guest behaviour (thermostat setpoints, lighting scenes, which
locks auto-lock, notifications you want suppressed) off it separately, or say
the word and I will wire that up too.

## Older Home Assistant versions

Below 2026.6 there is no template `device_tracker`. Two options:

- **MQTT** — if you have a broker, replace the `device_tracker:` block with an
  `mqtt device_tracker` and an automation that publishes `home` / `not_home` to
  its topic on `binary_sensor.gorcho_guests_online` state changes. Not
  deprecated, works on any version.
- **`device_tracker.see`** — an automation calling
  `device_tracker.see` with `dev_id: guest_wifi` and
  `location_name: home` / `not_home`. Simpler, but it is deprecated and is
  scheduled for removal in **2027.5**, so it is a stopgap rather than a
  destination.

## Note on the rest of this repository

The `smartapps/` and `devicetypes/` folders hold an earlier SmartThings Groovy
version of the same idea (webhook-driven rather than UniFi-driven). It is
independent of this package — nothing here depends on it, and vice versa.
