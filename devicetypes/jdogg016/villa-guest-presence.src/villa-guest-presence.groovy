/**
 *  Villa Guest Presence
 *
 *  A virtual presence sensor that represents a "person" in the villa whose
 *  presence is driven by activity on the guest Wi-Fi network rather than by a
 *  phone or a presence fob.  It is normally created and driven by the
 *  "Villa Guest Wi-Fi Presence" SmartApp, but arrived()/departed() can be
 *  called from any SmartApp, Routine or rule.
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
metadata {
	definition (name: "Villa Guest Presence", namespace: "jdogg016", author: "Justin Bennett",
	            ocfDeviceType: "x.com.st.d.sensor.presence") {
		capability "Presence Sensor"
		capability "Sensor"
		capability "Health Check"

		attribute "guestCount", "number"
		attribute "lastSeen", "string"

		command "arrived"
		command "departed"
		command "setGuestCount", ["number"]
	}

	simulator {
		status "present": "presence: present"
		status "not present": "presence: not present"
	}

	tiles(scale: 2) {
		standardTile("presence", "device.presence", width: 4, height: 4, canChangeBackground: true) {
			state "not present", label: "Away", icon: "st.presence.tile.presence-default",
			      backgroundColor: "#ffffff", action: "arrived"
			state "present", label: "Present", icon: "st.presence.tile.presence-default",
			      backgroundColor: "#00a0dc", action: "departed"
		}
		valueTile("guestCount", "device.guestCount", width: 2, height: 2, decoration: "flat") {
			state "guestCount", label: '${currentValue} on Wi-Fi'
		}
		valueTile("lastSeen", "device.lastSeen", width: 4, height: 1, decoration: "flat") {
			state "lastSeen", label: 'Last seen: ${currentValue}'
		}

		main "presence"
		details(["presence", "guestCount", "lastSeen"])
	}
}

def installed() {
	initialize()
}

def updated() {
	initialize()
}

private initialize() {
	// Cloud/virtual device: enroll in Health Check but do not let it go offline.
	sendEvent(name: "DeviceWatch-Enroll",
	          value: groovy.json.JsonOutput.toJson([protocol: "cloud", scheme: "untracked"]),
	          displayed: false)
	sendEvent(name: "healthStatus", value: "online", displayed: false)

	if (!device.currentValue("presence")) {
		departed()
	}
	if (device.currentValue("guestCount") == null) {
		sendEvent(name: "guestCount", value: 0, displayed: false)
	}
}

// Health Check
def ping() {
	sendEvent(name: "presence", value: device.currentValue("presence") ?: "not present", displayed: false)
}

// Accepts simulator/manual events of the form "presence: present"
def parse(String description) {
	def parts = description?.split(":")
	if (parts?.size() == 2 && parts[0].trim() == "presence") {
		parts[1].trim() == "present" ? arrived() : departed()
	}
	return null
}

def arrived() {
	if (device.currentValue("presence") != "present") {
		log.info "${device.displayName} has arrived"
	}
	sendEvent(name: "presence", value: "present",
	          descriptionText: "${device.displayName} has arrived",
	          translatable: true)
	touch()
}

def departed() {
	if (device.currentValue("presence") == "present") {
		log.info "${device.displayName} has left"
	}
	sendEvent(name: "presence", value: "not present",
	          descriptionText: "${device.displayName} has left",
	          translatable: true)
	sendEvent(name: "guestCount", value: 0, displayed: false)
}

def setGuestCount(count) {
	def value = (count ?: 0) as Integer
	sendEvent(name: "guestCount", value: value, displayed: false)
	if (value > 0) {
		touch()
	}
}

private touch() {
	sendEvent(name: "lastSeen", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone),
	          displayed: false)
}
