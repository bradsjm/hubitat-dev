/**
 *  MIT License
 *  Copyright 2023 Jonathan Bradshaw (jb@nrgup.net)
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the 'Software'), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED 'AS IS', WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 *
 *  Thanks to the Zigbee2Mqtt and Dresden-elektronik teams for
 *  their existing work in decoding the Hue protocol.
 */

import groovy.transform.Field
import hubitat.zigbee.zcl.DataType
import java.math.RoundingMode

metadata {
    definition(name: 'Aqara Presence Sensor FP1',
            importUrl: 'https://raw.githubusercontent.com/bradsjm/hubitat-dev/main/Zigbee/AqaraPresenceSensorFP1.groovy',
            namespace: 'aqara', author: 'Jonathan Bradshaw') {
        capability 'Configuration'
        capability 'Health Check'
        capability 'Refresh'
        capability 'Sensor'

        attribute 'healthStatus', 'enum', [ 'unknown', 'offline', 'online' ]

        fingerprint model: 'lumi.motion.ac01', manufacturer: 'aqara', profileId: '0104', endpointId: '01', inClusters: '0000,0003,FCC0', outClusters: '0003,0019', application: '36'
    }

    preferences {
        input name: 'triggerDistance', type: 'enum', title: '<b>Trigger Distance</b>', options: TriggerDistanceOpts.options, defaultValue: TriggerDistanceOpts.defaultValue, description:\
            '<i>Detection distance for approaching sensor.</i>'

        input name: 'sensitivityLevel', type: 'enum', title: '<b>Motion Sensitivity</b>', options: SensitivityLevelOpts.options, defaultValue: SensitivityLevelOpts.defaultValue, description:\
            '<i>Motion detection sensitivity within detection zone.</i>'

        input name: 'healthCheckInterval', type: 'enum', title: '<b>Healthcheck Interval</b>', options: HealthcheckIntervalOpts.options, defaultValue: HealthcheckIntervalOpts.defaultValue, description:\
            '<i>Changes how often the hub pings sensor to check health.</i>'

        input name: 'txtEnable', type: 'bool', title: '<b>Enable descriptionText logging</b>', defaultValue: true, description:\
            '<i>Enables command logging.</i>'

        input name: 'logEnable', type: 'bool', title: '<b>Enable debug logging</b>', defaultValue: false, description:\
            '<i>Turns on debug logging for 30 minutes.</i>'
    }
}

@Field static final String VERSION = '0.1'

List<String> configure() {
    List<String> cmds = []
    log.info 'configure...'
    state.attributes = [:]

    // Set motion sensitivity
    cmds += zigbee.writeAttribute(PRIVATE_CLUSTER_ID, SENSITIVITY_LEVEL_ATTR_ID, DataType.UINT8, settings.sensitivityLevel as Integer, [:], DELAY_MS)

    // Set trigger distance
    cmds += zigbee.writeAttribute(PRIVATE_CLUSTER_ID, TRIGGER_DISTANCE_ATTR_ID, DataType.UINT8, settings.triggerDistance as Integer, [:], DELAY_MS)

    // Enable left right detection
    cmds += zigbee.writeAttribute(PRIVATE_CLUSTER_ID, DIRECTION_MODE_ATTR_ID, DataType.UINT8, 0x01, [:], DELAY_MS)

    if (settings.logEnable) { log.debug "zigbee configure cmds: ${cmds}" }

    runIn(2, 'refresh')
    return cmds
}

void deviceCommandTimeout() {
    log.warn 'no response received (device offline?)'
    updateAttribute('healthStatus', 'offline')
}

void installed() {
    log.info 'installed'
    // populate some default values for attributes
    sendEvent(name: 'healthStatus', value: 'unknown')
}

void logsOff() {
    log.warn 'debug logging disabled...'
    device.updateSetting('logEnable', [value: 'false', type: 'bool'])
}

