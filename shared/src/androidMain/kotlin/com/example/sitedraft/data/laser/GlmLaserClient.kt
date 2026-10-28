package com.example.sitedraft.data.laser

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Bosch GLM laser measures over Bluetooth.
 *
 * Two transports, same frames:
 *  - Bluetooth Low Energy (GLM 50 C, GLM 120 C, ...): found by [startScan], connected with GATT,
 *    measurements arrive as notifications on the Bosch characteristic. These devices are NOT
 *    "paired" in the system settings; they are discovered and connected directly, which is what
 *    the Bosch app does.
 *  - Bluetooth Classic SPP (GLM 100 C and other older units): paired in system settings, listed
 *    by [bondedDevices].
 *
 * Protocol: the community-documented "sync" request [SYNC_REQUEST] makes the device report its
 * latest measurement; the reply is parsed leniently by [parseGlmFrame] and every raw frame is
 * kept in [State.log], because exact offsets differ between firmware versions. Everything the
 * GATT layer discovers is logged too, so a unit with different UUIDs can be matched up.
 */
class GlmLaserClient(private val context: Context) {

    enum class Status { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    data class Reading(val meters: Float, val frameHex: String, val at: Long)

    data class LeDevice(val name: String, val address: String)

    data class State(
        val status: Status = Status.DISCONNECTED,
        val deviceName: String? = null,
        val reading: Reading? = null,
        // Increments on every NEW measurement, so observers can react to a repeat of the same
        // distance as well as to a change.
        val readingSerial: Int = 0,
        val autoPoll: Boolean = true,
        val scanning: Boolean = false,
        val leDevices: List<LeDevice> = emptyList(),
        val message: String? = null,
        val log: List<String> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    // Classic SPP
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var output: OutputStream? = null
    // BLE
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var glmCharacteristic: BluetoothGattCharacteristic? = null
    private val leScanResults = mutableListOf<BluetoothDevice>()
    private var scanCallback: ScanCallback? = null

    @Volatile private var closing = false
    @Volatile private var lastCounter = -1
    @Volatile private var lastMeters = Float.NaN
    private var poller: Thread? = null

    // ---- permissions ----

    fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    // ---- device lists ----

    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BluetoothDevice> =
        if (!hasConnectPermission()) emptyList() else adapter?.bondedDevices?.toList().orEmpty()

    @SuppressLint("MissingPermission")
    fun deviceLabel(device: BluetoothDevice): String =
        (if (hasConnectPermission()) runCatching { device.name }.getOrNull() else null) ?: device.address

    /** BLE scan for SCAN_DURATION_MS. Named devices only; GLMs sort first. */
    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasScanPermission() || !hasConnectPermission()) {
            _state.update { it.copy(message = "Bluetooth scan permission not granted") }
            return
        }
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            _state.update { it.copy(message = "Bluetooth is off") }
            return
        }
        stopScan()
        leScanResults.clear()
        _state.update { it.copy(scanning = true, leDevices = emptyList(), message = null) }
        log("scanning for Bluetooth LE devices…")
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = result.scanRecord?.deviceName ?: runCatching { device.name }.getOrNull() ?: return
                synchronized(leScanResults) {
                    if (leScanResults.none { it.address == device.address }) {
                        leScanResults += device
                        val list = leScanResults.map { LeDevice(runCatching { it.name }.getOrNull() ?: name, it.address) }
                            .sortedBy { if (it.name.contains("GLM", ignoreCase = true)) 0 else 1 }
                        _state.update { it.copy(leDevices = list) }
                        log("found $name (${device.address})")
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                log("scan failed: $errorCode")
                _state.update { it.copy(scanning = false, message = "Scan failed ($errorCode)") }
            }
        }
        scanCallback = cb
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        Handler(Looper.getMainLooper()).postDelayed({ stopScan() }, SCAN_DURATION_MS)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val cb = scanCallback ?: return
        scanCallback = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }
        if (_state.value.scanning) {
            _state.update { it.copy(scanning = false) }
            log("scan finished: ${leScanResults.size} device(s)")
        }
    }

    /** Connect to a device found by [startScan]. */
    fun connectLe(address: String) {
        val device = synchronized(leScanResults) { leScanResults.firstOrNull { it.address == address } }
            ?: adapter?.getRemoteDevice(address) ?: return
        connectLe(device)
    }

    // ---- connect / disconnect ----

    @SuppressLint("MissingPermission")
    fun connectLe(device: BluetoothDevice) {
        if (!hasConnectPermission()) {
            _state.update { it.copy(status = Status.ERROR, message = "Bluetooth permission not granted") }
            return
        }
        stopScan()
        disconnect()
        closing = false
        val name = deviceLabel(device)
        _state.update { it.copy(status = Status.CONNECTING, deviceName = name, message = null) }
        log("connecting (LE) to $name")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /** Connect to a bonded Bluetooth Classic device (GLM 100 C style) over SPP. */
    @SuppressLint("MissingPermission")
    fun connectClassic(device: BluetoothDevice) {
        if (!hasConnectPermission()) {
            _state.update { it.copy(status = Status.ERROR, message = "Bluetooth permission not granted") }
            return
        }
        stopScan()
        disconnect()
        closing = false
        val name = deviceLabel(device)
        _state.update { it.copy(status = Status.CONNECTING, deviceName = name, message = null) }
        thread(name = "glm-laser-spp") {
            try {
                adapter?.cancelDiscovery()
                val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                s.connect()
                socket = s
                output = s.outputStream
                _state.update { it.copy(status = Status.CONNECTED, message = null) }
                log("connected (SPP) to $name")
                startPoller()
                readLoop(s.inputStream)
                if (!closing) {
                    _state.update { it.copy(status = Status.DISCONNECTED, message = "Connection closed by device") }
                    log("closed by device")
                }
            } catch (e: Exception) {
                if (closing) {
                    _state.update { it.copy(status = Status.DISCONNECTED, message = null) }
                } else {
                    _state.update { it.copy(status = Status.ERROR, message = e.message ?: e.javaClass.simpleName) }
                    log("error: ${e.message}")
                }
            } finally {
                closeSocket()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        closing = true
        closeSocket()
        val g = gatt
        gatt = null
        glmCharacteristic = null
        runCatching { g?.disconnect() }
        runCatching { g?.close() }
        poller = null
        if (_state.value.status != Status.DISCONNECTED) {
            _state.update { it.copy(status = Status.DISCONNECTED) }
            log("disconnected")
        }
    }

    fun setAutoPoll(enabled: Boolean) {
        _state.update { it.copy(autoPoll = enabled) }
        if (enabled && isConnected()) startPoller()
    }

    /** One measurement request, off the calling thread. */
    fun requestMeasurement() {
        thread(name = "glm-laser-req") { send(SYNC_REQUEST) }
    }

    @Volatile private var probing = false
    @Volatile private var probeByte = -1

    /**
     * Diagnostic: the device answered the documented request with `03 00 0A` (status 3 =
     * checksum error), so the request's trailing checksum is wrong for this firmware. Send the
     * same request with every possible checksum byte and log any reply that is not the error
     * reply; the byte that gets through pins down the checksum algorithm.
     */
    fun probeChecksums() {
        if (probing || !isConnected()) return
        probing = true
        _state.update { it.copy(autoPoll = false) }
        log("probing 256 checksums…")
        android.util.Log.d(TAG, "probe start")
        thread(name = "glm-laser-probe") {
            try {
                for (b in 0..255) {
                    if (closing || !isConnected()) break
                    probeByte = b
                    val frame = SYNC_REQUEST.copyOf()
                    frame[frame.size - 1] = b.toByte()
                    send(frame)
                    Thread.sleep(PROBE_INTERVAL_MS)
                }
                log("probe done")
                android.util.Log.d(TAG, "probe done")
            } finally {
                probing = false
                probeByte = -1
            }
        }
    }

    private fun isConnected() = socket != null || glmCharacteristic != null

    // ---- BLE GATT ----

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("GATT connected, discovering services")
                // Bigger MTU so a full reply fits one notification; discovery runs once it is set
                // (or immediately if the request cannot be made).
                if (!g.requestMtu(128)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                glmCharacteristic = null
                if (closing) {
                    _state.update { it.copy(status = Status.DISCONNECTED) }
                } else {
                    val msg = if (status == BluetoothGatt.GATT_SUCCESS) "Connection closed by device" else "GATT error $status"
                    _state.update { it.copy(status = if (status == BluetoothGatt.GATT_SUCCESS) Status.DISCONNECTED else Status.ERROR, message = msg) }
                    log(msg)
                }
                runCatching { g.close() }
                if (gatt === g) gatt = null
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log("MTU $mtu")
            g.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.update { it.copy(status = Status.ERROR, message = "Service discovery failed ($status)") }
                return
            }
            g.services.forEach { s ->
                log("svc ${s.uuid.toString().take(8)}: " + s.characteristics.joinToString(" ") { c -> c.uuid.toString().take(8) + propsLabel(c.properties) })
            }
            val known = g.getService(GLM_SERVICE)?.getCharacteristic(GLM_CHARACTERISTIC)
            val chosen = known ?: g.services
                .filter { it.uuid.toString().uppercase() != "00001800-0000-1000-8000-00805F9B34FB" && it.uuid.toString().uppercase() != "00001801-0000-1000-8000-00805F9B34FB" }
                .flatMap { it.characteristics }
                .firstOrNull { c ->
                    val p = c.properties
                    (p and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0 &&
                        (p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0
                }
            if (chosen == null) {
                _state.update { it.copy(status = Status.ERROR, message = "No measurement characteristic found - see device log") }
                return
            }
            log((if (known != null) "using Bosch characteristic " else "using fallback characteristic ") + chosen.uuid.toString().take(8))
            glmCharacteristic = chosen
            g.setCharacteristicNotification(chosen, true)
            val cccd = chosen.getDescriptor(CCCD)
            if (cccd == null) {
                onReady()
                return
            }
            val value = if ((chosen.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, value)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            log("notifications " + if (status == BluetoothGatt.GATT_SUCCESS) "enabled" else "failed ($status)")
            onReady()
        }

        // Android 13+ signature.
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleFrame(value)
        }

        // Pre-13 signature.
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                characteristic.value?.let { handleFrame(it) }
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) log("write failed ($status)")
        }

        private fun onReady() {
            _state.update { it.copy(status = Status.CONNECTED, message = null) }
            startPoller()
        }
    }

    private fun propsLabel(p: Int): String {
        val sb = StringBuilder("[")
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) sb.append('R')
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) sb.append('W')
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) sb.append('w')
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) sb.append('N')
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) sb.append('I')
        return sb.append(']').toString()
    }

    // ---- shared I/O ----

    private fun startPoller() {
        if (poller?.isAlive == true) return
        poller = thread(name = "glm-laser-poll") {
            while (!closing && isConnected() && _state.value.autoPoll) {
                send(SYNC_REQUEST)
                try { Thread.sleep(POLL_INTERVAL_MS) } catch (_: InterruptedException) { break }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun send(bytes: ByteArray) {
        val out = output
        if (out != null) {
            try {
                synchronized(out) { out.write(bytes); out.flush() }
            } catch (e: Exception) {
                if (!closing) log("tx failed: ${e.message}")
            }
            return
        }
        val g = gatt ?: return
        val c = glmCharacteristic ?: return
        val writeType = if ((c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, bytes, writeType)
            } else {
                @Suppress("DEPRECATION")
                c.writeType = writeType
                @Suppress("DEPRECATION")
                c.value = bytes
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        } catch (e: Exception) {
            if (!closing) log("tx failed: ${e.message}")
        }
    }

    private fun readLoop(input: InputStream) {
        val buf = ByteArray(512)
        while (!closing) {
            val n = input.read(buf)
            if (n < 0) return
            if (n > 0) handleFrame(buf.copyOf(n))
        }
    }

    private fun handleFrame(frame: ByteArray) {
        val hex = frame.toHex()
        if (probing) {
            val isErrorReply = frame.size == 3 && frame[0] == 0x03.toByte()
            android.util.Log.d(TAG, "probe crc=%02X rx=%s".format(probeByte, hex))
            if (!isErrorReply) log("crc %02X -> %s".format(probeByte, hex))
        } else {
            android.util.Log.d(TAG, "rx $hex")
            log(if (frame.glmCrcOk()) "rx $hex" else "rx (bad crc) $hex")
        }
        val m = parseGlmFrame(frame) ?: return
        // The device's own counter says whether this is a new measurement; a poll reply that
        // repeats the last one carries the same counter.
        if (m.counter == lastCounter && m.meters == lastMeters) return
        lastCounter = m.counter
        lastMeters = m.meters
        _state.update {
            it.copy(reading = Reading(m.meters, hex, System.currentTimeMillis()), readingSerial = it.readingSerial + 1)
        }
    }

    private fun closeSocket() {
        val s = socket
        socket = null
        output = null
        try { s?.close() } catch (_: Exception) { }
    }

    private fun log(line: String) {
        _state.update { it.copy(log = (it.log + line).takeLast(LOG_LINES)) }
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        // Bosch GLM 50 C BLE measurement service/characteristic as documented by the open
        // reverse-engineering projects; a fallback search handles units that differ.
        val GLM_SERVICE: UUID = UUID.fromString("02a6c0d0-0451-4000-b000-fb3210111989")
        val GLM_CHARACTERISTIC: UUID = UUID.fromString("02a6c0d1-0451-4000-b000-fb3210111989")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // "Give me the current measurement" and, as a side effect on the GLM 50 C, "push every
        // new measurement to me". The documented request ends in 0x1D; this unit rejected that
        // as a checksum error (reply 03 00 0A) and accepted 0x1A, found by probing all 256 values.
        val SYNC_REQUEST: ByteArray get() = GLM_SYNC_REQUEST

        // The device pushes measurements itself once a valid request has been sent, so polling
        // is only a safety net for a missed push.
        const val POLL_INTERVAL_MS = 2000L
        const val PROBE_INTERVAL_MS = 250L
        const val TAG = "SiteDraftLaser"
        const val SCAN_DURATION_MS = 10_000L
        const val LOG_LINES = 16
    }
}

