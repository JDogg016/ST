/**
 *  Villa Guest Wi-Fi Presence
 *
 *  Creates a "person" for the villa (by default named "Guest") and marks that
 *  person present for as long as anybody is associated with the guest Wi-Fi
 *  network.  The router / access point / controller reports guest-network
 *  clients to this app over its REST endpoints; see README.md in this folder
 *  for ready-made recipes (UniFi, OpenWrt, DD-WRT, plain curl).
 *
 *  Two reporting styles are supported and may be mixed:
 *
 *    - Incremental:  call /clients/:id/connected when a client associates and
 *                    /clients/:id/disconnected when it leaves.
 *    - Snapshot:     call /clients with the full list of currently associated
 *                    clients, as often as you like.  Anything missing from the
 *                    list is treated as gone immediately.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
definition(
	name: "Villa Guest Wi-Fi Presence",
	namespace: "jdogg016",
	author: "Justin Bennett",
	description: "Creates a Guest person for the villa and marks them present whenever anyone is on the guest Wi-Fi.",
	category: "Convenience",
	iconUrl: "https://s3.amazonaws.com/smartapp-icons/Presence/Cat-Presence.png",
	iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Presence/Cat-Presence@2x.png",
	iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Presence/Cat-Presence@2x.png",
	singleInstance: true,
	oauth: [displayName: "Villa Guest Wi-Fi Presence", displayLink: ""])

preferences {
	page(name: "mainPage")
	page(name: "endpointPage")
}

def mainPage() {
	dynamicPage(name: "mainPage", title: "Villa Guest Wi-Fi Presence", install: true, uninstall: true) {
		section("The person") {
			input "personName", "text", title: "Name this person", defaultValue: "Guest", required: true
			if (guestDevice) {
				paragraph "${guestDevice.displayName} is currently " +
				          "${guestDevice.currentValue("presence") == "present" ? "present" : "away"}" +
				          " (${activeClients().size()} client(s) on the guest Wi-Fi)."
			} else {
				paragraph "A presence device will be created when you tap Done."
			}
		}
		section("Timing") {
			input "awayAfterMinutes", "number", title: "Mark away this many minutes after the last client leaves",
			      defaultValue: 10, required: true
			input "clientTimeoutMinutes", "number",
			      title: "Forget a client we have not heard from in this many minutes",
			      description: "Only used when your router reports connects but not disconnects",
			      defaultValue: 30, required: true
		}
		section("Exclusions") {
			input "ignoredClients", "text",
			      title: "Ignore these MACs / client names (comma separated)",
			      description: "e.g. villa hardware parked on the guest SSID",
			      required: false
		}
		section("Router hookup") {
			href name: "toEndpointPage", page: "endpointPage", title: "Show the URLs for my router",
			     description: "Endpoint address and access token"
		}
		section("Logging") {
			input "debugLogging", "bool", title: "Enable debug logging", defaultValue: false, required: false
		}
	}
}

def endpointPage() {
	dynamicPage(name: "endpointPage", title: "Router hookup", install: false, uninstall: false) {
		if (!ensureAccessToken()) {
			section {
				paragraph "OAuth is not enabled for this SmartApp. In the IDE open " +
				          "My SmartApps -> Villa Guest Wi-Fi Presence -> App Settings -> OAuth -> " +
				          "Enable OAuth in Smart App, save, then come back here."
			}
			return
		}

		def base = "${apiServerUrl("/api/smartapps/installations/${app.id}")}"
		def token = "access_token=${state.accessToken}"

		section("A client joined the guest Wi-Fi") {
			paragraph "${base}/clients/<MAC>/connected?${token}"
		}
		section("A client left the guest Wi-Fi") {
			paragraph "${base}/clients/<MAC>/disconnected?${token}"
		}
		section("Full list of connected clients (snapshot)") {
			paragraph "${base}/clients?macs=<MAC>,<MAC>&${token}"
			paragraph "Send macs= with an empty value when nobody is connected."
		}
		section("Current status") {
			paragraph "${base}/status?${token}"
		}
	}
}

mappings {
	path("/clients/:id/connected")    { action: [GET: "clientConnected",    POST: "clientConnected"] }
	path("/clients/:id/disconnected") { action: [GET: "clientDisconnected", POST: "clientDisconnected"] }
	path("/clients")                  { action: [GET: "clientSnapshot", POST: "clientSnapshot", PUT: "clientSnapshot"] }
	path("/status")                   { action: [GET: "statusRequest"] }
}

def installed() {
	initialize()
}

def updated() {
	unsubscribe()
	unschedule()
	initialize()
}

def uninstalled() {
	getChildDevices()?.each { deleteChildDevice(it.deviceNetworkId) }
}

private initialize() {
	if (state.clients == null) state.clients = [:]
	ensureAccessToken()
	ensureGuestDevice()
	runEvery1Minute("sweep")
	evaluatePresence()
}

// ---------------------------------------------------------------------------
// The person
// ---------------------------------------------------------------------------

private getGuestDevice() {
	getChildDevice(guestDeviceNetworkId)
}

private getGuestDeviceNetworkId() {
	"villa-guest-presence-${app.id}"
}

private ensureGuestDevice() {
	def child = guestDevice
	if (!child) {
		log.info "Creating villa presence device \"${personName}\""
		child = addChildDevice("jdogg016", "Villa Guest Presence", guestDeviceNetworkId, null,
		                       [name: "Villa Guest Presence", label: personName ?: "Guest", completedSetup: true])
	} else if (personName && child.displayName != personName) {
		child.setLabel(personName)
	}
	child
}

// ---------------------------------------------------------------------------
// REST endpoints
// ---------------------------------------------------------------------------

def clientConnected() {
	def id = normalize(params.id)
	if (!id) httpError(400, "A client MAC or name is required")

	if (isIgnored(id)) {
		logDebug "Ignoring connect from ${id}"
		return statusPayload()
	}

	logDebug "Client ${id} joined the guest Wi-Fi"
	state.clients[id] = now()
	evaluatePresence()
	statusPayload()
}

def clientDisconnected() {
	def id = normalize(params.id)
	if (!id) httpError(400, "A client MAC or name is required")

	logDebug "Client ${id} left the guest Wi-Fi"
	state.clients.remove(id)
	evaluatePresence()
	statusPayload()
}

def clientSnapshot() {
	def reported = []
	def body = request?.JSON

	// A snapshot has to be explicit about who is connected, otherwise a bare
	// GET /clients (a read, not a report) would wipe the list.  An empty
	// macs= is a legitimate snapshot meaning "nobody is connected".
	def hasQuery = params.containsKey("macs") || params.containsKey("clients")
	if (!hasQuery && !body) {
		return statusPayload()
	}

	// ?macs=aa:bb:cc:dd:ee:ff,11:22:33:44:55:66  (also accepts ?clients=)
	def query = params.macs ?: params.clients
	if (query) {
		reported += query.tokenize(",")
	}
	// or a JSON body: {"macs": ["aa:bb:...", ...]}
	if (body) {
		def fromBody = body instanceof List ? body : (body.macs ?: body.clients)
		if (fromBody) reported += (fromBody instanceof List ? fromBody : [fromBody])
	}

	def timestamp = now()
	def clients = [:]
	reported.collect { normalize(it) }.findAll { it && !isIgnored(it) }.each { clients[it] = timestamp }

	logDebug "Guest Wi-Fi snapshot: ${clients.keySet()}"
	state.clients = clients
	evaluatePresence()
	statusPayload()
}

def statusRequest() {
	statusPayload()
}

private statusPayload() {
	def child = guestDevice
	[
		person:  child?.displayName ?: personName,
		present: child?.currentValue("presence") == "present",
		clients: activeClients().keySet() as List
	]
}

// ---------------------------------------------------------------------------
// Presence logic
// ---------------------------------------------------------------------------

def sweep() {
	evaluatePresence()
}

private evaluatePresence() {
	def child = ensureGuestDevice()
	def active = activeClients()
	state.clients = active

	if (active) {
		state.emptySince = null
		child.setGuestCount(active.size())
		if (child.currentValue("presence") != "present") {
			log.info "${active.size()} client(s) on the guest Wi-Fi - marking ${child.displayName} present"
			child.arrived()
		}
		return
	}

	if (child.currentValue("presence") != "present") {
		state.emptySince = null
		return
	}

	if (!state.emptySince) {
		state.emptySince = now()
		logDebug "Guest Wi-Fi is empty, starting the ${awayGraceMinutes} minute grace period"
		return
	}

	if (now() - (state.emptySince as Long) >= awayGraceMinutes * 60000L) {
		log.info "Guest Wi-Fi empty for ${awayGraceMinutes} minutes - marking ${child.displayName} away"
		state.emptySince = null
		child.departed()
	}
}

// Clients we have heard from recently enough to still count as connected.
private activeClients() {
	def cutoff = now() - (staleMinutes * 60000L)
	(state.clients ?: [:]).findAll { id, lastSeen -> (lastSeen as Long) >= cutoff }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private getAwayGraceMinutes() {
	(awayAfterMinutes ?: 10) as Integer
}

private getStaleMinutes() {
	(clientTimeoutMinutes ?: 30) as Integer
}

private getIgnoredList() {
	(ignoredClients ?: "").tokenize(",").collect { normalize(it) }.findAll { it }
}

private isIgnored(id) {
	ignoredList.contains(id)
}

// MACs arrive as AA:BB:CC:DD:EE:FF, aa-bb-cc-dd-ee-ff or aabbccddeeff depending
// on the router, so fold them all onto one form.  Anything that is not a MAC
// (a hostname, a UniFi client name) is just lower-cased.
private normalize(value) {
	def id = value?.toString()?.trim()?.toLowerCase()
	if (!id) return null

	def hex = id.replaceAll("[^0-9a-f]", "")
	if (hex.length() == 12 && id.replaceAll("[0-9a-f:.\\-]", "").isEmpty()) {
		return hex.toList().collate(2)*.join().join(":")
	}
	id
}

private ensureAccessToken() {
	if (!state.accessToken) {
		try {
			createAccessToken()
		} catch (e) {
			log.warn "Could not create an access token, is OAuth enabled for this SmartApp? ${e}"
		}
	}
	state.accessToken as Boolean
}

private logDebug(msg) {
	if (debugLogging) log.debug msg
}