List<String> ping() {
    if (settings.txtEnable) { log.info 'ping...' }
    // Using attribute 0x00 as a simple ping/pong mechanism
    scheduleCommandTimeoutCheck()
    return zigbee.readAttribute(zigbee.BASIC_CLUSTER, PING_ATTR_ID, [:], 0)
}

List<String> refresh() {
    log.info 'refresh'
    List<String> cmds = []

    // Get configuration
    cmds += zigbee.readAttribute(PRIVATE_CLUSTER_ID, [
        SENSITIVITY_LEVEL_ATTR_ID,
        TRIGGER_DISTANCE_ATTR_ID
    ], [:], DELAY_MS)
    scheduleCommandTimeoutCheck()
    return cmds
}

void updated() {
    log.info 'updated...'
    log.info "driver version ${VERSION}"
    unschedule()

    if (settings.logEnable) {
        log.debug settings
        runIn(1800, logsOff)
    }

    int interval = (settings.healthCheckInterval as Integer) ?: 0
    if (interval > 0) {
        log.info "scheduling health check every ${interval} minutes"
        scheduleDeviceHealthCheck(interval)
    }

    runIn(1, 'configure')
}

void parse(String description) {
    Map descMap = zigbee.parseDescriptionAsMap(description)
    updateAttribute('healthStatus', 'online')
    unschedule('deviceCommandTimeout')

    if (descMap.isClusterSpecific == false) {
        if (settings.logEnable) { log.trace "zigbee received message ${descMap}" }
        parseGlobalCommands(descMap)
        return
    }

    if (settings.logEnable) {
        String clusterName = clusterLookup(descMap.clusterInt)
        String attribute = descMap.attrId ? " attribute 0x${descMap.attrId} (value ${descMap.value})" : ''
        if (settings.logEnable) { log.trace "zigbee received ${clusterName} message" + attribute }
    }

    switch (descMap.clusterInt as Integer) {
        case zigbee.BASIC_CLUSTER:
            parseBasicCluster(descMap)
            descMap.additionalAttrs?.each { m -> parseBasicCluster(m) }
            break
        case PRIVATE_CLUSTER_ID:
            parsePrivateCluster(descMap)
            descMap.additionalAttrs?.each { m -> parsePrivateCluster(m) }
            break
        default:
            if (settings.logEnable) {
                log.debug "zigbee received unknown message cluster: ${descMap}"
            }
            break
    }
}

/*
 * Zigbee Basic Cluster Parsing
 */
void parseBasicCluster(Map descMap) {
    switch (descMap.attrInt as Integer) {
        case PING_ATTR_ID: // Using 0x01 read as a simple ping/pong mechanism
            if (settings.txtEnable) { log.info 'pong..' }
            break
        default:
            log.warn "zigbee received unknown ${clusterLookup(descMap.clusterInt)}: ${descMap}"
            break
    }
}

/*
 * Zigbee Global Command Parsing
 */
void parseGlobalCommands(Map descMap) {
    switch (hexStrToUnsignedInt(descMap.command)) {
        case 0x04: // write attribute response
            int statusCode = hexStrToUnsignedInt(descMap.data in List ? descMap.data[0] : descMap.data)
            String status = "0x${intToHexStr(statusCode)}"
            if (settings.logEnable) {
                log.trace "zigbee response write ${clusterLookup(descMap.clusterInt)} attribute response: ${status}"
            } else if (statusCode != 0x00) {
                log.warn "zigbee response write ${clusterLookup(descMap.clusterInt)} attribute error: ${status}"
            }
            break
        case 0x07: // configure reporting response
            log.info "reporting for ${clusterLookup(descMap.clusterInt)} enabled sucessfully"
            break
        case 0x0B: // default command response
            String commandId = descMap.data[0]
            int statusCode = hexStrToUnsignedInt(descMap.data[1])
            String status = "0x${descMap.data[1]}"
            if (settings.logEnable) {
                log.trace "zigbee command status ${clusterLookup(descMap.clusterInt)} command 0x${commandId}: ${status}"
            } else if (statusCode != 0x00) {
                log.warn "zigbee command error (${clusterLookup(descMap.clusterInt)}, command: 0x${commandId}) ${status}"
            }
            break
    }
}

