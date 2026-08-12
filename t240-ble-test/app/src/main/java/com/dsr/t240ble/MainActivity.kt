package com.dsr.t240ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Minimal test app proving that a custom app can:
 *   1. discover + connect to a Shengmai T240 "Record Card" over BLE,
 *   2. complete the handshake/bind flow (within the device's 5 s window),
 *   3. exchange commands: time sync, battery, and "USB disk" enable.
 *
 * Protocol notes (from the T240 BLE/WiFi protocol doc):
 *  - Frame: `0x01 cmdL cmdH data...`
 *  - Handshake (cmd 0x01 0x00) uses a step byte after the command:
 *      dev->app 0x00 + JSON{"uuid"}  ->  app->dev 0x01 + JSON{"time","uuid"}
 *      -> dev->app 0x02 <code> [JSON device info if code==0x00]
 *  - Time sync: 0x04, battery: 0x09.
 *  - There is no dedicated "open USB disk" opcode. USB-disk (U盘) mode is the
 *    device default and is blocked by two things:
 *      * WiFi open (0x0A) hands the SD card to the WiFi chip -> close with 0x0B;
 *      * "storage disabled" (0x70 param 1) explicitly means "cannot be used as a
 *        USB drive on a computer" -> restore with 0x70 param 0; query with 0x71.
 *    So "Enable USB Disk" = close WiFi (0x0B) -> enable storage (0x70 0x00)
 *    -> verify (0x71 returns 0x00 = storage allowed = mounts as USB drive).
 */
@SuppressLint("MissingPermission", "SetTextI18n")
class MainActivity : Activity() {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("00001910-0000-1000-8000-00805f9b34fb")
        val WRITE_UUID: UUID = UUID.fromString("00001912-0000-1000-8000-00805f9b34fb")
        val NOTIFY_UUID: UUID = UUID.fromString("00001911-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val HEAD: Byte = 0x01
        const val CMD_HANDSHAKE = 0x01
        const val CMD_TIME_SYNC = 0x04
        const val CMD_GET_SN = 0x02
        const val CMD_BATTERY = 0x09
        const val CMD_WIFI_OPEN = 0x0A
        const val CMD_WIFI_CLOSE = 0x0B
        const val CMD_STORAGE_SET = 0x70
        const val CMD_STORAGE_GET = 0x71

        const val DEVICE_NAME_MATCH = "T240"
        const val SCAN_TIMEOUT_MS = 15_000L
        const val HANDSHAKE_TIMEOUT_MS = 6_000L
        const val USB_STEP_TIMEOUT_MS = 2_000L
        const val REQ_PERMS = 42
    }

    private enum class State { DISCONNECTED, SCANNING, CONNECTING, HANDSHAKING, BOUND, FAILED }

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var rawInput: EditText

    private var state = State.DISCONNECTED
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var mtuPayload = 20                       // usable bytes per write (MTU - 3)
    private var scanning = false
    private var deviceUuid: String? = null

    // frame reassembly buffer for notifications (JSON payloads can span notifications)
    private var rxBuf = ByteArray(0)

    // sequential write queue (WRITE_NO_RESPONSE still gets onCharacteristicWrite callbacks)
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeBusy = false

    // "Enable USB disk" sequence: 0=idle, 1=closing wifi, 2=enabling storage, 3=verifying
    private var usbStep = 0
    private var usbStepTimeout: Runnable? = null
    private var handshakeTimeout: Runnable? = null
    private var scanStopper: Runnable? = null

    private val appUuid: String by lazy {
        val prefs = getSharedPreferences("t240", Context.MODE_PRIVATE)
        prefs.getString("app_uuid", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("app_uuid", it).apply()
        }
    }

    // ---------------------------------------------------------------- UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (8 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        statusView = TextView(this).apply {
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
        }
        root.addView(statusView)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(button("Scan & Connect") { onScanConnect() })
        row1.addView(button("Disconnect") { disconnect("user request") })
        root.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(button("Read Battery") { onReadBattery() })
        row2.addView(button("Enable USB Disk") { onEnableUsbDisk() })
        root.addView(row2)

        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rawInput = EditText(this).apply {
            hint = "raw frame hex, e.g. 01 09 00"
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        row3.addView(rawInput)
        row3.addView(button("Send raw") { onSendRaw() })
        root.addView(row3)

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            addView(logView)
        }
        root.addView(logScroll)

        setContentView(root)
        setStatus(State.DISCONNECTED, null)
        log("app UUID: $appUuid")
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        setOnClickListener { onClick() }
    }

    private fun setStatus(s: State, detail: String?) {
        state = s
        ui.post {
            val text = when (s) {
                State.DISCONNECTED -> "Disconnected"
                State.SCANNING -> "Scanning…"
                State.CONNECTING -> "Connecting…"
                State.HANDSHAKING -> "Handshaking…"
                State.BOUND -> "Bound ✓"
                State.FAILED -> "Failed"
            } + (detail?.let { "  ($it)" } ?: "")
            statusView.text = text
            statusView.setTextColor(
                when (s) {
                    State.BOUND -> Color.rgb(0, 140, 0)
                    State.FAILED -> Color.RED
                    else -> Color.DKGRAY
                }
            )
        }
    }

    private fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        ui.post {
            logView.append("$ts  $msg\n")
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun hex(b: ByteArray, off: Int = 0, len: Int = b.size - off): String =
        (off until off + len).joinToString(" ") { "%02X".format(b[it]) }

    // ---------------------------------------------------------------- permissions

    private fun neededPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPerms() = neededPerms().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(req, perms, res)
        if (req == REQ_PERMS) {
            if (res.isNotEmpty() && res.all { it == PackageManager.PERMISSION_GRANTED }) {
                log("permissions granted")
                onScanConnect()
            } else {
                log("!! permissions denied — cannot scan")
            }
        }
    }

    // ---------------------------------------------------------------- scan + connect

    private fun onScanConnect() {
        if (!hasPerms()) {
            log("requesting runtime permissions…")
            requestPermissions(neededPerms(), REQ_PERMS)
            return
        }
        if (scanning || state == State.CONNECTING || state == State.HANDSHAKING) {
            log("already busy (state=$state)"); return
        }
        if (gatt != null) disconnect("reconnecting")

        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            log("!! Bluetooth is off — enable it and retry"); return
        }
        val scanner = adapter.bluetoothLeScanner ?: run { log("!! no BLE scanner"); return }

        setStatus(State.SCANNING, null)
        log("scanning for name containing \"$DEVICE_NAME_MATCH\"…")
        if (Build.VERSION.SDK_INT < 31) log("(Android <12: location services must be ON for BLE scan)")
        scanning = true
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, scanCb)
        scanStopper = Runnable {
            if (scanning) {
                stopScan()
                setStatus(State.FAILED, "no T240 found in ${SCAN_TIMEOUT_MS / 1000}s")
                log("!! scan timeout — no device with \"$DEVICE_NAME_MATCH\" in name found")
            }
        }.also { ui.postDelayed(it, SCAN_TIMEOUT_MS) }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        scanStopper?.let { ui.removeCallbacks(it) }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        adapter?.bluetoothLeScanner?.stopScan(scanCb)
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName ?: return
            if (!name.contains(DEVICE_NAME_MATCH, ignoreCase = true)) return
            if (!scanning) return
            stopScan()
            log("found \"$name\" ${result.device.address} rssi=${result.rssi}")
            setStatus(State.CONNECTING, name)
            gatt = result.device.connectGatt(
                this@MainActivity, false, gattCb, android.bluetooth.BluetoothDevice.TRANSPORT_LE
            )
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            setStatus(State.FAILED, "scan error $errorCode")
            log("!! scan failed, code=$errorCode")
        }
    }

    private fun disconnect(reason: String) {
        cancelHandshakeTimeout()
        cancelUsbTimeout()
        usbStep = 0
        stopScan()
        writeQueue.clear(); writeBusy = false
        rxBuf = ByteArray(0)
        gatt?.let {
            log("disconnecting ($reason)")
            it.disconnect(); it.close()
        }
        gatt = null; writeChar = null
        if (state != State.FAILED) setStatus(State.DISCONNECTED, reason)
    }

    // ---------------------------------------------------------------- GATT

    private val gattCb = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("connected (status=$status) — requesting MTU 247")
                // handshake JSON is bigger than the default 20-byte payload
                g.requestMtu(247)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("disconnected, gatt status=$status" + gattStatusHint(status))
                cancelHandshakeTimeout(); cancelUsbTimeout(); usbStep = 0
                writeQueue.clear(); writeBusy = false
                g.close()
                if (gatt === g) { gatt = null; writeChar = null }
                if (state != State.FAILED) setStatus(State.DISCONNECTED, "gatt status $status")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            mtuPayload = if (status == BluetoothGatt.GATT_SUCCESS) mtu - 3 else 20
            log("MTU=$mtu (payload $mtuPayload) status=$status — discovering services")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("!! service discovery failed: $status")
                setStatus(State.FAILED, "discovery $status"); return
            }
            val svc = g.getService(SERVICE_UUID)
            if (svc == null) {
                log("!! service 1910 not found; services: " + g.services.joinToString { it.uuid.toString().substring(4, 8) })
                setStatus(State.FAILED, "no 1910 service"); return
            }
            writeChar = svc.getCharacteristic(WRITE_UUID)
            val notify = svc.getCharacteristic(NOTIFY_UUID)
            if (writeChar == null || notify == null) {
                log("!! 1911/1912 characteristics missing")
                setStatus(State.FAILED, "chars missing"); return
            }
            // Opening notify starts the device's handshake — it will push its UUID
            log("enabling notifications on 1911 (opens data channel)")
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(CCCD_UUID)
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("!! CCCD write failed: $status")
                setStatus(State.FAILED, "notify enable $status"); return
            }
            log("notifications ON — waiting for device UUID (5 s window running)")
            setStatus(State.HANDSHAKING, null)
            handshakeTimeout = Runnable {
                setStatus(State.FAILED, "handshake timeout")
                log("!! no bind result within ${HANDSHAKE_TIMEOUT_MS} ms")
            }.also { ui.postDelayed(it, HANDSHAKE_TIMEOUT_MS) }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val data = c.value ?: return
            ui.post { onNotification(data) }
        }

        // (API 33+ variant; both never fire together)
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            ui.post { onNotification(value) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) log("!! write failed: $status")
            ui.post { writeBusy = false; drainWriteQueue() }
        }
    }

    private fun gattStatusHint(status: Int) = when (status) {
        8 -> " (0x08 connection timeout — device may have dropped us: handshake too slow or unbound app)"
        19 -> " (0x13 remote closed the connection — likely bind rejection)"
        else -> ""
    }

    // ---------------------------------------------------------------- TX

    private fun sendFrame(frame: ByteArray, label: String) {
        val wc = writeChar
        if (gatt == null || wc == null) { log("!! not connected, can't send $label"); return }
        log("TX $label: ${hex(frame)}")
        var off = 0
        while (off < frame.size) {
            val n = minOf(mtuPayload, frame.size - off)
            writeQueue.add(frame.copyOfRange(off, off + n))
            off += n
        }
        drainWriteQueue()
    }

    private fun drainWriteQueue() {
        if (writeBusy) return
        val chunk = writeQueue.poll() ?: return
        val g = gatt ?: return
        val wc = writeChar ?: return
        writeBusy = true
        wc.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        @Suppress("DEPRECATION")
        wc.value = chunk
        @Suppress("DEPRECATION")
        if (!g.writeCharacteristic(wc)) {
            log("!! writeCharacteristic() rejected chunk")
            writeBusy = false
        }
    }

    private fun frame(cmd: Int, data: ByteArray = ByteArray(0)) =
        byteArrayOf(HEAD, cmd.toByte(), 0x00) + data

    // ---------------------------------------------------------------- RX + frame parsing

    private fun onNotification(chunk: ByteArray) {
        log("RX ${hex(chunk)}")
        rxBuf += chunk
        parseLoop()
    }

    private fun parseLoop() {
        while (rxBuf.isNotEmpty()) {
            if (rxBuf[0] != HEAD) {
                log("!! non-command data (type=0x%02X), dropping %d bytes".format(rxBuf[0], rxBuf.size))
                rxBuf = ByteArray(0); return
            }
            if (rxBuf.size < 3) return // need cmdL+cmdH
            val cmd = rxBuf[1].toInt() and 0xFF
            val cmdH = rxBuf[2].toInt() and 0xFF
            if (cmdH != 0) {
                log("!! unexpected cmdH=0x%02X, dropping buffer: %s".format(cmdH, hex(rxBuf)))
                rxBuf = ByteArray(0); return
            }
            val consumed = when (cmd) {
                CMD_HANDSHAKE -> parseHandshake()
                CMD_TIME_SYNC -> fixed(17) { log("time echo: \"${String(rxBuf, 3, 14)}\" — time sync OK") }
                CMD_BATTERY -> fixed(4) {
                    val pct = rxBuf[3].toInt() and 0xFF
                    log("battery: $pct%")
                    toast("Battery: $pct%")
                }
                CMD_GET_SN -> fixed(21) { log("SN: \"${String(rxBuf, 3, 18)}\"") }
                CMD_WIFI_OPEN -> fixed(4) { log("wifi-open result: 0x%02X".format(rxBuf[3])) }
                CMD_WIFI_CLOSE -> fixed(3) { onWifiClosedAck() }
                CMD_STORAGE_SET -> fixed(4) { onStorageSetAck(rxBuf[3].toInt() and 0xFF) }
                CMD_STORAGE_GET -> fixed(4) { onStorageState(rxBuf[3].toInt() and 0xFF) }
                else -> { // unknown reply: no length field in protocol, consume everything
                    log("frame cmd=0x%02X data=[%s]".format(cmd, hex(rxBuf, 3)))
                    rxBuf.size
                }
            }
            if (consumed == 0) return // incomplete — wait for more notifications
            rxBuf = rxBuf.copyOfRange(consumed, rxBuf.size)
        }
    }

    /** Runs [handler] and consumes [len] bytes if the buffer has them, else waits. */
    private inline fun fixed(len: Int, handler: () -> Unit): Int {
        if (rxBuf.size < len) return 0
        handler(); return len
    }

    /** Returns index just past the balanced JSON object starting at [start], or -1 if incomplete. */
    private fun jsonEnd(start: Int): Int {
        if (start >= rxBuf.size || rxBuf[start] != '{'.code.toByte()) return -1
        var depth = 0; var inStr = false; var esc = false
        for (i in start until rxBuf.size) {
            val ch = rxBuf[i].toInt().toChar()
            when {
                esc -> esc = false
                inStr && ch == '\\' -> esc = true
                ch == '"' -> inStr = !inStr
                !inStr && ch == '{' -> depth++
                !inStr && ch == '}' -> { depth--; if (depth == 0) return i + 1 }
            }
        }
        return -1
    }

    // ---------------------------------------------------------------- handshake

    private fun parseHandshake(): Int {
        if (rxBuf.size < 4) return 0
        return when (val step = rxBuf[3].toInt() and 0xFF) {
            0x00 -> { // device UUID + JSON
                val end = jsonEnd(4)
                if (end < 0) return 0
                val json = String(rxBuf, 4, end - 4)
                log("device hello: $json")
                try {
                    deviceUuid = JSONObject(json).getString("uuid")
                    log("device UUID = $deviceUuid — sending app UUID")
                    val reply = JSONObject()
                        .put("time", System.currentTimeMillis() / 1000)
                        .put("uuid", appUuid)
                        .toString()
                    sendFrame(
                        byteArrayOf(HEAD, CMD_HANDSHAKE.toByte(), 0x00, 0x01) + reply.toByteArray(),
                        "handshake step1 (app UUID)"
                    )
                } catch (e: Exception) {
                    log("!! bad hello JSON: $e")
                    setStatus(State.FAILED, "bad hello JSON")
                }
                end
            }
            0x02 -> { // bind result
                if (rxBuf.size < 5) return 0
                val code = rxBuf[4].toInt() and 0xFF
                var consumed = 5
                var info: String? = null
                if (code == 0x00 && rxBuf.size > 5 && rxBuf[5] == '{'.code.toByte()) {
                    val end = jsonEnd(5)
                    if (end < 0) return 0 // wait for the rest of the info JSON
                    info = String(rxBuf, 5, end - 5)
                    consumed = end
                }
                cancelHandshakeTimeout()
                if (code == 0x00) {
                    setStatus(State.BOUND, null)
                    log("BIND OK ✓" + (info?.let { "  device info: $it" } ?: ""))
                    sendTimeSync()
                } else {
                    val reason = when (code) {
                        0x01 -> "app UUID check failed (device bound to another app? unbind with 01 05 00)"
                        0x02 -> "data length invalid"
                        0x03 -> "command sent before handshake"
                        0x04 -> "handshake timeout (>5s)"
                        else -> "unknown code"
                    }
                    setStatus(State.FAILED, "bind code 0x%02X".format(code))
                    log("!! bind FAILED code=0x%02X: %s".format(code, reason))
                }
                consumed
            }
            else -> {
                log("!! unexpected handshake step 0x%02X, dropping buffer".format(step))
                rxBuf.size
            }
        }
    }

    private fun cancelHandshakeTimeout() {
        handshakeTimeout?.let { ui.removeCallbacks(it) }; handshakeTimeout = null
    }

    private fun sendTimeSync() {
        val t = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
        sendFrame(frame(CMD_TIME_SYNC, t.toByteArray()), "time sync ($t)")
    }

    // ---------------------------------------------------------------- commands

    private fun requireBound(): Boolean {
        if (state != State.BOUND) { log("!! not bound yet — Scan & Connect first"); return false }
        return true
    }

    private fun onReadBattery() {
        if (!requireBound()) return
        sendFrame(frame(CMD_BATTERY), "read battery")
    }

    private fun onSendRaw() {
        if (!requireBound()) return
        val txt = rawInput.text.toString().replace(Regex("[^0-9a-fA-F]"), "")
        if (txt.isEmpty() || txt.length % 2 != 0) { log("!! raw input must be whole hex bytes"); return }
        val bytes = ByteArray(txt.length / 2) { txt.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        sendFrame(bytes, "raw")
    }

    // --- USB disk: close WiFi (0x0B) -> enable storage (0x70 0x00) -> verify (0x71) ---

    private fun onEnableUsbDisk() {
        if (!requireBound()) return
        if (usbStep != 0) { log("USB-disk sequence already running (step $usbStep)"); return }
        log("== enable USB disk: close WiFi -> enable storage -> verify ==")
        usbStep = 1
        sendFrame(frame(CMD_WIFI_CLOSE), "close WiFi")
        armUsbTimeout("close WiFi (probably already closed)") { usbEnableStorage() }
    }

    private fun onWifiClosedAck() {
        log("WiFi closed — SD card back under USB/BLE control")
        if (usbStep == 1) { cancelUsbTimeout(); usbEnableStorage() }
    }

    private fun usbEnableStorage() {
        usbStep = 2
        sendFrame(frame(CMD_STORAGE_SET, byteArrayOf(0x00)), "enable storage (USB disk allowed)")
        armUsbTimeout("enable storage") { usbVerify() }
    }

    private fun onStorageSetAck(code: Int) {
        if (usbStep == 2) {
            cancelUsbTimeout()
            if (code == 0x01) log("storage-enable ACK: success")
            else log("!! storage-enable ACK: FAILED (0x%02X) — device recording? stop recording and retry".format(code))
            usbVerify()
        } else log("storage-set ack: 0x%02X".format(code))
    }

    private fun usbVerify() {
        usbStep = 3
        sendFrame(frame(CMD_STORAGE_GET), "query storage state")
        armUsbTimeout("storage query") { usbStep = 0 }
    }

    private fun onStorageState(code: Int) {
        if (usbStep == 3) { cancelUsbTimeout(); usbStep = 0 }
        if (code == 0x00) {
            log("USB DISK ENABLED ✓ — storage allowed. Plug the T240 into a computer; it should mount as a drive.")
            toast("USB disk enabled ✓")
        } else {
            log("!! storage still disabled (0x%02X) — USB disk NOT available".format(code))
            toast("USB disk NOT enabled")
        }
    }

    private fun armUsbTimeout(what: String, next: () -> Unit) {
        cancelUsbTimeout()
        usbStepTimeout = Runnable {
            log("no reply to $what within ${USB_STEP_TIMEOUT_MS} ms — continuing")
            next()
        }.also { ui.postDelayed(it, USB_STEP_TIMEOUT_MS) }
    }

    private fun cancelUsbTimeout() {
        usbStepTimeout?.let { ui.removeCallbacks(it) }; usbStepTimeout = null
    }

    private fun toast(msg: String) =
        ui.post { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show() }

    override fun onDestroy() {
        super.onDestroy()
        disconnect("activity destroyed")
    }
}
