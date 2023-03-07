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
 */

import groovy.transform.Field
import hubitat.zigbee.zcl.DataType

metadata {
    definition(name: 'SmartWings Motorized Shades',
            importUrl: 'https://raw.githubusercontent.com/bradsjm/hubitat-dev/main/Shades/SmartWings.groovy',
            namespace: 'smartwings', author: 'Jonathan Bradshaw') {
        capability 'Actuator'
        capability 'Battery'
        capability 'Configuration'
        capability 'Health Check'
        capability 'Refresh'
        capability 'Sensor'
        capability 'Window Shade'

        attribute 'healthStatus', 'enum', [ 'unknown', 'offline', 'online' ]

        fingerprint profileId: '0104', inClusters: '0000,0001,0003,0004,0005,0102', outClusters: '0003,0019', manufacturer: 'Smartwings', model: 'WM25/L-Z'
    }

    preferences {
        input name: 'healthCheckInterval', type: 'enum', title: '<b>Healthcheck Interval</b>', options: HealthcheckIntervalOpts.options, defaultValue: HealthcheckIntervalOpts.defaultValue, description:\
            '<i>Changes how often the hub pings shades to check health.</i>'

        input name: 'invertCommands', type: 'bool', title: '<b>Invert Commands</b>', defaultValue: false, required: true, description:\
            '<i>Inverts open and close commands.</i>'

        input name: 'invertPosition', type: 'bool', title: '<b>Invert position</b>', defaultValue: false, required: true, description:\
            '<i>Inverts position value (0 becomes 100).</i>'

        input name: 'txtEnable', type: 'bool', title: '<b>Enable descriptionText logging</b>', defaultValue: true, description:\
            '<i>Enables command logging.</i>'

        input name: 'logEnable', type: 'bool', title: '<b>Enable debug logging</b>', defaultValue: false, description:\
            '<i>Turns on debug logging for 30 minutes.</i>'
    }
}

List<String> close() {
    log.info 'close...'
    updateAttribute('windowShade', 'closing')
    scheduleCommandTimeoutCheck()
    if (settings.invertCommands) {
        return zigbee.command(CLUSTER_WINDOW_COVERING, OPEN_CMD_ID)
    }
    return zigbee.command(CLUSTER_WINDOW_COVERING, CLOSE_CMD_ID)
}

List<String> configure() {
    List<String> cmds = []
    log.info 'configure...'

    cmds += zigbee.configureReporting(CLUSTER_WINDOW_COVERING, WINDOW_COVERING_LIFT_PERCENT_ID, DataType.UINT8, 1, 3600, 0x01)
    cmds += zigbee.configureReporting(zigbee.POWER_CONFIGURATION_CLUSTER, BATTERY_PERCENT_REMAINING_ID, DataType.UINT8, 30, 21600, 0x02)

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
    sendEvent(name: 'windowShade', value: 'unknown')
}

void logsOff() {
    log.warn 'debug logging disabled...'
    device.updateSetting('logEnable', [value: 'false', type: 'bool'])
}

List<String> open() {
    log.info 'open...'
    updateAttribute('windowShade', 'opening')
    scheduleCommandTimeoutCheck()
    if (settings.invertCommands) {
        return zigbee.command(CLUSTER_WINDOW_COVERING, CLOSE_CMD_ID)
    }
    return zigbee.command(CLUSTER_WINDOW_COVERING, OPEN_CMD_ID)
}

