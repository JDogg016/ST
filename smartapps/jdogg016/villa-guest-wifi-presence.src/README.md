# Villa Guest Wi-Fi Presence

Creates a person for the villa — named **Guest** by default — and marks that
person present for as long as anybody is associated with the guest Wi-Fi
network. When the guest network empties out and stays empty for the grace
period, Guest is marked away.

Once installed, `Guest` behaves like any other presence sensor: use it in
Routines, Smart Lighting, thermostat rules, "when everyone leaves" automations,
and so on.

## Install

1. In the IDE, add the device handler
   `devicetypes/jdogg016/villa-guest-presence.src/villa-guest-presence.groovy`
   and **Publish → For Me**.
2. Add the SmartApp
   `smartapps/jdogg016/villa-guest-wifi-presence.src/villa-guest-wifi-presence.groovy`
   and **Publish → For Me**.
3. Open the SmartApp in the IDE → **App Settings** → **OAuth** →
   **Enable OAuth in Smart App** → **Update**.
4. Install the SmartApp from the phone app (Marketplace → SmartApps → My Apps).
   It creates the `Guest` presence device on the way out.
5. Re-open the SmartApp → **Show the URLs for my router** and copy the
   endpoint address and access token.

## Settings

| Setting | Default | What it does |
| --- | --- | --- |
| Name this person | `Guest` | Label of the created presence device |
| Mark away after | 10 min | Grace period after the last client leaves, so a phone that drops Wi-Fi for a moment does not flap the person away and back |
| Forget a client after | 30 min | Only matters if your router reports connects but never disconnects — a client we have not heard about in this long is assumed gone |
| Ignore these MACs / names | — | Villa-owned hardware parked on the guest SSID that should not count as a guest |

## Endpoints

`BASE` is `https://<your-shard>.api.smartthings.com/api/smartapps/installations/<app-id>`,
shown on the "Show the URLs for my router" page along with the token.

| Call | Meaning |
| --- | --- |
| `GET BASE/clients/<mac>/connected?access_token=<token>` | This client just joined the guest Wi-Fi |
| `GET BASE/clients/<mac>/disconnected?access_token=<token>` | This client just left |
| `GET BASE/clients?macs=<mac>,<mac>&access_token=<token>` | Full list of who is connected right now — anything not listed is treated as gone |
| `GET BASE/clients?macs=&access_token=<token>` | Nobody is connected right now |
| `GET BASE/status?access_token=<token>` | Read current state, changes nothing |

The MAC can be written `AA:BB:CC:DD:EE:FF`, `aa-bb-cc-dd-ee-ff` or
`aabbccddeeff` — they are all folded onto the same identifier. If your gear
only gives you hostnames or client names, those work too; anything that is not
a MAC is used as-is (lower-cased).

The snapshot endpoint also accepts a JSON body instead of the query string:

```
curl -X POST "$BASE/clients?access_token=$TOKEN" \
     -H 'Content-Type: application/json' \
     -d '{"macs": ["aa:bb:cc:dd:ee:ff", "11:22:33:44:55:66"]}'
```

Both styles can be mixed: incremental connect/disconnect calls for
responsiveness plus an occasional snapshot to resynchronise.

## Router recipes

Set `BASE` and `TOKEN` from the SmartApp's hookup page in each of these.

### UniFi (poll the controller from any always-on host)

Guest clients on a UniFi guest network come back from the controller's client
list; push the whole list as a snapshot every minute from cron.

```sh
#!/bin/sh
# /usr/local/bin/villa-guest-wifi.sh  —  run from cron: * * * * *
BASE="https://graph-na04-useast2.api.smartthings.com/api/smartapps/installations/<app-id>"
TOKEN="<token>"
SITE="default"
CONTROLLER="https://unifi.local:8443"

COOKIE=$(mktemp)
curl -sk --cookie-jar "$COOKIE" -H 'Content-Type: application/json' \
     -d '{"username":"<user>","password":"<pass>"}' \
     "$CONTROLLER/api/login" >/dev/null

MACS=$(curl -sk --cookie "$COOKIE" "$CONTROLLER/api/s/$SITE/stat/sta" \
       | tr ',' '\n' | grep -o '"mac":"[^"]*"' | cut -d'"' -f4 | paste -sd, -)

curl -s "$BASE/clients?macs=$MACS&access_token=$TOKEN" >/dev/null
rm -f "$COOKIE"
```

Filter by guest network first if your guest SSID shares the site — add
`| grep is_guest` logic, or query `stat/guest` instead of `stat/sta`.

### OpenWrt (event driven, guest AP interface)

`hostapd` fires `wireless.wlan-*` events for the guest interface. Drop this in
`/etc/hotplug.d/net/99-villa-guest` or run it from a `wifi-event` hook, with
`GUEST_IFACE` set to the guest AP interface (e.g. `wlan0-1`):

```sh
#!/bin/sh
BASE="https://graph-na04-useast2.api.smartthings.com/api/smartapps/installations/<app-id>"
TOKEN="<token>"
GUEST_IFACE="wlan0-1"

MACS=$(iw dev "$GUEST_IFACE" station dump | awk '/Station/ {print $2}' | paste -sd, -)
curl -s "$BASE/clients?macs=$MACS&access_token=$TOKEN" >/dev/null
```

Running the same script from cron every minute is the simplest reliable
setup — the snapshot endpoint is idempotent.

### DD-WRT

```sh
BASE="https://graph-na04-useast2.api.smartthings.com/api/smartapps/installations/<app-id>"
TOKEN="<token>"
MACS=$(wl -i ath1 assoclist | awk '{print $2}' | paste -sd, -)
curl -s "$BASE/clients?macs=$MACS&access_token=$TOKEN" >/dev/null
```

### Anything else

If your gear can only fire a single webhook when a device joins, use the
incremental endpoint and let the grace period handle departures:

```sh
curl -s "$BASE/clients/$MAC/connected?access_token=$TOKEN"
```

With no disconnect calls, a client is forgotten after the "Forget a client
after" window, and Guest goes away one grace period later.

## Verifying

Call the connect endpoint by hand with a made-up MAC and watch the device:

```sh
curl -s "$BASE/clients/aa:bb:cc:dd:ee:ff/connected?access_token=$TOKEN"
curl -s "$BASE/status?access_token=$TOKEN"
# {"person":"Guest","present":true,"clients":["aa:bb:cc:dd:ee:ff"]}

curl -s "$BASE/clients/aa:bb:cc:dd:ee:ff/disconnected?access_token=$TOKEN"
# Guest goes away once the grace period elapses
```

Turn on debug logging in the SmartApp to watch each client report in **Live
Logging**.