/*
 * Zigbee Private Cluster Parsing
 */
void parsePrivateCluster(Map descMap) {
    switch (descMap.attrInt as Integer) {
        case SENSITIVITY_LEVEL_ATTR_ID:
            Integer value = hexStrToUnsignedInt(descMap.value)
            log.info "sensitivity level is '${SensitivityLevelOpts.options[value]}' (0x${descMap.value})"
            device.updateSetting('sensitivityLevel', [value: value.toString(), type: 'enum' ])
            break
        case TRIGGER_DISTANCE_ATTR_ID:
            Integer value = hexStrToUnsignedInt(descMap.value)
            log.info "trigger distance is '${TriggerDistanceOpts.options[value]}' (0x${descMap.value})"
            device.updateSetting('triggerDistance', [value: value.toString(), type: 'enum' ])
            break
        default:
            log.warn "zigbee received unknown ${clusterLookup(descMap.clusterInt)}: ${descMap}"
            break
    }
}

private String clusterLookup(Object cluster) {
    int clusterInt = cluster in String ? hexStrToUnsignedInt(cluster) : cluster.toInteger()
    String label = zigbee.clusterLookup(clusterInt)?.clusterLabel
    String hex = "0x${intToHexStr(clusterInt, 2)}"
    return label ? "${label} (${hex}) cluster" : "cluster ${hex}"
}

private void scheduleCommandTimeoutCheck(int delay = COMMAND_TIMEOUT) {
    runIn(delay, 'deviceCommandTimeout')
}

private void scheduleDeviceHealthCheck(int intervalMins) {
    Random rnd = new Random()
    schedule("${rnd.nextInt(59)} ${rnd.nextInt(9)}/${intervalMins} * ? * * *", 'ping')
}

private void updateAttribute(String attribute, Object value, String unit = null, String type = null) {
    String descriptionText = "${attribute} was set to ${value}${unit ?: ''}"
    if (device.currentValue(attribute) != value && settings.txtEnable) {
        log.info descriptionText
    }
    sendEvent(name: attribute, value: value, unit: unit, type: type, descriptionText: descriptionText)
}

// Zigbee Attribute IDs
@Field static final int DIRECTION_MODE_ATTR_ID = 0x0144
@Field static final int PING_ATTR_ID = 0x01
@Field static final int PRESENCE_ATTR_ID = 0x0143
@Field static final int PRIVATE_CLUSTER_ID = 0xFCC0
@Field static final int SENSITIVITY_LEVEL_ATTR_ID = 0x010C
@Field static final int TRIGGER_DISTANCE_ATTR_ID = 0x0146
@Field static final int XIAOMI_VENDOR = 0x1037

@Field static Map TriggerDistanceOpts = [
    defaultValue: 0x00,
    options: [ 0x00: 'Far', 0x01: 'Medium', 0x02: 'Near' ]
]

@Field static Map SensitivityLevelOpts = [
    defaultValue: 0x03,
    options: [ 0x01: 'Low', 0x02: 'Medium', 0x03: 'High' ]
]

@Field static Map HealthcheckIntervalOpts = [
    defaultValue: 10,
    options: [ 10: 'Every 10 Mins', 15: 'Every 15 Mins', 30: 'Every 30 Mins', 45: 'Every 45 Mins', 59: 'Every Hour', 00: 'Disabled' ]
]

// Command timeout before setting healthState to offline
@Field static final int COMMAND_TIMEOUT = 10

// Delay inbetween zigbee commands
@Field static final int DELAY_MS = 200
