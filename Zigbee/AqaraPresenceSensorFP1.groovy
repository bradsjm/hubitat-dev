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
            importUrl: 'https://raw.githubusercontent.com/bradsjm/hubitat-dev/main/Zigbee/AqaraPresenceSensorFP1.groovy',
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
        attribute 'regionAction', 'enum', REGION_ACTIONS.values() as List<String>

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
                "<i>Set detection grid for <a href=\'${GRID_IMG_HREF}\' target='_blank'>region ${id}</a> (top, bottom, left, right)</i>"
        }

        input name: 'interferenceRegion', type: 'text', title: '<b>Interference Grid Region</b>', defaultValue: '1,7,0,0', description: \
            "<i>Optional interference <a href=\'${GRID_IMG_HREF}\' target='_blank'>region</a> (top, bottom, left, right)</i>"

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

@Field static final String GRID_IMG_HREF = 'https://smarthomescene.com/wp-content/uploads/2023/02/aqara-fp1-regions-zigbee2mqtt-zone-grid.jpg'

@Field static final String VERSION = '0.1'

List<String> configure() {
    List<String> cmds = []
    log.info 'configure...'

    // Set motion sensitivity
    if (settings.sensitivityLevel) {
        cmds += zigbee.writeAttribute(XIAOMI_CLUSTER_ID, SENSITIVITY_LEVEL_ATTR_ID, DataType.UINT8, settings.sensitivityLevel as Integer, MFG_CODE, DELAY_MS)
    }

    // Set trigger distance
    if (settings.approachDistance) {
        cmds += zigbee.writeAttribute(XIAOMI_CLUSTER_ID, TRIGGER_DISTANCE_ATTR_ID, DataType.UINT8, settings.approachDistance as Integer, MFG_CODE, DELAY_MS)
    }

    // Enable left right detection
    if (settings.directionMode) {
        cmds += zigbee.writeAttribute(XIAOMI_CLUSTER_ID, DIRECTION_MODE_ATTR_ID, DataType.UINT8, settings.directionMode as Integer, MFG_CODE, DELAY_MS)
    }

    // Set detection regions
    (1..10).each { int id ->
        if (settings["detectionRegion${id}"]) {
            cmds += setDetectionRegion(id, settings["detectionRegion${id}"].tokenize(',') as int[])
        } else {
            cmds += setDetectionRegion(id, 1, 7, 0, 0)
        }
    }

    // Set interference region
    if (settings.interferenceRegion) {
        cmds += setInterferenceRegion(settings.interferenceRegion.tokenize(',') as int[])
    } else {
        cmds += setInterferenceRegion(1, 7, 0, 0)
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
            String regionAction = REGION_ACTIONS.get(value)
            if (settings.logEnable) { log.debug "xiaomi: region ${regionId} action is ${value}" }
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
            log.info "trigger distance is '${ApproachDistanceOpts.options[value]}' (0x${descMap.value})"
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
    device.deleteCurrentState('regionAction')
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
 *  Calculates the set of values that make up a boxed region specified by
 *  the top, bottom, left and right parameters and returns an array of 7 rows
 *  used by the Aqara FP1 region operations. If left and right are passed in
 *  as zero then it is assumed the matrix should be blank.
 *
 *      X1 X2 X3 X4
 *  Y1 | 1| 2| 4| 8|
 *  Y2 | 1| 2| 4| 8|
 *  Y3 | 1| 2| 4| 8|
 *  Y4 | 1| 2| 4| 8|
 *  Y5 | 1| 2| 4| 8|
 *  Y6 | 1| 2| 4| 8|
 *  Y7 | 1| 2| 4| 8|
 */
private static char[] calculateBoxedRegionArray(int top, int bottom, int left, int right) {
    int total = left + right > 0 ? (1 << right) - (1 << (left - 1)) : 0
    char[] results = new char[7]
    for (int i = top - 1; i < bottom; i++) { results[i] = HEX_CHARS[total] }
    return results
}

/**
 *  Calculates the region value for a boxed region specified by
 *  the given horizontal (X) and vertical (Y) parameters that is
 *  used by the Aqara FP1 region operations.
 */
private static String calculateBoxedRegionHex(int[] x, int[] y) {
    if (x.length != 2 || x[0] < 0 || x[0] > 4 || x[1] < x[0] || x[1] > 4) {
        return ''
    }
    if (y.length != 2 || y[0] < 1 || y[0] > 7 || y[1] < y[0] || y[1] > 7) {
        return ''
    }
    char[] matrix = calculateBoxedRegionArray(y[0], y[1], x[0], x[1]) // top, bottom, left, right
    StringBuilder hexString = new StringBuilder('0')
    for (int i = matrix.length - 1; i >= 0; i--) {
        hexString.append(matrix[i])
    }
    return hexString.toString()
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
 *  BigInteger for discreet values or a String for variable length.
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

private List<String> setDetectionRegion(int regionId, int... box) {
    int[] y = box[0..1] as int[]
    int[] x = box[2..3] as int[]
    if (regionId < 1 || regionId > 10) {
        log.error 'region must be between 1 and 10'
        return []
    }
    if (x.length != 2 || x[0] < 0 || x[0] > 4 || x[1] < x[0] || x[1] > 4) {
        log.error("Invalid horizontal value: ${x}")
        return []
    }
    if (y.length != 2 || y[0] < 1 || y[0] > 7 || y[1] < y[0] || y[1] > 7) {
        log.error("Invalid vertical value: ${y}")
        return []
    }

    String octetStr
    if (x[0] == 0 && x[1] == 0) { // remove region
        octetStr = '07020' + HEX_CHARS[(int)regionId] + '0000000000'
    } else {
        char[] matrix = calculateBoxedRegionArray(y[0], y[1], x[0], x[1])
        octetStr = new StringBuilder()
            .append('07010')
            .append(HEX_CHARS[(int)regionId])
            .append(matrix[1])
            .append(matrix[0])
            .append(matrix[3])
            .append(matrix[2])
            .append(matrix[5])
            .append(matrix[4])
            .append('0')
            .append(matrix[6])
            .append('FF')
    }
    return zigbee.writeAttribute(XIAOMI_CLUSTER_ID, SET_REGION_ATTR_ID, DataType.STRING_OCTET, octetStr, MFG_CODE, DELAY_MS)
}

private List<String> setInterferenceRegion(int... box) {
    int[] y = box[0..1] as int[]
    int[] x = box[2..3] as int[]
    if (x.length != 2 || x[0] < 0 || x[0] > 4 || x[1] < x[0] || x[1] > 4) {
        log.error("Invalid horizontal value: ${x}")
        return []
    }
    if (y.length != 2 || y[0] < 1 || y[0] > 7 || y[1] < y[0] || y[1] > 7) {
        log.error("Invalid vertical value: ${y}")
        return []
    }
    String value = calculateBoxedRegionHex(x, y)
    return zigbee.writeAttribute(XIAOMI_CLUSTER_ID, SET_INTERFERENCE_ATTR_ID, DataType.UINT32, value, MFG_CODE, DELAY_MS)
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