void parse(String description) {
    Map descMap = zigbee.parseDescriptionAsMap(description)
    updateAttribute('healthStatus', 'online')
    unschedule('deviceCommandTimeout')

    if (descMap.isClusterSpecific == false) {
        if (settings.logEnable) { log.trace "zigbee received global message ${descMap}" }
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
            descMap.remove('additionalAttrs')?.each { m -> parseBasicCluster(descMap + m) }
            break
        case zigbee.POWER_CONFIGURATION_CLUSTER:
            parsePowerConfigurationCluster(descMap)
            descMap.remove('additionalAttrs')?.each { m -> parsePowerConfigurationCluster(descMap + m) }
            break
        case CLUSTER_WINDOW_COVERING:
            parseWindowCoveringCluster(descMap)
            descMap.remove('additionalAttrs')?.each { m -> parseWindowCoveringCluster(descMap + m) }
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
        case FIRMWARE_VERSION_ID:
            String version = descMap.value ?: 'unknown'
            log.info "device firmware version is ${version}"
            updateDataValue('softwareBuild', version)
            break
        default:
            log.warn "zigbee received unknown Basic cluster attribute 0x${descMap.attrId} (value ${descMap.value})"
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
 * Zigbee Power Configuration Cluster Parsing
 */
void parsePowerConfigurationCluster(Map descMap) {
    switch (descMap.attrInt as Integer) {
        case BATTERY_PERCENT_REMAINING_ID:
            int value = hexStrToUnsignedInt(descMap.value)
            if (value < 255) {
                int batteryLevel = Math.min(100, Math.max(0, value))
                updateAttribute('battery', batteryLevel, '%')
            }
            break
        default:
            log.warn "zigbee received unknown Power Configuration cluster attribute 0x${descMap.attrId} (value ${descMap.value})"
            break
    }
}

/*
 * Zigbee Window Covering Cluster Parsing
 */
void parseWindowCoveringCluster(Map descMap) {
    switch (descMap.attrInt as Integer) {
        case WINDOW_COVERING_LIFT_PERCENT_ID:
            int value = hexStrToUnsignedInt(descMap.value)
            int position = Math.min(100, Math.max(0, value))
            switch (position) {
                case 0:
                    updateAttribute('windowShade', 'open')
                    break
                case 100:
                    updateAttribute('windowShade', 'closed')
                    break
                default:
                    updateAttribute('windowShade', 'partially open')
                    break
            }
            if (settings.invertPosition) {
                position = 100 - position
            }
            updateAttribute('position', position, '%')
            break
        default:
            log.warn "zigbee received unknown Window Covering cluster attribute 0x${descMap.attrId} (value ${descMap.value})"
            break
    }
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

    // Get Firmware Version
    cmds += zigbee.readAttribute(zigbee.BASIC_CLUSTER, FIRMWARE_VERSION_ID, [:], DELAY_MS)

    // Get Battery Percent
    cmds += zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, BATTERY_PERCENT_REMAINING_ID, [:], DELAY_MS)

    // Get Position
    cmds += zigbee.readAttribute(CLUSTER_WINDOW_COVERING, WINDOW_COVERING_LIFT_PERCENT_ID, [:], 0)

    scheduleCommandTimeoutCheck()
    return cmds
}

List<String> setPosition(Object value, Object duration = null) {
    log.info "setPosition(${value}, ${duration})"
    int position = Math.min(100, Math.max(0, value.toInteger()))
    if (settings.invertPosition) {
        position = 100 - position
    }
    String positionHex = zigbee.convertToHexString(position, 2)
    updateAttribute('windowShade', 'partially open')
    return zigbee.command(CLUSTER_WINDOW_COVERING, GOTO_POSITION_CMD_ID, positionHex)
}

List<String> startPositionChange(String direction) {
    log.info "startPositionChange(${direction})"
    switch (direction) {
        case 'open': return open()
        case 'close': return close()
    }
}

List<String> stopPositionChange() {
    log.info 'stopPositionChange...'
    scheduleCommandTimeoutCheck()
    return zigbee.command(CLUSTER_WINDOW_COVERING, STOP_CMD_ID)
}

void updated() {
    log.info 'updated...'
    log.info "driver version ${VERSION}"
    unschedule()
    state.clear()

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

@Field static Map HealthcheckIntervalOpts = [
    defaultValue: 59,
    options: [ 10: 'Every 10 Mins', 15: 'Every 15 Mins', 30: 'Every 30 Mins', 45: 'Every 45 Mins', 59: 'Every Hour', 00: 'Disabled' ]
]

// Command timeout before setting healthState to offline
@Field static final int COMMAND_TIMEOUT = 10

// Delay inbetween zigbee commands
@Field static final int DELAY_MS = 200

// Commands IDs
@Field static final int OPEN_CMD_ID = 0x00
@Field static final int CLOSE_CMD_ID = 0x01
@Field static final int STOP_CMD_ID = 0x02
@Field static final int GOTO_POSITION_CMD_ID = 0x05

// Attribute IDs
@Field static final int BATTERY_PERCENT_REMAINING_ID = 0x0021
@Field static final int FIRMWARE_VERSION_ID = 0x4000
@Field static final int PING_ATTR_ID = 0x01
@Field static final int WINDOW_COVERING_LIFT_PERCENT_ID = 0x0008

// Cluster IDs
@Field static final int CLUSTER_WINDOW_COVERING = 0x0102
