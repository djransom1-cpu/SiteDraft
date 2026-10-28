package com.example.sitedraft.data.laser

import com.example.sitedraft.util.format

internal fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

data class GlmMeasurement(val meters: Float, val counter: Int)

/**
 * Pulls a measurement out of a GLM frame, or null if the frame is not one.
 *
 * Captured from a GLM 50 C on 2026-10-28 (see the probe in [GlmLaserClient]):
 *  - reply to the sync request:  `00 10 | 02 08 DE 01 00 00 00 00 ... | 22`
 *  - unsolicited push on measure: `C0 55 10 | 06 08 DF 01 37 F8 38 40 00 ... | A2`
 * i.e. status/start bytes, a length (0x10), then a 16-byte payload: two type bytes, a 16-bit
 * little-endian measurement counter, the distance as little-endian float32 metres, padding; then
 * a checksum. A zero distance (the reply before any measurement, or a "laser on" push with type
 * bytes 02 09) is not a measurement. The counter lets two identical distances in a row still
 * count as two readings.
 */
fun parseGlmFrame(frame: ByteArray): GlmMeasurement? {
    val payloadStart = when {
        frame.size >= 3 && frame[0] == 0x00.toByte() -> 2
        frame.size >= 4 && frame[0] == 0xC0.toByte() && frame[1] == 0x55.toByte() -> 3
        else -> return null
    }
    val len = frame[payloadStart - 1].toInt() and 0xFF
    if (len < 8 || payloadStart + len > frame.size) return null
    val counter = (frame[payloadStart + 2].toInt() and 0xFF) or ((frame[payloadStart + 3].toInt() and 0xFF) shl 8)
    val bits = (frame[payloadStart + 4].toInt() and 0xFF) or ((frame[payloadStart + 5].toInt() and 0xFF) shl 8) or
        ((frame[payloadStart + 6].toInt() and 0xFF) shl 16) or ((frame[payloadStart + 7].toInt() and 0xFF) shl 24)
    val meters = Float.fromBits(bits)
    if (!meters.isFinite() || meters < 0.05f || meters > 300f) return null
    return GlmMeasurement(meters, counter)
}

/**
 * The CRC-8 the GLM puts at the end of every frame: polynomial 0xA6, initial value 0xAA, no
 * reflection, no final XOR. Found by brute force over six frames captured from a GLM 50 C on
 * 2026-10-28 (the "documented" request byte 0x1D is simply wrong for this unit).
 */
fun glmCrc8(bytes: ByteArray, length: Int = bytes.size): Int {
    var crc = 0xAA
    for (i in 0 until length) {
        crc = crc xor (bytes[i].toInt() and 0xFF)
        repeat(8) {
            crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0xA6) and 0xFF else (crc shl 1) and 0xFF
        }
    }
    return crc
}

fun withGlmCrc(body: ByteArray): ByteArray = body + glmCrc8(body).toByte()

fun ByteArray.glmCrcOk(): Boolean = size >= 2 && glmCrc8(this, size - 1) == (this[size - 1].toInt() and 0xFF)

/**
 * "Give me the current measurement" and, as a side effect on the GLM 50 C, "push every new
 * measurement to me". Ends in 0x1A: the checksum this unit accepts (the documented 0x1D is rejected).
 */
val GLM_SYNC_REQUEST: ByteArray = withGlmCrc(byteArrayOf(0xC0.toByte(), 0x55, 0x02, 0x01, 0x00))
