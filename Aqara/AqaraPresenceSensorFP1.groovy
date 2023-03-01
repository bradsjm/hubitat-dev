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
 *  their existing work in decoding the FP1's protocol.
 *
 *  Region encoding and decoding courtesy of Otnow:
 *  https://github.com/dresden-elektronik/deconz-rest-plugin/issues/5928#issuecomment-1166545226
 */

/**
 *  1. Aqara states that sensor needs about 6s to detect presence (whether person stays or not)
 *  2. It determines within 30s that a person is no longer there
 *  3. 'Enter' should be PIR-like, which means as soon as sensors sees something
 */
import groovy.transform.Field
import hubitat.helper.HexUtils
import hubitat.zigbee.zcl.DataType

metadata {
    definition(name: 'Aqara Presence Sensor FP1',
            importUrl: 'https://raw.githubusercontent.com/bradsjm/hubitat-dev/main/Aqara/AqaraPresenceSensorFP1.groovy',
            namespace: 'aqara', author: 'Jonathan Bradshaw') {
        capability 'Configuration'
        capability 'Health Check'
        capability 'Motion Sensor'
        capability 'Presence Sensor'
        capability 'Sensor'

        command 'resetPresence'

        attribute 'healthStatus', 'enum', [ 'unknown', 'offline', 'online' ]
        attribute 'activity', 'enum', PRESENCE_ACTIONS.values() as List<String>
        attribute 'regionNumber', 'number'
        attribute 'regionActivity', 'enum', REGION_ACTIONS.values() as List<String>

        fingerprint model: 'lumi.motion.ac01', manufacturer: 'aqara', profileId: '0104', endpointId: '01', inClusters: '0000,0003,FCC0', outClusters: '0003,0019', application: '36'
    }

    preferences {
        input name: 'approachDistance', type: 'enum', title: '<b>Approach Distance</b>', options: ApproachDistanceOpts.options, defaultValue: ApproachDistanceOpts.defaultValue, description:\
            '<i>Maximum distance for detecting approach vs away.</i>'

        input name: 'sensitivityLevel', type: 'enum', title: '<b>Motion Sensitivity</b>', options: SensitivityLevelOpts.options, defaultValue: SensitivityLevelOpts.defaultValue, description:\
            '<i>Sensitivity of movement detection.</i>'

        input name: 'directionMode', type: 'enum', title: '<b>Monitoring Direction Mode</b>', options: DirectionModeOpts.options, defaultValue: DirectionModeOpts.defaultValue, description:\
            '<i>Enables direction detection capabilities.</i>'

        (1..10).each { int id ->
            input name: "detectionRegion${id}", type: 'text', title: "<b>Detection Region #${id}</b>", description: \
                "<i>Set grid value for <b>region ${id}</b> by using the ${getPopupLink('region calculator')}.</i>"
        }

        input name: 'interferenceRegion', type: 'text', title: '<b>Interference Grid (Optional)</b>', description: \
                "<i>Optional region masking value from the ${getPopupLink('region calculator')}.</i>"

        input name: 'exitEntrancesRegion', type: 'text', title: '<b>Exit/Entrance Grid (Optional)</b>', description: \
                "<i>Optional exit/entrances value from the ${getPopupLink('region calculator')}.</i>"

        input name: 'edgesRegion', type: 'text', title: '<b>Edge Definition Grid (Optional)</b>', description: \
                "<i>Optional edges grid value from the ${getPopupLink('region calculator')}.</i>"

        input name: 'presenceResetInterval', type: 'enum', title: '<b>Presence Watchdog</b>', options: PresenceResetOpts.options, defaultValue: PresenceResetOpts.defaultValue, description:\
            '<i>Reset presence if stuck for extended period of time.</i>'

        input name: 'healthCheckInterval', type: 'enum', title: '<b>Healthcheck Interval</b>', options: HealthcheckIntervalOpts.options, defaultValue: HealthcheckIntervalOpts.defaultValue, description:\
            '<i>Changes how often the hub pings sensor to check health.</i>'

        input name: 'txtEnable', type: 'bool', title: '<b>Enable descriptionText logging</b>', defaultValue: true, description:\
            '<i>Enables command logging.</i>'

        input name: 'logEnable', type: 'bool', title: '<b>Enable debug logging</b>', defaultValue: false, description:\
            '<i>Turns on debug logging for 30 minutes.</i>'
    }
}

