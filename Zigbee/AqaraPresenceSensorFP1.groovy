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
 *  LIABILITY, WHETHER IN AN activity OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 *
 *  Thanks to the Zigbee2Mqtt and Dresden-elektronik teams for
 *  their existing work in decoding the Hue protocol.
 */

import groovy.transform.Field
import hubitat.helper.HexUtils
import hubitat.zigbee.zcl.DataType

metadata {
    definition(name: 'Aqara Presence Sensor FP1',
            importUrl: 'https://raw.githubusercontent.com/bradsjm/hubitat-dev/main/Zigbee/AqaraPresenceSensorFP1.groovy',
            namespace: 'aqara', author: 'Jonathan Bradshaw') {
        capability 'Configuration'
        capability 'Health Check'
        capability 'Motion Sensor'
        capability 'Presence Sensor'
        capability 'Refresh'
        capability 'Sensor'

        command 'resetPresence'
        command 'removeAllRegions'
        command 'removeInterferenceRegion'
        command 'removeRegion', [
            [ name: 'Region Id*', type: 'NUMBER', description: 'Region ID (1-10)' ]
        ]
        command 'setRegion', [
            [ name: 'Region Id*', type: 'NUMBER', description: 'Region ID (1-10)' ],
            [ name: 'Horizontal*', type: 'STRING', description: 'Left, Right (where 1 is left and 4 is right)' ],
            [ name: 'Vertical*', type: 'STRING', description: 'Top, Bottom (where 1 is top and 7 is bottom)' ],
        ]
        command 'setInterferenceRegion', [
            [ name: 'Horizontal*', type: 'STRING', description: 'Left, Right (where 1 is left and 4 is right)' ],
            [ name: 'Vertical*', type: 'STRING', description: 'Top, Bottom (where 1 is top and 7 is bottom)' ]
        ]

        attribute 'healthStatus', 'enum', [ 'unknown', 'offline', 'online' ]
        attribute 'activity', 'enum', [ 'enter', 'leave', 'enter left', 'leave right', 'enter right', 'leave left', 'approach', 'away' ]
        attribute 'regionNumber', 'number'
        attribute 'regionAction', 'enum', [ 'enter', 'leave', 'occupied', 'unoccupied' ]

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
    state.clear()

    // Set motion sensitivity
    if (settings.sensitivityLevel) {
        cmds += zigbee.writeAttribute(XIAOMI_CLUSTER_ID, SENSITIVITY_LEVEL_ATTR_ID, DataType.UINT8, settings.sensitivityLevel as Integer, [:], DELAY_MS)
    }

    // Set trigger distance
    if (settings.triggerDistance) {
        cmds += zigbee.writeAttribute(XIAOMI_CLUSTER_ID, TRIGGER_DISTANCE_ATTR_ID, DataType.UINT8, settings.triggerDistance as Integer, [:], DELAY_MS)
    }

    // Enable left right detection
    cmds += zigbee.writeAttribute(XIAOMI_CLUSTER_ID, DIRECTION_MODE_ATTR_ID, DataType.UINT8, 0x01, [:], DELAY_MS)

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
    sendEvent(name: 'activity', value: 'leave')
    sendEvent(name: 'motion', value: 'inactive')
    sendEvent(name: 'presence', value: 'not present')
}

void logsOff() {
    log.warn 'debug logging disabled...'
    device.updateSetting('logEnable', [value: 'false', type: 'bool'])
}

void parse(String description) {
    Map descMap = zigbee.parseDescriptionAsMap(description)
    updateAttribute('healthStatus', 'online')
    unschedule('deviceCommandTimeout')

    if (descMap.isClusterSpecific == false) {
        if (settings.logEnable) { log.trace "zigbee received global command message ${descMap}" }
        parseGlobalCommands(descMap)
        return
    }

    switch (descMap.clusterInt as Integer) {
        case zigbee.BASIC_CLUSTER:
            parseBasicCluster(descMap)
            descMap.remove('additionalAttrs')?.each { m -> parseBasicCluster(descMap + m) }
            break
        case XIAOMI_CLUSTER_ID:
            parseXiaomiCluster(descMap)
            descMap.remove('additionalAttrs')?.each { m -> parseXiaomiCluster(descMap + m) }
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
    if (settings.logEnable) {
        log.trace "zigbee received Basic cluster attribute 0x${descMap.attrId} (value ${descMap.value})"
    }

    switch (descMap.attrInt as Integer) {
        case PING_ATTR_ID: // Using 0x01 read as a simple ping/pong mechanism
            if (settings.txtEnable) { log.info 'pong..' }
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
 * Zigbee Xiaomi Cluster Parsing
 */
void parseXiaomiCluster(Map descMap) {
    if (settings.logEnable) {
        log.trace "zigbee received Xiaomi cluster attribute 0x${descMap.attrId} (value ${descMap.value})"
    }

    switch (descMap.attrInt as Integer) {
        case PRESENCE_ATTR_ID:
            Integer value = hexStrToUnsignedInt(descMap.value)
            updateAttribute('presence', value == 0 ? 'not present' : 'present')
            break
        case PRESENCE_EVENT_ATTR_ID:
            Integer value = hexStrToUnsignedInt(descMap.value)
            if (value <= 7) {
                String activity = [ 'enter', 'leave', 'enter left', 'leave right', 'enter right', 'leave left', 'approach', 'away' ].get(value)
                updateAttribute('activity', activity)
                updateAttribute('motion', value in [0, 2, 4, 6, 7] ? 'active' : 'inactive')
            } else {
                log.warn "unknown presence value ${value}"
            }
            break
        case REGION_EVENT_ATTR_ID:
            Integer regionId = HexUtils.hexStringToInt(descMap.value[0..1])
            Integer value = HexUtils.hexStringToInt(descMap.value[2..3])
            String regionAction = [ 1: 'enter', 2: 'leave', 4: 'occupied', 8: 'unoccupied' ].get(value)
            if (settings.logEnable) { log.debug "region ${regionId} event ${regionAction}" }
            updateAttribute('regionNumber', regionId)
            updateAttribute('regionAction', regionAction)
            break
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
        case XIAOMI_ATTR_ID:
            if (descMap.value?.size() > 3) {
                ByteArrayInputStream stream = new ByteArrayInputStream(HexUtils.hexStringToByteArray(descMap.value))
                Map tags = decodeXiaomiStream(stream)
                stream.close()
                log.debug results
                if (tags[0x03]) {
                    log.debug "temperature ${convertTemperatureIfNeeded(tags[0x03], 'C', 1)}"
                }
                if (tags[0x05]) {
                    log.debug "RSSI -${tags[0x05]}dBm"
                }
                if (tags[0x08]) {
                    String swBuild = '0.0.0_' + (tags[0x08] & 0xFF).toString().padLeft(4, '0')
                    log.debug "swBuild ${swBuild}"
                }
            }
            break
        default:
            log.warn "zigbee received unknown Xiaomi cluster attribute 0x${descMap.attrId} (value ${descMap.value})"
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

    // Get configuration
    cmds += zigbee.readAttribute(XIAOMI_CLUSTER_ID, [
        SENSITIVITY_LEVEL_ATTR_ID,
        TRIGGER_DISTANCE_ATTR_ID,
        PRESENCE_ATTR_ID,
        PRESENCE_EVENT_ATTR_ID
    ], [:], DELAY_MS)
    scheduleCommandTimeoutCheck()
    return cmds
}

List<String> removeAllRegions() {
    log.info 'remove all regions'
    device.deleteCurrentState('regionNumber')
    device.deleteCurrentState('regionAction')
    return (1..10).collectMany { id -> removeRegion(id) }
}

List<String> removeInterferenceRegion() {
    return setInterferenceRegion('0,0', '1,7')
}

List<String> removeRegion(BigDecimal regionId) {
    if (regionId < 1 || regionId > 10) { log.error 'region must be between 1 and 10'; return [] }
    String octetStr = '07020' + HEX_CHARS[(int)regionId] + '0000000000'
    if (settings.logEnable) { log.debug "remove region ${regionId} = ${octetStr}" }
    return zigbee.writeAttribute(XIAOMI_CLUSTER_ID, SET_REGION_ATTR_ID, 0x41, octetStr, [:], DELAY_MS)
}

List<String> resetPresence() {
    log.info 'reset presence'
    updateAttribute('motion', 'inactive')
    updateAttribute('presence', 'not present')
    updateAttribute('activity', 'leave')
    return zigbee.writeAttribute(XIAOMI_CLUSTER_ID, RESET_PRESENCE_ATTR_ID, DataType.UINT8, 0x00, [:], 0)
}

List<String> setInterferenceRegion(String horizontal, String vertical) {
    String value = getRegionHex(horizontal, vertical)
    if (settings.logEnable) { log.debug "set interference region = ${value}" }
    return zigbee.writeAttribute(XIAOMI_CLUSTER_ID, SET_INTERFERENCE_ATTR_ID, DataType.UINT32, value, [:], 0)
}

List<String> setRegion(BigDecimal regionId, String horizontal, String vertical) {
    Integer[] x = horizontal.tokenize(',-') as Integer[]
    Integer[] y = vertical.tokenize(',-') as Integer[]
    if (regionId < 1 || regionId > 10) {
        log.error 'region must be between 1 and 10'
        return []
    }
    if (x.length != 2 || x[0] < 0 || x[0] > 4 || x[1] < 0 || x[1] > 4) {
        log.error("Invalid horizontal value: ${horizontal}")
        return []
    }
    if (y.length != 2 || y[0] < 1 || y[0] > 7 || y[1] < 1 || y[1] > 7) {
        log.error("Invalid vertical value: ${vertical}")
        return []
    }
    log.info "setting region ${regionId} grid {top=${y[0]}, bottom=${y[1]}, left=${x[0]}, right=${x[1]}}"
    int[] matrix = calculateMatrix(y[0], y[1], x[0], x[1])
    StringBuilder sb = new StringBuilder()
    sb.append('07010')
        .append(HEX_CHARS[(int)regionId])
        .append(HEX_CHARS[matrix[1]])
        .append(HEX_CHARS[matrix[0]])
        .append(HEX_CHARS[matrix[3]])
        .append(HEX_CHARS[matrix[2]])
        .append(HEX_CHARS[matrix[5]])
        .append(HEX_CHARS[matrix[4]])
        .append('0')
        .append(HEX_CHARS[matrix[6]])
        .append('FF')
    if (settings.logEnable) { log.debug "set region ${regionId} = ${sb}" }
    return zigbee.writeAttribute(XIAOMI_CLUSTER_ID, SET_REGION_ATTR_ID, 0x41, sb.toString(), [:], 0)
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

private int[] calculateMatrix(int top, int bottom, int left, int right) {
    int total = left + right > 0 ? (1 << right) - (1 << (left - 1)) : 0
    int[] results = new int[7]
    for (int i = top - 1; i < bottom; i++) { results[i] = total }
    return results
}

private String clusterLookup(Object cluster) {
    if (cluster) {
        int clusterInt = cluster in String ? hexStrToUnsignedInt(cluster) : cluster.toInteger()
        String label = zigbee.clusterLookup(clusterInt)?.clusterLabel
        String hex = "0x${intToHexStr(clusterInt, 2)}"
        return label ? "${label} (${hex}) cluster" : "cluster ${hex}"
    }
    return 'unknown'
}

private Map decodeXiaomiStream(ByteArrayInputStream stream) {
    Map results = [:]
    while (stream.available()) {
        int tag = stream.read()
        int dataType = stream.read()
        BigInteger value
        switch (dataType) {
            case 0x08 : // 8 bit data
            case 0x10 : // 8-bit boolean
            case 0x18 : // 8-bit bitmap
            case 0x20 : // 8-bit unsigned int
            case 0x28 : // 8-bit signed int
            case 0x30 : // 8-bit enumeration
                value = new BigInteger(stream.read())
                break
            case 0x21 : // 16-bit unsigned int
                value = readBytes(stream, 2)
                break
            case 0x0B : // 32-bit data
            case 0x1B : // 32-bit bitmap
            case 0x23 : // 32-bit unsigned int
            case 0x2B : // 32-bit signed int
                value = readBytes(stream, 4)
                break
            case 0x24 : // 40-bit Zcl40BitUint tag == 0x06 -> LQI (?)
            case 0x0C : // 40-bit data
            case 0x1C : // 40-bit bitmap
            case 0x24 : // 40-bit unsigned int
            case 0x2C : // 40-bit signed int
                value = readBytes(stream, 5)
                break
            case 0x0D : // 48-bit data
            case 0x1D : // 48-bit bitmap
            case 0x25 : // 48-bit unsigned int
            case 0x2D : // 48-bit signed integer
                value = readBytes(stream, 6)
                break
            default:
                log.error "unknown data type ${dataType}"
                break
        }
        log.debug "tag=0x${HexUtils.integerToHexString(tag, 2)}, dataType=0x${HexUtils.integerToHexString(dataType, 2)}, value=${value}"
        results[tag] = value
    }
    return results
}

private BigInteger readBytes(ByteArrayInputStream stream, int length) {
    byte[] byteArr = new byte[length]
    stream.read(byteArr, 0, length)
    BigInteger bigInt = BigInteger.ZERO
    for (int i = byteArr.length - 1; i >= 0; i--) {
        bigInt = bigInt | (BigInteger.valueOf((byteArr[i] & 0xFF) << (8 * i)))
    }
    return bigInt
}

private String getRegionHex(String horizontal, String vertical) {
    Integer[] x = horizontal.tokenize(',-') as Integer[]
    Integer[] y = vertical.tokenize(',-') as Integer[]
    if (x.length != 2 || x[0] < 0 || x[0] > 4 || x[1] < 0 || x[1] > 4) {
        log.error("Invalid horizontal value: ${horizontal}")
        return ''
    }
    if (y.length != 2 || y[0] < 1 || y[0] > 7 || y[1] < 1 || y[1] > 7) {
        log.error("Invalid vertical value: ${vertical}")
        return ''
    }
    log.info "setting interference region grid {top=${y[0]}, bottom=${y[1]}, left=${x[0]}, right=${x[1]}}"
    int[] matrix = calculateMatrix(y[0], y[1], x[0], x[1])
    StringBuilder hexString = new StringBuilder('0')
    for (int i = matrix.length - 1; i >= 0; i--) {
        hexString.append(HEX_CHARS[matrix[i]])
    }
    return hexString.toString()
}

private void scheduleCommandTimeoutCheck(int delay = COMMAND_TIMEOUT) {
    runIn(delay, 'deviceCommandTimeout')
}

private void scheduleDeviceHealthCheck(int intervalMins) {
    Random rnd = new Random()
    schedule("${rnd.nextInt(59)} ${rnd.nextInt(9)}/${intervalMins} * ? * * *", 'ping')
}

private void updateAttribute(String attribute, Object value, String unit = null, String type = 'digital') {
    String descriptionText = "${attribute} was set to ${value}${unit ?: ''}"
    if (device.currentValue(attribute) != value && settings.txtEnable) {
        log.info descriptionText
    }
    sendEvent(name: attribute, value: value, unit: unit, type: type, descriptionText: descriptionText)
}

// Zigbee Attribute IDs
@Field static final int DIRECTION_MODE_ATTR_ID = 0x0144
@Field static final int PING_ATTR_ID = 0x01
@Field static final int PRESENCE_ATTR_ID = 0x0142
@Field static final int PRESENCE_EVENT_ATTR_ID = 0x0143
@Field static final int REGION_EVENT_ATTR_ID = 0x0151
@Field static final int RESET_PRESENCE_ATTR_ID = 0x0157
@Field static final int SENSITIVITY_LEVEL_ATTR_ID = 0x010C
@Field static final int SET_EDGE_REGION_ATTR_ID = 0x0156
@Field static final int SET_EXIT_REGION_ATTR_ID = 0x0153
@Field static final int SET_INTERFERENCE_ATTR_ID = 0x0154
@Field static final int SET_REGION_ATTR_ID = 0x0150
@Field static final int TRIGGER_DISTANCE_ATTR_ID = 0x0146
@Field static final int XIAOMI_ATTR_ID = 0x00F7
@Field static final int XIAOMI_CLUSTER_ID = 0xFCC0
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

@Field static char[] HEX_CHARS = '0123456789ABCDEF'.toCharArray()

// Command timeout before setting healthState to offline
@Field static final int COMMAND_TIMEOUT = 10

// Delay inbetween zigbee commands
@Field static final int DELAY_MS = 200