@Field static final String VERSION = '0.2'

List<String> configure() {
    List<String> cmds = []
    log.info 'configure...'

    // Set motion sensitivity
    if (settings.sensitivityLevel) {
        log.info "setting sensitivity level to ${SensitivityLevelOpts.options[settings.sensitivityLevel as Integer]}"
        cmds += zigbee.writeAttribute(XIAOMI_CLUSTER_ID, SENSITIVITY_LEVEL_ATTR_ID, DataType.UINT8, settings.sensitivityLevel as Integer, MFG_CODE, DELAY_MS)
    }

    // Set trigger distance
    if (settings.approachDistance) {
        log.info "setting approach distance to ${ApproachDistanceOpts.options[settings.approachDistance as Integer]}"
        cmds += zigbee.writeAttribute(XIAOMI_CLUSTER_ID, TRIGGER_DISTANCE_ATTR_ID, DataType.UINT8, settings.approachDistance as Integer, MFG_CODE, DELAY_MS)
    }

    // Enable left right detection
    if (settings.directionMode) {
        log.info "setting direction mode to ${DirectionModeOpts.options[settings.directionMode as Integer]}"
        cmds += zigbee.writeAttribute(XIAOMI_CLUSTER_ID, DIRECTION_MODE_ATTR_ID, DataType.UINT8, settings.directionMode as Integer, MFG_CODE, DELAY_MS)
    }

    // Set detection regions
    (1..10).each { int id ->
        if (settings["detectionRegion${id}"]) {
            log.info "setting detection region ${id} value to " + settings["detectionRegion${id}"]
            cmds += setDetectionRegionAttribute(id, settings["detectionRegion${id}"].tokenize(',') as int[])
        } else {
            log.info "removing detection region ${id}"
            cmds += setDetectionRegionAttribute(id, 0, 0, 0, 0, 0, 0, 0)
        }
    }

    // Set interference region
    if (settings.interferenceRegion) {
        log.info 'setting detection interference region value to ' + settings.interferenceRegion
        cmds += setRegionAttribute(SET_INTERFERENCE_ATTR_ID, settings.interferenceRegion.tokenize(',') as int[])
    } else {
        log.info 'removing detection interference region'
        cmds += setRegionAttribute(SET_INTERFERENCE_ATTR_ID, 0, 0, 0, 0, 0, 0, 0)
    }

    // Set exits/entrances region
    if (settings.exitEntrancesRegion) {
        log.info 'setting exits/entrances region value to ' + settings.exitEntrancesRegion
        cmds += setRegionAttribute(SET_EXIT_REGION_ATTR_ID, settings.exitEntrancesRegion.tokenize(',') as int[])
    } else {
        log.info 'removing exits/entrances region'
        cmds += setRegionAttribute(SET_EXIT_REGION_ATTR_ID, 0, 0, 0, 0, 0, 0, 0)
    }

    // Set edges region
    if (settings.edgesRegion) {
        log.info 'setting edges region value to ' + settings.edgesRegion
        cmds += setRegionAttribute(SET_EDGE_REGION_ATTR_ID, settings.edgesRegion.tokenize(',') as int[])
    } else {
        log.info 'removing edges region'
        cmds += setRegionAttribute(SET_EDGE_REGION_ATTR_ID, 0, 0, 0, 0, 0, 0, 0)
    }

    // Get configuration
    cmds += zigbee.readAttribute(XIAOMI_CLUSTER_ID, [
        SENSITIVITY_LEVEL_ATTR_ID,
        TRIGGER_DISTANCE_ATTR_ID,
        DIRECTION_MODE_ATTR_ID,
    ], MFG_CODE, DELAY_MS)

    // Enable raw sensor data
    //cmds += zigbee.writeAttribute(XIAOMI_CLUSTER_ID, 0x0155, DataType.UINT8, 0x01, MFG_CODE, DELAY_MS)

    if (settings.logEnable) { log.debug "zigbee configure cmds: ${cmds}" }

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
            if (settings.logEnable) { log.debug "xiaomi: presence attribute is ${value}" }
            updateAttribute('presence', value == 0 ? 'not present' : 'present')
            if (settings.presenceResetInterval && value) {
                runIn((settings.presenceResetInterval as int) * 3600, 'resetPresence')
            } else if (settings.presenceResetInterval) {
                unschedule('resetPresence')
            }
            break
        case PRESENCE_ACTIONS_ATTR_ID:
            Integer value = hexStrToUnsignedInt(descMap.value)
            if (settings.logEnable) { log.debug "xiaomi: action attribute is ${value}" }
            if (value <= 7) {
                String activity = PRESENCE_ACTIONS.get(value)
                updateAttribute('activity', activity)
                updateAttribute('motion', value in [0, 2, 4, 6, 7] ? 'active' : 'inactive')
            } else {
                log.warn "unknown presence value ${value}"
            }
            break
        case REGION_EVENT_ATTR_ID:
            Integer regionId = HexUtils.hexStringToInt(descMap.value[0..1])
            Integer value = HexUtils.hexStringToInt(descMap.value[2..3])
            String regionActivity = REGION_ACTIONS.get(value)
            if (settings.logEnable) { log.debug "xiaomi: region ${regionId} action is ${value}" }
            updateAttribute('regionNumber', regionId)
            updateAttribute('regionActivity', regionActivity)
            break
        case SENSITIVITY_LEVEL_ATTR_ID:
            Integer value = hexStrToUnsignedInt(descMap.value)
            log.info "sensitivity level is '${SensitivityLevelOpts.options[value]}' (0x${descMap.value})"
            device.updateSetting('sensitivityLevel', [value: value.toString(), type: 'enum' ])
            break
        case TRIGGER_DISTANCE_ATTR_ID:
            Integer value = hexStrToUnsignedInt(descMap.value)
            log.info "approach distance is '${ApproachDistanceOpts.options[value]}' (0x${descMap.value})"
            device.updateSetting('approachDistance', [value: value.toString(), type: 'enum' ])
            break
        case DIRECTION_MODE_ATTR_ID:
            Integer value = hexStrToUnsignedInt(descMap.value)
            log.info "monitoring direction mode is '${DirectionModeOpts.options[value]}' (0x${descMap.value})"
            device.updateSetting('directionMode', [value: value.toString(), type: 'enum' ])
            break
        case XIAOMI_TAGS_ATTR_ID:
            Map tags = decodeXiaomiTags(descMap.value)
            if (tags[SWBUILD_TAG_ID]) {
                String swBuild = (tags[SWBUILD_TAG_ID] & 0xFF).toString().padLeft(4, '0')
                device.updateDataValue('softwareBuild', swBuild)
            }
            break
        // case XIAOMI_RAW_ATTR_ID:
        //     byte[] rawData = HexUtils.hexStringToByteArray(descMap.value)
        //     int distanceCm = new BigInteger((byte[])[rawData[17], rawData[18]]).toInteger()
        //     log.debug "distance ${distanceCm}cm"
        //     break
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

List<String> resetPresence() {
    log.info 'reset presence'
    updateAttribute('motion', 'inactive')
    updateAttribute('presence', 'not present')
    updateAttribute('activity', 'leave')
    device.deleteCurrentState('regionNumber')
    device.deleteCurrentState('regionActivity')
    return zigbee.writeAttribute(XIAOMI_CLUSTER_ID, RESET_PRESENCE_ATTR_ID, DataType.UINT8, 0x01, MFG_CODE, 0)
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

/**
 *  Reads a specified number of little-endian bytes from a given
 *  ByteArrayInputStream and returns a BigInteger.
 */
private static BigInteger readBigIntegerBytes(ByteArrayInputStream stream, int length) {
    byte[] byteArr = new byte[length]
    stream.read(byteArr, 0, length)
    BigInteger bigInt = BigInteger.ZERO
    for (int i = byteArr.length - 1; i >= 0; i--) {
        bigInt = bigInt | (BigInteger.valueOf((byteArr[i] & 0xFF) << (8 * i)))
    }
    return bigInt
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

/**
 *  Decodes a Xiaomi Zigbee cluster attribute payload in hexadecimal format and
 *  returns a map of decoded tag number and value pairs where the value is either a
 *  BigInteger for fixed values or a String for variable length.
 */
private Map<Integer, Object> decodeXiaomiTags(String hexString) {
    Map<Integer, Object> results = [:]
    byte[] bytes = HexUtils.hexStringToByteArray(hexString)
    new ByteArrayInputStream(bytes).withCloseable { stream ->
        while (stream.available() > 2) {
            int tag = stream.read()
            int dataType = stream.read()
            Object value
            if (DataType.isDiscrete(dataType)) {
                int length = stream.read()
                byte[] byteArr = new byte[length]
                stream.read(byteArr, 0, length)
                value = new String(byteArr)
            } else {
                int length = DataType.getLength(dataType)
                value = readBigIntegerBytes(stream, length)
            }
            results[tag] = value
            if (settings.logEnable) {
                log.debug "Xiaomi decode tag=0x${HexUtils.integerToHexString(tag, 2)}, dataType=0x${HexUtils.integerToHexString(dataType, 2)}, value=${value}"
            }
        }
    }
    return results
}

private void scheduleCommandTimeoutCheck(int delay = COMMAND_TIMEOUT) {
    runIn(delay, 'deviceCommandTimeout')
}

private void scheduleDeviceHealthCheck(int intervalMins) {
    Random rnd = new Random()
    schedule("${rnd.nextInt(59)} ${rnd.nextInt(9)}/${intervalMins} * ? * * *", 'ping')
}

/**
 *  Set a detection region on an Aqara FP1 device based on the provided grid coordinates.
 *
 *  This function takes in a region ID and a grid array containing seven values representing the
 *  rows of the grid. It checks if the region ID and grid values are valid, and returns an
 *  empty list if they are not.
 */
private List<String> setDetectionRegionAttribute(int regionId, int... grid) {
    if (regionId < 1 || regionId > 10) {
        log.error 'region must be between 1 and 10'
        return []
    }
    if (grid.size() != 7) {
        log.error 'grid must contain 7 row values'
        return []
    }

    String octetStr = '07020' + HEX_CHARS[(int)regionId] + '0000000000'
    if (grid.sum() > 0) {
        octetStr = new StringBuilder()
            .append('07010')
            .append(HEX_CHARS[(int)regionId])
            .append(HEX_CHARS[grid[1]])
            .append(HEX_CHARS[grid[0]])
            .append(HEX_CHARS[grid[3]])
            .append(HEX_CHARS[grid[2]])
            .append(HEX_CHARS[grid[5]])
            .append(HEX_CHARS[grid[4]])
            .append('0')
            .append(HEX_CHARS[grid[6]])
            .append('FF')
    }
    return zigbee.writeAttribute(XIAOMI_CLUSTER_ID, SET_REGION_ATTR_ID, DataType.STRING_OCTET, octetStr, MFG_CODE, DELAY_MS)
}

private List<String> setRegionAttribute(int attribute, int... grid) {
    if (grid.size() != 7) {
        log.error 'grid must contain exactly 7 row values'
        return []
    }
    StringBuilder hexString = new StringBuilder('0')
    for (int i = 6; i >= 0; i--) {
        hexString.append(HEX_CHARS[grid[i]])
    }
    return zigbee.writeAttribute(XIAOMI_CLUSTER_ID, attribute, DataType.UINT32, hexString.toString(), MFG_CODE, DELAY_MS)
}

private void updateAttribute(String attribute, Object value, String unit = null, String type = 'digital') {
    String descriptionText = "${attribute} was set to ${value}${unit ?: ''}"
    if (device.currentValue(attribute) != value && settings.txtEnable) {
        log.info descriptionText
    }
    sendEvent(name: attribute, value: value, unit: unit, type: type, descriptionText: descriptionText)
}

// Hex characters used for conversion
@Field static final char[] HEX_CHARS = '0123456789ABCDEF'.toCharArray()

// Set of Presence Actions
@Field static final Map<Integer, String> PRESENCE_ACTIONS = [
    0: 'enter',
    1: 'leave',
    2: 'enter (left)',
    3: 'leave (right)',
    4: 'enter (right)',
    5: 'leave (left)',
    6: 'towards',
    7: 'away'
]

// Set of Region Actions
@Field static final Map<Integer, String> REGION_ACTIONS = [
    1: 'enter',
    2: 'leave',
    4: 'occupied',
    8: 'unoccupied'
]

// Zigbee Cluster, Attribute and Xiaomi Tag IDs
@Field static final int DIRECTION_MODE_ATTR_ID = 0x0144
@Field static final int PING_ATTR_ID = 0x01
@Field static final int PRESENCE_ATTR_ID = 0x0142
@Field static final int PRESENCE_ACTIONS_ATTR_ID = 0x0143
@Field static final int REGION_EVENT_ATTR_ID = 0x0151
@Field static final int RESET_PRESENCE_ATTR_ID = 0x0157
@Field static final int SENSITIVITY_LEVEL_ATTR_ID = 0x010C
@Field static final int SET_EDGE_REGION_ATTR_ID = 0x0156
@Field static final int SET_EXIT_REGION_ATTR_ID = 0x0153
@Field static final int SET_INTERFERENCE_ATTR_ID = 0x0154
@Field static final int SET_REGION_ATTR_ID = 0x0150
@Field static final int SWBUILD_TAG_ID = 0x08
@Field static final int TRIGGER_DISTANCE_ATTR_ID = 0x0146
@Field static final int XIAOMI_TAGS_ATTR_ID = 0x00F7
@Field static final int XIAOMI_CLUSTER_ID = 0xFCC0
@Field static final int XIAOMI_RAW_ATTR_ID = 0xFFF2
@Field static final Map MFG_CODE = [ mfgCode: 0x115F ]

// Configuration options
@Field static final Map ApproachDistanceOpts = [
    defaultValue: 0x00,
    options: [ 0x00: 'Far (3m)', 0x01: 'Medium (2m)', 0x02: 'Near (1m)' ]
]

@Field static final Map SensitivityLevelOpts = [
    defaultValue: 0x03,
    options: [ 0x01: 'Low', 0x02: 'Medium', 0x03: 'High' ]
]

@Field static final Map DirectionModeOpts = [
    defaultValue: 0x00,
    options: [ 0x00: 'Undirected Enter/Leave', 0x01: 'Left & Right Enter/Leave' ]
]

@Field static final Map HealthcheckIntervalOpts = [
    defaultValue: 10,
    options: [ 10: 'Every 10 Mins', 15: 'Every 15 Mins', 30: 'Every 30 Mins', 45: 'Every 45 Mins', 59: 'Every Hour', 00: 'Disabled' ]
]

@Field static final Map PresenceResetOpts = [
    defaultValue: 0,
    options: [ 0: 'Disabled', 60: 'After 1 Hour', 2: 'After 2 Hours', 4: 'After 4 Hours', 8: 'After 8 Hours', 12: 'After 12 Hours' ]
]

// Command timeout before setting healthState to offline
@Field static final int COMMAND_TIMEOUT = 10

// Delay inbetween zigbee commands
@Field static final int DELAY_MS = 200

private String getPopupLink(String title) {
    String url = 'https://htmlpreview.github.io/?https://raw.githubusercontent.com/bradsjm/hubitat-dev/main/Aqara/AqaraFP1DetectionGrid.html'
    return """<a href='#' onClick="window.open('${url}','popUpGrid','height=450,width=300,left=100,top=200,resizable=no,scrollbars=no,toolbar=no,menubar=no,location=no,status=no');">${title}</a>"""
}
