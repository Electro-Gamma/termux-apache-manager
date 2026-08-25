// EsptoolRead.kt – Kotlin port with TCP socket support (garbage-tolerant SLIP)
// Compile with: kotlinc EsptoolRead.kt -include-runtime -cp "jSerialComm-2.9.0.jar:json-20230227.jar" -d esptool.jar

import com.fazecast.jSerialComm.SerialPort
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import kotlin.system.exitProcess

// =====================================================================
// Universal protocol constants
// =====================================================================

const val ESP_FLASH_BEGIN = 0x02
const val ESP_FLASH_DATA = 0x03
const val ESP_FLASH_END = 0x04
const val ESP_MEM_BEGIN = 0x05
const val ESP_MEM_END = 0x06
const val ESP_MEM_DATA = 0x07
const val ESP_SYNC = 0x08
const val ESP_WRITE_REG = 0x09
const val ESP_READ_REG = 0x0a
const val ESP_SPI_SET_PARAMS = 0x0B
const val ESP_SPI_ATTACH = 0x0D
const val ESP_READ_FLASH_SLOW = 0x0e
const val ESP_CHANGE_BAUDRATE = 0x0F
const val ESP_FLASH_DEFL_BEGIN = 0x10
const val ESP_FLASH_DEFL_DATA = 0x11
const val ESP_FLASH_DEFL_END = 0x12
const val ESP_SPI_FLASH_MD5 = 0x13
const val ESP_ERASE_FLASH = 0xD0
const val ESP_ERASE_REGION = 0xD1
const val ESP_READ_FLASH = 0xD2
const val ESP_RUN_USER_CODE = 0xD3
const val ESP_FLASH_ENCRYPT_DATA = 0xD4

const val ROM_INVALID_RECV_MSG = 0x05
const val ESP_RAM_BLOCK = 0x1800
const val ESP_ROM_BAUD = 115200
const val ESP_CHECKSUM_MAGIC = 0xEF
const val FLASH_SECTOR_SIZE = 0x1000
const val UART_DATE_REG_ADDR = 0x60000078L
const val CHIP_DETECT_MAGIC_REG_ADDR = 0x40001000L
const val UART_CLKDIV_MASK = 0xFFFFFL

const val DEFAULT_TIMEOUT_MS = 3000
const val CHIP_ERASE_TIMEOUT_MS = 120000
const val MAX_TIMEOUT_MS = CHIP_ERASE_TIMEOUT_MS * 2
const val SYNC_TIMEOUT_MS = 100
const val MD5_TIMEOUT_PER_MB_MS = 8000
const val DEFAULT_CONNECT_ATTEMPTS = 7
const val STATUS_BYTES_LENGTH_DEFAULT = 2

const val VERSION = "3.1-kotlin-socket (read-only)"

// =====================================================================
// Small utilities: colors, hex formatting, struct-pack helpers
// =====================================================================

private fun supportsColor(): Boolean = System.console() != null

private val COLORS = mapOf(
    "red" to "\u001B[91m", "green" to "\u001B[92m", "yellow" to "\u001B[93m",
    "blue" to "\u001B[94m", "magenta" to "\u001B[95m", "cyan" to "\u001B[96m",
    "white" to "\u001B[97m", "reset" to "\u001B[0m"
)

fun colorize(text: String, colorName: String): String {
    if (!supportsColor()) return text
    return (COLORS[colorName] ?: "") + text + COLORS.getValue("reset")
}

fun colorAddress(s: String) = colorize(s, "green")
fun colorSize(s: String) = colorize(s, "blue")
fun colorStatus(s: String) = colorize(s, "green")
fun colorPort(s: String) = colorize(s, "cyan")
fun colorConnecting(s: String) = colorize(s, "yellow")
fun colorChipname(s: String) = colorize(s, "green")

fun hexify(data: ByteArray, uppercase: Boolean = true): String {
    val fmt = if (uppercase) "%02X" else "%02x"
    val sb = StringBuilder(data.size * 2)
    for (b in data) sb.append(fmt.format(b.toInt() and 0xFF))
    return sb.toString()
}

class HexFormatter(private val data: ByteArray, private val autoSplit: Boolean = true) {
    override fun toString(): String {
        if (!autoSplit || data.size <= 16) return hexify(data, uppercase = false)
        val sb = StringBuilder()
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + 16, data.size)
            val line = data.copyOfRange(offset, end)
            val ascii = buildString {
                for (b in line) {
                    val c = (b.toInt() and 0xFF).toChar()
                    append(if (c in ' '..'~') c else '.')
                }
            }
            val first8 = line.copyOfRange(0, minOf(8, line.size))
            val rest = if (line.size > 8) line.copyOfRange(8, line.size) else ByteArray(0)
            sb.append("\n    %-16s %-16s | %s".format(hexify(first8, false), hexify(rest, false), ascii))
            offset += 16
        }
        return sb.toString()
    }
}

fun padTo(data: ByteArray, alignment: Int, padByte: Byte = 0xFF.toByte()): ByteArray {
    val padMod = data.size % alignment
    if (padMod == 0) return data
    val extra = ByteArray(alignment - padMod) { padByte }
    return data + extra
}

fun packU32LE(vararg values: Long): ByteArray {
    val buf = ByteBuffer.allocate(4 * values.size).order(ByteOrder.LITTLE_ENDIAN)
    for (v in values) buf.putInt(v.toInt())
    return buf.array()
}

fun packBEPair(a: Long, b: Long): ByteArray {
    val buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
    buf.putInt(a.toInt())
    buf.putInt(b.toInt())
    return buf.array()
}

fun checksum(data: ByteArray, state: Int = ESP_CHECKSUM_MAGIC): Int {
    var s = state
    for (b in data) s = s xor (b.toInt() and 0xFF)
    return s
}

fun parseIntAuto(raw: String): Long {
    var s = raw.trim()
    var negative = false
    if (s.startsWith("-")) { negative = true; s = s.substring(1) }
    val value = when {
        s.startsWith("0x") || s.startsWith("0X") -> s.substring(2).toLong(16)
        s.startsWith("0o") || s.startsWith("0O") -> s.substring(2).toLong(8)
        s.startsWith("0b") || s.startsWith("0B") -> s.substring(2).toLong(2)
        else -> s.toLong(10)
    }
    return if (negative) -value else value
}

fun printOverwrite(message: String, lastLine: Boolean = false) {
    if (System.console() != null) {
        print("\r$message")
        if (lastLine) println()
    } else {
        println(message)
    }
    System.out.flush()
}

fun timeoutPerMb(msPerMb: Int, sizeBytes: Long): Int {
    val result = (msPerMb * (sizeBytes / 1e6)).toInt()
    return if (result < DEFAULT_TIMEOUT_MS) DEFAULT_TIMEOUT_MS else result
}

// =====================================================================
// Errors
// =====================================================================

open class FatalError(message: String) : RuntimeException(message) {
    companion object {
        fun withResult(message: String, result: ByteArray): FatalError =
            FatalError("$message (result was ${hexify(result)})")
    }
}

class NotImplementedInROMError(bootloader: ESPLoader, funcName: String) :
    FatalError("${bootloader.chipName} ROM does not support function $funcName.")

class UnsupportedCommandError(esp: ESPLoader, op: Int) : RuntimeException(
    if (esp.secureDownloadMode)
        "This command (0x%x) is not supported in Secure Download Mode".format(op)
    else
        "Invalid (unsupported) command 0x%x".format(op)
)

// =====================================================================
// SLIP framing – mirrors Python's slip_reader() exactly (see esptool.py).
//
// Python's slip_reader is a GENERATOR: when it yields a frame mid-way
// through iterating `read_bytes`, the *rest* of that array is still sitting
// there waiting on the next next() call, because generator suspension
// preserves the whole stack frame. A plain function that returns has no
// such memory, so this class keeps its own pending-buffer + position across
// calls instead — otherwise, whenever a single socket read happens to
// contain more than one frame (extremely common — the ROM often answers a
// SYNC with several back-to-back ack frames in one TCP segment), everything
// after the first frame's closing 0xC0 would silently be thrown away and
// the next read would start misaligned mid-frame.
// =====================================================================

class SlipDecoder(private val input: () -> ByteArray, private val trace: (String) -> Unit) {
    private var pending: ByteArray = ByteArray(0)
    private var pendingPos: Int = 0
    private var partial: ByteArrayOutputStream? = null
    private var inEscape: Boolean = false

    /** Discards any buffered-but-unconsumed bytes and any in-progress frame.
     *  Mirrors Python's flush_input(), which drops the old slip_reader
     *  generator (and anything it had queued) and builds a fresh one. */
    fun reset() {
        pending = ByteArray(0)
        pendingPos = 0
        partial = null
        inEscape = false
    }

    private fun nextByte(): Byte? {
        if (pendingPos >= pending.size) {
            val fresh = input()
            if (fresh.isEmpty()) return null // this read attempt timed out
            trace("Read ${fresh.size} bytes: ${HexFormatter(fresh)}")
            pending = fresh
            pendingPos = 0
        }
        return pending[pendingPos++]
    }

    /** Blocks until one full SLIP-framed packet has been read, and returns its payload. */
    fun readPacket(): ByteArray {
        while (true) {
            val b = nextByte()
            if (b == null) {
                // Same exit condition as Python's `if read_bytes == b'':`.
                val waitingFor = if (partial == null) "header" else "content"
                trace("Timed out waiting for packet $waitingFor")
                throw FatalError("Timed out waiting for packet $waitingFor")
            }
            if (partial == null) {
                if (b == 0xC0.toByte()) {
                    partial = ByteArrayOutputStream()
                } else {
                    throw FatalError("Invalid head of packet (0x%s)".format(hexify(byteArrayOf(b))))
                }
            } else {
                if (inEscape) {
                    inEscape = false
                    when (b) {
                        0xDC.toByte() -> partial!!.write(0xC0)
                        0xDD.toByte() -> partial!!.write(0xDB)
                        else -> throw FatalError("Invalid SLIP escape (0xdb, 0x%s)".format(hexify(byteArrayOf(b))))
                    }
                } else if (b == 0xDB.toByte()) {
                    inEscape = true
                } else if (b == 0xC0.toByte()) {
                    val result = partial!!.toByteArray()
                    trace("Received full packet: ${HexFormatter(result)}")
                    partial = null
                    return result
                } else {
                    partial!!.write(b.toInt())
                }
            }
        }
    }
}

fun slipEncode(packet: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(0xC0)
    for (b in packet) {
        when (b) {
            0xDB.toByte() -> { out.write(0xDB); out.write(0xDD) }
            0xC0.toByte() -> { out.write(0xDB); out.write(0xDC) }
            else -> out.write(b.toInt())
        }
    }
    out.write(0xC0)
    return out.toByteArray()
}

// =====================================================================
// Stub loader (loaded lazily from devices/<chip>.json, base64-encoded)
// =====================================================================

data class StubCode(
    val entry: Long,
    val textStart: Long,
    val text: ByteArray,
    val dataStart: Long,
    val data: ByteArray
)

fun loadStubFromJson(chipName: String): StubCode? {
    val file = File("devices", "$chipName.json")
    if (!file.exists()) {
        System.err.println(
            "WARNING: Stub file not found: ${file.path}. " +
                "Stub loading is unavailable for $chipName (pass --no-stub to use the slow ROM loader instead)."
        )
        return null
    }
    return try {
        val obj = JSONObject(file.readText())
        val decoder = Base64.getDecoder()
        StubCode(
            entry = obj.optLong("entry", 0L),
            textStart = obj.optLong("text_start", 0L),
            text = if (obj.has("text")) decoder.decode(obj.getString("text")) else ByteArray(0),
            dataStart = obj.optLong("data_start", 0L),
            data = if (obj.has("data")) decoder.decode(obj.getString("data")) else ByteArray(0)
        )
    } catch (e: Exception) {
        System.err.println("WARNING: Could not parse stub for $chipName: ${e.message}")
        null
    }
}

// =====================================================================
// Serial / Socket abstraction
// =====================================================================

interface SerialPortLike {
    fun read(timeoutMs: Int): ByteArray  // blocks until at least one byte, or timeout -> empty
    fun write(data: ByteArray)
    fun setBaudRate(baud: Int)
    // Readable back, like pyserial's `port.baudrate` property. For a real serial
    // port this reflects the hardware; for socket:// pyserial still stores and
    // returns whatever was last assigned even though it has no physical effect.
    fun getBaudRate(): Int
    fun close()
    fun setDTR(state: Boolean) {}
    fun setRTS(state: Boolean) {}
    fun flushInput() {}
    fun flushOutput() {}
    val portName: String
}

// ---- Physical serial port using jSerialComm ----
class JSerialPort(val port: SerialPort) : SerialPortLike {
    override val portName: String = port.systemPortName
    private var timeoutMs = DEFAULT_TIMEOUT_MS

    override fun read(timeoutMs: Int): ByteArray {
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, timeoutMs, 0)
        val waiting = port.bytesAvailable().let { if (it < 0) 0 else it }
        val toRead = if (waiting == 0) 1 else waiting
        val buffer = ByteArray(toRead)
        val n = port.readBytes(buffer, toRead.toLong())
        return if (n <= 0) ByteArray(0) else buffer.copyOfRange(0, n.toInt())
    }

    override fun write(data: ByteArray) { port.writeBytes(data, data.size.toLong()) }
    override fun setBaudRate(baud: Int) { port.setBaudRate(baud) }
    override fun getBaudRate(): Int = port.baudRate
    override fun close() { port.closePort() }
    override fun setDTR(state: Boolean) { if (state) port.setDTR() else port.clearDTR() }
    override fun setRTS(state: Boolean) { if (state) port.setRTS() else port.clearRTS() }
    override fun flushInput() { port.flushIOBuffers() }
    override fun flushOutput() { port.flushIOBuffers() }
}

// ---- TCP socket wrapper ----
// Mirrors pyserial's socket:// url handler: baud rate, DTR/RTS and flush calls
// are all silently accepted no-ops (pyserial docs: "All serial port settings,
// control and status lines are ignored"), but a genuine read timeout has to
// stay distinguishable from the remote end actually closing the connection.
class SocketPort(private val socket: Socket) : SerialPortLike {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()
    // pyserial keeps `.baudrate` as a plain, readable attribute even for
    // socket:// (it has no effect on the wire, but get_crystal_freq() etc.
    // still read it back) – reproduce that here instead of hard-coding a value.
    private var nominalBaud: Int = ESP_ROM_BAUD
    override val portName: String = "socket://${socket.inetAddress.hostAddress}:${socket.port}"

    override fun read(timeoutMs: Int): ByteArray {
        socket.soTimeout = timeoutMs
        try {
            val available = input.available()
            val toRead = if (available == 0) 1 else available
            val buffer = ByteArray(toRead)
            val n = input.read(buffer)
            if (n < 0) {
                // End of stream: the remote end closed the connection. This is not
                // "no data yet" – returning empty here would make callers treat a
                // dead connection as an ordinary timeout and retry forever.
                throw FatalError("Connection closed by remote host ($portName)")
            }
            return if (n == 0) ByteArray(0) else buffer.copyOfRange(0, n)
        } catch (e: java.net.SocketTimeoutException) {
            return ByteArray(0)
        }
    }

    override fun write(data: ByteArray) { output.write(data); output.flush() }
    override fun setBaudRate(baud: Int) { nominalBaud = baud /* stored only, no effect on the wire */ }
    override fun getBaudRate(): Int = nominalBaud
    override fun close() { socket.close() }
    override fun setDTR(state: Boolean) { /* no-op, matches pyserial socket:// */ }
    override fun setRTS(state: Boolean) { /* no-op, matches pyserial socket:// */ }
    override fun flushInput() { /* no-op, matches pyserial socket:// */ }
    override fun flushOutput() { /* no-op, matches pyserial socket:// */ }
}

// =====================================================================
// ESPLoader base class (modified to use SerialPortLike)
// =====================================================================

open class ESPLoader {
    val port: SerialPortLike
    val traceEnabled: Boolean
    var secureDownloadMode: Boolean = false
    var syncStubDetected: Boolean = false

    private val slipDecoder: SlipDecoder
    private var currentTimeoutMs: Int = DEFAULT_TIMEOUT_MS
    private var lastTraceNanos: Long = 0L

    // Constructor for any SerialPortLike
    constructor(port: SerialPortLike, baud: Int, traceEnabled: Boolean = false) {
        this.port = port
        this.traceEnabled = traceEnabled
        port.setBaudRate(baud)
        this.slipDecoder = SlipDecoder({ port.read(currentTimeoutMs) }) { msg -> trace(msg) }
    }

    // ---- chip-varying properties (overridden by subclasses) ----
    open val chipName: String = "Espressif device"
    open val isStub: Boolean = false
    open val statusBytesLength: Int = STATUS_BYTES_LENGTH_DEFAULT
    open val flashWriteSize: Long = 0x400L
    open val spiRegBase: Long = 0L
    open val spiUsrOffs: Long = 0L
    open val spiUsr1Offs: Long = 0L
    open val spiUsr2Offs: Long = 0L
    open val spiMosiDlenOffs: Long? = null
    open val spiMisoDlenOffs: Long? = null
    open val spiW0Offs: Long = 0L
    open val uartClkdivReg: Long = 0L
    open val xtalClkDivider: Int = 1
    open var stubCode: StubCode? = null
    open val stubJsonName: String = ""

    private fun setPortBaudrate(baud: Int) { port.setBaudRate(baud) }

    fun trace(fmt: String, vararg args: Any?) {
        if (!traceEnabled) return
        val now = System.nanoTime()
        val delta = if (lastTraceNanos == 0L) 0.0 else (now - lastTraceNanos) / 1e9
        lastTraceNanos = now
        try {
            println("TRACE +%.3f %s".format(delta, if (args.isEmpty()) fmt else fmt.format(*args)))
        } catch (e: Exception) {
            println("TRACE +%.3f %s".format(delta, fmt))
        }
    }

    fun write(packet: ByteArray) {
        val buf = slipEncode(packet)
        trace("Write %d bytes: %s", buf.size, HexFormatter(buf))
        port.write(buf)
    }

    fun read(): ByteArray = slipDecoder.readPacket()

    fun flushInput() {
        port.flushInput()
        slipDecoder.reset()
    }
    fun flushOutput() { port.flushOutput() }

    fun command(
        op: Int? = null,
        data: ByteArray = ByteArray(0),
        chk: Long = 0,
        waitResponse: Boolean = true,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): Pair<Long, ByteArray> {
        val savedTimeout = currentTimeoutMs
        val newTimeout = minOf(timeoutMs, MAX_TIMEOUT_MS)
        if (newTimeout != savedTimeout) currentTimeoutMs = newTimeout
        try {
            if (op != null) {
                trace(
                    "command op=0x%02x data len=%s wait_response=%d timeout=%.3f data=%s",
                    op, data.size, if (waitResponse) 1 else 0, timeoutMs / 1000.0, HexFormatter(data)
                )
                val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                header.put(0x00.toByte())
                header.put(op.toByte())
                header.putShort(data.size.toShort())
                header.putInt(chk.toInt())
                write(header.array() + data)
            }
            if (!waitResponse) return Pair(0L, ByteArray(0))
            repeat(100) {
                val p = read()
                if (p.size < 8) return@repeat
                val buf = ByteBuffer.wrap(p, 0, 8).order(ByteOrder.LITTLE_ENDIAN)
                val resp = buf.get().toInt() and 0xFF
                val opRet = buf.get().toInt() and 0xFF
                buf.short
                val vali = buf.int.toLong() and 0xFFFFFFFFL
                if (resp != 1) return@repeat
                val respData = p.copyOfRange(8, p.size)
                if (op == null || opRet == op) return Pair(vali, respData)
                if (respData.isNotEmpty() && (respData[0].toInt() and 0xFF) != 0 &&
                    respData.size > 1 && (respData[1].toInt() and 0xFF) == ROM_INVALID_RECV_MSG
                ) {
                    flushInput()
                    throw UnsupportedCommandError(this, op)
                }
            }
        } finally {
            if (newTimeout != savedTimeout) currentTimeoutMs = savedTimeout
        }
        throw FatalError("Response doesn't match request")
    }

    fun checkCommand(
        opDescription: String,
        op: Int? = null,
        data: ByteArray = ByteArray(0),
        chk: Long = 0,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): ByteArray {
        val (_, respData) = command(op, data, chk, timeoutMs = timeoutMs)
        if (respData.size < statusBytesLength) {
            throw FatalError("Failed to $opDescription. Only got ${respData.size} byte status response.")
        }
        val statusBytes = respData.copyOfRange(respData.size - statusBytesLength, respData.size)
        if ((statusBytes[0].toInt() and 0xFF) != 0) {
            throw FatalError.withResult("Failed to $opDescription", statusBytes)
        }
        return if (respData.size > statusBytesLength) respData.copyOfRange(0, respData.size - statusBytesLength)
        else ByteArray(0)
    }

    fun sync() {
        val payload = byteArrayOf(0x07, 0x07, 0x12, 0x20) + ByteArray(32) { 0x55.toByte() }
        val (v, _) = command(ESP_SYNC, payload, timeoutMs = SYNC_TIMEOUT_MS)
        syncStubDetected = v == 0L
        repeat(7) {
            val (v2, _) = command()
            syncStubDetected = syncStubDetected && v2 == 0L
        }
    }

    // ---- reset / DTR / RTS ----
    // Python always runs this sequence regardless of port type and simply relies
    // on pyserial's socket:// handler silently ignoring DTR/RTS; do the same here
    // instead of special-casing SocketPort.
    fun bootloaderReset(esp32r0Delay: Boolean = false, usbJtagSerial: Boolean = false) {
        if (usbJtagSerial) {
            port.setRTS(false); port.setDTR(false)
            Thread.sleep(100)
            port.setDTR(true); port.setRTS(false)
            Thread.sleep(100)
            port.setRTS(true); port.setDTR(false)
            port.setRTS(true)
            Thread.sleep(100)
            port.setDTR(false); port.setRTS(false)
        } else {
            port.setDTR(false); port.setRTS(true)
            Thread.sleep(100)
            if (esp32r0Delay) Thread.sleep(1200)
            port.setDTR(true); port.setRTS(false)
            if (esp32r0Delay) Thread.sleep(400)
            Thread.sleep(50)
            port.setDTR(false)
        }
    }

    private fun connectAttempt(mode: String = "default_reset", esp32r0Delay: Boolean = false, usbJtagSerial: Boolean = false): Exception? {
        if (mode != "no_reset") bootloaderReset(esp32r0Delay, usbJtagSerial)
        var lastError: Exception? = null
        repeat(5) {
            try {
                flushInput()
                flushOutput()
                sync()
                return null
            } catch (e: FatalError) {
                print(if (esp32r0Delay) "_" else ".")
                System.out.flush()
                Thread.sleep(50)
                lastError = e
            }
        }
        return lastError
    }

    open fun connect(mode: String = "default_reset", attempts: Int = DEFAULT_CONNECT_ATTEMPTS, detecting: Boolean = false) {
        print(colorConnecting("Connecting...")); System.out.flush()
        var lastError: Exception? = null
        val usbJtagSerial = mode == "usb_reset"
        try {
            val range = if (attempts > 0) 0 until attempts else 0 until Int.MAX_VALUE
            for (i in range) {
                lastError = connectAttempt(mode, esp32r0Delay = false, usbJtagSerial = usbJtagSerial)
                if (lastError == null) break
                lastError = connectAttempt(mode, esp32r0Delay = true, usbJtagSerial = usbJtagSerial)
                if (lastError == null) break
            }
        } finally {
            println()
        }
        if (lastError != null) throw FatalError("Failed to connect to $chipName: ${lastError.message}")
        if (!detecting) {
            try {
                val chipMagic = readReg(CHIP_DETECT_MAGIC_REG_ADDR)
                if (chipMagic !in chipDetectMagicValues()) {
                    val actually = chipRegistry.firstOrNull { chipMagic in it.first }
                    if (actually == null) {
                        println("WARNING: This chip doesn't appear to be a $chipName (chip magic value 0x%08x).".format(chipMagic))
                    } else {
                        throw FatalError("This chip is not a $chipName. Wrong --chip argument?")
                    }
                }
            } catch (e: UnsupportedCommandError) {
                secureDownloadMode = true
            }
            postConnect()
        }
    }

    open fun chipDetectMagicValues(): List<Long> = emptyList()
    open fun postConnect() { /* no-op */ }

    // ---- registers ----
    fun readReg(addr: Long, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Long {
        val (v, data) = command(ESP_READ_REG, packU32LE(addr), timeoutMs = timeoutMs)
        if (data.isNotEmpty() && (data[0].toInt() and 0xFF) != 0) {
            throw FatalError.withResult("Failed to read register address %08x".format(addr), data)
        }
        return v
    }

    fun writeReg(addr: Long, value: Long, mask: Long = 0xFFFFFFFFL, delayUs: Long = 0, delayAfterUs: Long = 0) {
        var cmd = packU32LE(addr, value, mask, delayUs)
        if (delayAfterUs > 0) cmd += packU32LE(UART_DATE_REG_ADDR, 0, 0, delayAfterUs)
        checkCommand("write target memory", ESP_WRITE_REG, cmd)
    }

    // ---- RAM download ----
    open fun memBegin(size: Long, blocks: Long, blocksize: Long, offset: Long) {
        if (isStub) {
            val stub = stubCode
            if (stub != null) {
                val loadStart = offset
                val loadEnd = offset + size
                val ranges = listOf(
                    stub.dataStart to (stub.dataStart + stub.data.size),
                    stub.textStart to (stub.textStart + stub.text.size)
                )
                for ((start, end) in ranges) {
                    if (loadStart < end && loadEnd > start) {
                        throw FatalError(
                            ("Software loader is resident at 0x%08x-0x%08x. Can't load binary at overlapping " +
                                "address range 0x%08x-0x%08x. Either change binary loading address, or use the " +
                                "--no-stub option to disable the software loader.").format(start, end, loadStart, loadEnd)
                        )
                    }
                }
            }
        }
        checkCommand("enter RAM download mode", ESP_MEM_BEGIN, packU32LE(size, blocks, blocksize, offset))
    }

    fun memBlock(data: ByteArray, seq: Long) {
        checkCommand(
            "write to target RAM", ESP_MEM_DATA,
            packU32LE(data.size.toLong(), seq, 0, 0) + data,
            checksum(data).toLong() and 0xFFFFFFFFL
        )
    }

    fun memFinish(entrypoint: Long = 0) {
        val timeout = if (isStub) DEFAULT_TIMEOUT_MS else 50
        val data = packU32LE(if (entrypoint == 0L) 1L else 0L, entrypoint)
        try {
            checkCommand("leave RAM download mode", ESP_MEM_END, data, timeoutMs = timeout)
        } catch (e: FatalError) {
            if (isStub) throw e
        }
    }

    // ---- flash download ----
    open fun getEraseSize(offset: Long, size: Long): Long = size

    fun flashBegin(size: Long, offset: Long, beginRomEncrypted: Boolean = false): Long {
        val numBlocks = (size + flashWriteSize - 1) / flashWriteSize
        val eraseSize = getEraseSize(offset, size)
        val timeout = if (isStub) DEFAULT_TIMEOUT_MS else timeoutPerMb(30000, size)
        var params = packU32LE(eraseSize, numBlocks, flashWriteSize, offset)
        if (needsEncryptedFlag && !isStub) {
            params += packU32LE(if (beginRomEncrypted) 1 else 0)
        }
        checkCommand("enter Flash download mode", ESP_FLASH_BEGIN, params, timeoutMs = timeout)
        return numBlocks
    }

    open val needsEncryptedFlag: Boolean = false

    fun flashBlock(data: ByteArray, seq: Long, timeoutMs: Int = DEFAULT_TIMEOUT_MS) {
        checkCommand(
            "write to target Flash after seq $seq", ESP_FLASH_DATA,
            packU32LE(data.size.toLong(), seq, 0, 0) + data,
            checksum(data).toLong() and 0xFFFFFFFFL,
            timeoutMs = timeoutMs
        )
    }

    fun flashFinish(reboot: Boolean = false) {
        checkCommand("leave Flash mode", ESP_FLASH_END, packU32LE(if (!reboot) 1 else 0))
    }

    fun run(reboot: Boolean = false) {
        flashBegin(0, 0)
        flashFinish(reboot)
    }

    fun flashId(): Long {
        val spiflashRdid = 0x9F
        return runSpiflashCommand(spiflashRdid, ByteArray(0), 24)
    }

    // ---- stub loader upload ----
    open fun runStub(): ESPLoader {
        if (syncStubDetected) {
            println(colorStatus("Stub is already running. No upload is necessary."))
            return toStub()
        }
        val stub = stubCode ?: loadStubFromJson(stubJsonName)?.also { stubCode = it }
        ?: throw FatalError("No stub loader available for $chipName (missing devices/$stubJsonName.json). Pass --no-stub to use the slow ROM loader instead.")

        println(colorize("Uploading stub...", "cyan"))
        for (field in listOf("text", "data")) {
            val (bytes, start) = if (field == "text") stub.text to stub.textStart else stub.data to stub.dataStart
            if (bytes.isNotEmpty()) {
                val length = bytes.size.toLong()
                val blocks = (length + ESP_RAM_BLOCK - 1) / ESP_RAM_BLOCK
                memBegin(length, blocks, ESP_RAM_BLOCK.toLong(), start)
                for (seq in 0 until blocks) {
                    val from = (seq * ESP_RAM_BLOCK).toInt()
                    val to = minOf(from + ESP_RAM_BLOCK, bytes.size)
                    memBlock(bytes.copyOfRange(from, to), seq)
                }
            }
        }
        println(colorize("Running stub...", "cyan"))
        memFinish(stub.entry)
        val p = read()
        if (String(p, Charsets.US_ASCII) != "OHAI") {
            throw FatalError("Failed to start stub. Unexpected response: ${hexify(p)}")
        }
        println(colorize("Stub running...", "cyan"))
        return toStub()
    }

    open fun toStub(): ESPLoader = throw NotImplementedInROMError(this, "toStub")

    // ---- misc stub/ESP32-only operations ----
    open fun flashMd5sum(addr: Long, size: Long): String {
        if (!isStub && this !is Esp32Rom) throw NotImplementedInROMError(this, "flashMd5sum")
        val timeout = timeoutPerMb(MD5_TIMEOUT_PER_MB_MS, size)
        val res = checkCommand("calculate md5sum", ESP_SPI_FLASH_MD5, packU32LE(addr, size, 0, 0), timeoutMs = timeout)
        return when (res.size) {
            32 -> String(res, Charsets.UTF_8)
            16 -> hexify(res).toLowerCase()
            else -> throw FatalError("MD5Sum command returned unexpected result: ${hexify(res)}")
        }
    }

    open fun changeBaud(baud: Int) {
        if (!isStub && this !is Esp32Rom) throw NotImplementedInROMError(this, "changeBaud")
        println("Changing baud rate to $baud")
        // Python always sends this command and always calls _set_port_baudrate(),
        // regardless of port type — pyserial's socket:// handler just silently
        // ignores the local baudrate assignment. Do the same here: the chip still
        // needs to be told to switch its UART speed even when we're talking to it
        // through a socket bridge, so don't skip the command for SocketPort.
        val secondArg = if (isStub) port.getBaudRate().toLong() else 0L
        command(ESP_CHANGE_BAUDRATE, packU32LE(baud.toLong(), secondArg))
        println("Changed.")
        port.setBaudRate(baud)
        Thread.sleep(50)
        flushInput()
    }

    open fun eraseFlash() {
        if (!isStub) throw NotImplementedInROMError(this, "eraseFlash")
        checkCommand("erase flash", ESP_ERASE_FLASH, timeoutMs = CHIP_ERASE_TIMEOUT_MS)
    }

    open fun eraseRegion(offset: Long, size: Long) {
        if (!isStub) throw NotImplementedInROMError(this, "eraseRegion")
        if (offset % FLASH_SECTOR_SIZE != 0L || size % FLASH_SECTOR_SIZE != 0L) {
            throw FatalError("Offset and size must be multiples of 4096")
        }
        checkCommand("erase region", ESP_ERASE_REGION, packU32LE(offset, size), timeoutMs = timeoutPerMb(30000, size))
    }

    open fun readFlashSlow(offset: Long, length: Long, progressFn: ((Long, Long) -> Unit)?): ByteArray {
        throw NotImplementedInROMError(this, "readFlashSlow")
    }

    fun readFlash(offset: Long, length: Long, progressFn: ((Long, Long) -> Unit)? = null): ByteArray {
        if (!isStub) return readFlashSlow(offset, length, progressFn)
        checkCommand("read flash", ESP_READ_FLASH, packU32LE(offset, length, FLASH_SECTOR_SIZE.toLong(), 64))
        var data = ByteArray(0)
        while (data.size < length) {
            val p = read()
            data += p
            if (data.size < length && p.size < FLASH_SECTOR_SIZE) {
                throw FatalError("Corrupt data, expected 0x%x bytes but received 0x%x bytes".format(FLASH_SECTOR_SIZE, p.size))
            }
            write(packU32LE(data.size.toLong()))
            if (progressFn != null && (data.size % 1024 == 0 || data.size.toLong() == length)) {
                progressFn(data.size.toLong(), length)
            }
        }
        progressFn?.invoke(data.size.toLong(), length)
        if (data.size.toLong() > length) throw FatalError("Read more than expected")
        val digestFrame = read()
        if (digestFrame.size != 16) throw FatalError("Expected digest, got: ${hexify(digestFrame)}")
        val expectedDigest = hexify(digestFrame).toUpperCase()
        val digest = MessageDigest.getInstance("MD5").digest(data)
        val digestHex = hexify(digest).toUpperCase()
        if (digestHex != expectedDigest) throw FatalError("Digest mismatch: expected $expectedDigest, got $digestHex")
        return data
    }

    fun flashSpiAttach(hspiArg: Long) {
        var arg = packU32LE(hspiArg)
        if (!isStub) arg += byteArrayOf(0, 0, 0, 0)
        checkCommand("configure SPI flash pins", ESP_SPI_ATTACH, arg)
    }

    fun flashSetParameters(size: Long) {
        val flId = 0L
        val blockSize = 64L * 1024
        val sectorSize = 4L * 1024
        val pageSize = 256L
        val statusMask = 0xffffL
        checkCommand(
            "set SPI params", ESP_SPI_SET_PARAMS,
            packU32LE(flId, size, blockSize, sectorSize, pageSize, statusMask)
        )
    }

    fun runSpiflashCommand(spiflashCommand: Int, data: ByteArray = ByteArray(0), readBits: Int = 0): Long {
        val base = spiRegBase
        val spiCmdReg = base + 0x00
        val spiUsrReg = base + spiUsrOffs
        val spiUsr1Reg = base + spiUsr1Offs
        val spiUsr2Reg = base + spiUsr2Offs
        val spiW0Reg = base + spiW0Offs

        fun setDataLengths(mosiBits: Int, misoBits: Int) {
            val mosiOffs = spiMosiDlenOffs
            val misoOffs = spiMisoDlenOffs
            if (mosiOffs != null && misoOffs != null) {
                if (mosiBits > 0) writeReg(base + mosiOffs, (mosiBits - 1).toLong())
                if (misoBits > 0) writeReg(base + misoOffs, (misoBits - 1).toLong())
            } else {
                val spiMosiBitlenS = 17
                val spiMisoBitlenS = 8
                val mosiMask = if (mosiBits == 0) 0L else (mosiBits - 1).toLong()
                val misoMask = if (misoBits == 0) 0L else (misoBits - 1).toLong()
                writeReg(spiUsr1Reg, (misoMask shl spiMisoBitlenS) or (mosiMask shl spiMosiBitlenS))
            }
        }

        val spiUsr2CommandLenShift = 28
        if (readBits > 32) throw FatalError("Reading more than 32 bits back from a SPI flash operation is unsupported")
        if (data.size > 64) throw FatalError("Writing more than 64 bytes of data with one SPI command is unsupported")
        val dataBits = data.size * 8
        val oldSpiUsr = readReg(spiUsrReg)
        val oldSpiUsr2 = readReg(spiUsr2Reg)
        var flags = (1L shl 31)
        if (readBits > 0) flags = flags or (1L shl 28)
        if (dataBits > 0) flags = flags or (1L shl 27)
        setDataLengths(dataBits, readBits)
        writeReg(spiUsrReg, flags)
        writeReg(spiUsr2Reg, ((7L shl spiUsr2CommandLenShift) or spiflashCommand.toLong()))
        if (dataBits == 0) {
            writeReg(spiW0Reg, 0)
        } else {
            val padded = padTo(data, 4, 0)
            var nextReg = spiW0Reg
            var i = 0
            while (i < padded.size) {
                val word = ByteBuffer.wrap(padded, i, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                writeReg(nextReg, word)
                nextReg += 4
                i += 4
            }
        }
        writeReg(spiCmdReg, 1L shl 18)
        var done = false
        for (i in 0 until 10) {
            if ((readReg(spiCmdReg) and (1L shl 18)) == 0L) { done = true; break }
        }
        if (!done) throw FatalError("SPI command did not complete in time")
        val status = readReg(spiW0Reg)
        writeReg(spiUsrReg, oldSpiUsr)
        writeReg(spiUsr2Reg, oldSpiUsr2)
        return status
    }

    fun readStatus(numBytes: Int = 2): Long {
        var status = 0L
        var shift = 0
        val cmds = listOf(0x05, 0x35, 0x15)
        for (cmd in cmds.take(numBytes)) {
            status += runSpiflashCommand(cmd, readBits = 8) shl shift
            shift += 8
        }
        return status
    }

    open fun getCrystalFreq(): Int {
        // Python always runs this same formula off self._port.baudrate regardless
        // of port type — for socket:// that's just whatever nominal value was last
        // assigned (see SocketPort.nominalBaud), same as here. Chips that don't
        // support this estimation (S2/S3/C3/C6) already override getCrystalFreq().
        val uartDiv = readReg(uartClkdivReg) and UART_CLKDIV_MASK
        val estXtal = port.getBaudRate().toDouble() * uartDiv / 1e6 / xtalClkDivider
        val normXtal = if (estXtal > 33) 40 else 26
        if (kotlin.math.abs(normXtal - estXtal) > 1) {
            println(
                "WARNING: Detected crystal freq %.2fMHz is quite different to normalized freq %dMHz. Unsupported crystal in use?"
                    .format(estXtal, normXtal)
            )
        }
        return normXtal
    }

    open fun getChipDescription(): String = chipName
    open fun getChipFeatures(): List<String> = listOf("Unknown")
    open fun readMac(): ByteArray = throw NotImplementedInROMError(this, "readMac")

    fun hardReset() {
        println(colorize("Hard resetting via RTS pin...", "cyan"))
        port.setRTS(true)
        Thread.sleep(100)
        port.setRTS(false)
    }

    fun softReset(stayInBootloader: Boolean) {
        if (!isStub) {
            if (stayInBootloader) return
            flashBegin(0, 0)
            flashFinish(false)
        } else {
            if (stayInBootloader) {
                flashBegin(0, 0)
                flashFinish(true)
            } else if (chipName != "ESP8266") {
                throw FatalError("Soft resetting is currently only supported on ESP8266")
            } else {
                command(ESP_RUN_USER_CODE, waitResponse = false)
            }
        }
    }
}

// =====================================================================
// Chip-specific ROM classes (unchanged, but use the new base)
// =====================================================================

open class Esp8266Rom : ESPLoader {
    constructor(port: SerialPortLike, baud: Int, traceEnabled: Boolean = false) : super(port, baud, traceEnabled)
    override val chipName = "ESP8266"
    override val statusBytesLength = STATUS_BYTES_LENGTH_DEFAULT
    override val spiRegBase = 0x60000200L
    override val spiUsrOffs = 0x1cL
    override val spiUsr1Offs = 0x20L
    override val spiUsr2Offs = 0x24L
    override val spiMosiDlenOffs: Long? = null
    override val spiMisoDlenOffs: Long? = null
    override val spiW0Offs = 0x40L
    override val uartClkdivReg = 0x60000014L
    override val xtalClkDivider = 2
    override val stubJsonName = "esp8266"

    override fun chipDetectMagicValues(): List<Long> = MAGIC_VALUES
    override fun toStub(): ESPLoader = Esp8266StubLoader(this)
    override fun getChipDescription(): String = "ESP8266EX"
    override fun getChipFeatures(): List<String> = listOf("WiFi")
    override fun getEraseSize(offset: Long, size: Long): Long {
        val sectorsPerBlock = 16L
        val sectorSize = FLASH_SECTOR_SIZE.toLong()
        val numSectors = (size + sectorSize - 1) / sectorSize
        val startSector = offset / sectorSize
        var headSectors = sectorsPerBlock - (startSector % sectorsPerBlock)
        if (numSectors < headSectors) headSectors = numSectors
        return if (numSectors < 2 * headSectors) (numSectors + 1) / 2 * sectorSize
        else (numSectors - headSectors) * sectorSize
    }

    override fun readMac(): ByteArray {
        val mac0 = readReg(0x3ff00050L)
        val mac1 = readReg(0x3ff00054L)
        val mac3 = readReg(0x3ff0005cL)
        val oui: Triple<Long, Long, Long> = when {
            mac3 != 0L -> Triple((mac3 shr 16) and 0xff, (mac3 shr 8) and 0xff, mac3 and 0xff)
            ((mac1 shr 16) and 0xff) == 0L -> Triple(0x18L, 0xfeL, 0x34L)
            ((mac1 shr 16) and 0xff) == 1L -> Triple(0xacL, 0xd0L, 0x74L)
            else -> throw FatalError("Unknown OUI")
        }
        return byteArrayOf(
            oui.first.toByte(), oui.second.toByte(), oui.third.toByte(),
            ((mac1 shr 8) and 0xff).toByte(), (mac1 and 0xff).toByte(), ((mac0 shr 24) and 0xff).toByte()
        )
    }

    companion object { val MAGIC_VALUES = listOf(0xfff0c101L) }
}

class Esp8266StubLoader(romLoader: ESPLoader) : Esp8266Rom(romLoader.port, 0, romLoader.traceEnabled) {
    override val isStub = true
    override val flashWriteSize = 0x4000L
    override fun getEraseSize(offset: Long, size: Long): Long = size
    override fun toStub(): ESPLoader = this
    init { flushInput() }
}

open class Esp32Rom : ESPLoader {
    constructor(port: SerialPortLike, baud: Int, traceEnabled: Boolean = false) : super(port, baud, traceEnabled)
    override val chipName = "ESP32"
    override val statusBytesLength = 4
    override val spiRegBase = 0x3ff42000L
    override val spiUsrOffs = 0x1cL
    override val spiUsr1Offs = 0x20L
    override val spiUsr2Offs = 0x24L
    override val spiMosiDlenOffs: Long? = 0x28L
    override val spiMisoDlenOffs: Long? = 0x2cL
    override val spiW0Offs = 0x80L
    override val uartClkdivReg = 0x3ff40014L
    override val xtalClkDivider = 1
    override val needsEncryptedFlag = true
    override val stubJsonName = "esp32"

    override fun chipDetectMagicValues(): List<Long> = MAGIC_VALUES
    override fun toStub(): ESPLoader = Esp32StubLoader(this)
    override fun getChipDescription(): String = "ESP32"
    override fun getChipFeatures(): List<String> = listOf("WiFi", "BT")
    override fun getEraseSize(offset: Long, size: Long): Long = size

    override fun readMac(): ByteArray {
        val w0 = readReg(0x3ff5a004L + 4 * 2)
        val w1 = readReg(0x3ff5a004L + 4 * 1)
        return packBEPair(w0, w1).copyOfRange(2, 8)
    }

    override fun readFlashSlow(offset: Long, length: Long, progressFn: ((Long, Long) -> Unit)?): ByteArray {
        val blockLen = 64L
        var data = ByteArray(0)
        while (data.size < length) {
            val thisLen = minOf(blockLen, length - data.size)
            val r = checkCommand("read flash block", ESP_READ_FLASH_SLOW, packU32LE(offset + data.size, thisLen))
            if (r.size < thisLen) throw FatalError("Expected $thisLen byte block, got ${r.size} bytes.")
            data += r.copyOfRange(0, thisLen.toInt())
            if (progressFn != null && (data.size % 1024 == 0 || data.size.toLong() == length)) {
                progressFn(data.size.toLong(), length)
            }
        }
        return data
    }

    companion object { val MAGIC_VALUES = listOf(0x00f01d83L) }
}

open class Esp32StubLoader(romLoader: ESPLoader) : Esp32Rom(romLoader.port, 0, romLoader.traceEnabled) {
    override val isStub = true
    override val flashWriteSize = 0x4000L
    override val statusBytesLength = STATUS_BYTES_LENGTH_DEFAULT
    override fun toStub(): ESPLoader = this
    init { flushInput() }
}

open class Esp32S2Rom : Esp32Rom {
    constructor(port: SerialPortLike, baud: Int, traceEnabled: Boolean = false) : super(port, baud, traceEnabled)
    override val chipName = "ESP32-S2"
    override val spiRegBase = 0x3f402000L
    override val spiUsrOffs = 0x18L
    override val spiUsr1Offs = 0x1cL
    override val spiUsr2Offs = 0x20L
    override val spiMosiDlenOffs: Long? = 0x24L
    override val spiMisoDlenOffs: Long? = 0x28L
    override val spiW0Offs = 0x58L
    override val uartClkdivReg = 0x3f400014L
    override val stubJsonName = "esp32s2"
    val macEfuseReg = 0x3f41A044L

    override fun chipDetectMagicValues(): List<Long> = MAGIC_VALUES
    override fun toStub(): ESPLoader = Esp32S2StubLoader(this)
    override fun getChipDescription(): String = "ESP32-S2"
    override fun getChipFeatures(): List<String> = listOf("WiFi")
    override fun getCrystalFreq(): Int = 40

    override fun readMac(): ByteArray {
        val mac0 = readReg(macEfuseReg)
        val mac1 = readReg(macEfuseReg + 4)
        return packBEPair(mac1, mac0).copyOfRange(2, 8)
    }

    companion object { val MAGIC_VALUES = listOf(0x000007c6L) }
}

class Esp32S2StubLoader(romLoader: ESPLoader) : Esp32S2Rom(romLoader.port, 0, romLoader.traceEnabled) {
    override val isStub = true
    override val flashWriteSize = 0x4000L
    override val statusBytesLength = STATUS_BYTES_LENGTH_DEFAULT
    override fun toStub(): ESPLoader = this
    init { flushInput() }
}

open class Esp32S3Rom : Esp32Rom {
    constructor(port: SerialPortLike, baud: Int, traceEnabled: Boolean = false) : super(port, baud, traceEnabled)
    override val chipName = "ESP32-S3"
    override val spiRegBase = 0x60002000L
    override val spiUsrOffs = 0x18L
    override val spiUsr1Offs = 0x1cL
    override val spiUsr2Offs = 0x20L
    override val spiMosiDlenOffs: Long? = 0x24L
    override val spiMisoDlenOffs: Long? = 0x28L
    override val spiW0Offs = 0x58L
    override val uartClkdivReg = 0x60000014L
    val macEfuseReg = 0x6001A044L

    override fun toStub(): ESPLoader = Esp32S3StubLoader(this)
    override fun getChipDescription(): String = "ESP32-S3"
    override fun getChipFeatures(): List<String> = listOf("WiFi", "BLE")
    override fun getCrystalFreq(): Int = 40

    override fun readMac(): ByteArray {
        val mac0 = readReg(macEfuseReg)
        val mac1 = readReg(macEfuseReg + 4)
        return packBEPair(mac1, mac0).copyOfRange(2, 8)
    }
}

class Esp32S3StubLoader(romLoader: ESPLoader) : Esp32S3Rom(romLoader.port, 0, romLoader.traceEnabled) {
    override val isStub = true
    override val flashWriteSize = 0x4000L
    override val statusBytesLength = STATUS_BYTES_LENGTH_DEFAULT
    override fun toStub(): ESPLoader = this
    init { flushInput() }
}

class Esp32S3Beta2Rom : Esp32S3Rom {
    constructor(port: SerialPortLike, baud: Int, traceEnabled: Boolean = false) : super(port, baud, traceEnabled)
    override val chipName = "ESP32-S3(beta2)"
    override val stubJsonName = "esp32s3beta2"
    override fun chipDetectMagicValues(): List<Long> = MAGIC_VALUES
    companion object { val MAGIC_VALUES = listOf(0xeb004136L) }
}

class Esp32S3Beta3Rom : Esp32S3Rom {
    constructor(port: SerialPortLike, baud: Int, traceEnabled: Boolean = false) : super(port, baud, traceEnabled)
    override val chipName = "ESP32-S3(beta3)"
    override val stubJsonName = "esp32s3beta3"
    override fun chipDetectMagicValues(): List<Long> = MAGIC_VALUES
    companion object { val MAGIC_VALUES = listOf(0x9L) }
}

open class Esp32C3Rom : Esp32Rom {
    constructor(port: SerialPortLike, baud: Int, traceEnabled: Boolean = false) : super(port, baud, traceEnabled)
    override val chipName = "ESP32-C3"
    override val spiRegBase = 0x60002000L
    override val spiUsrOffs = 0x18L
    override val spiUsr1Offs = 0x1cL
    override val spiUsr2Offs = 0x20L
    override val spiMosiDlenOffs: Long? = 0x24L
    override val spiMisoDlenOffs: Long? = 0x28L
    override val spiW0Offs = 0x58L
    override val uartClkdivReg = 0x60000014L
    override val stubJsonName = "esp32c3"
    val macEfuseReg = 0x60008844L

    override fun chipDetectMagicValues(): List<Long> = MAGIC_VALUES
    override fun toStub(): ESPLoader = Esp32C3StubLoader(this)
    override fun getChipDescription(): String = "ESP32-C3"
    override fun getChipFeatures(): List<String> = listOf("WiFi")
    override fun getCrystalFreq(): Int = 40

    override fun readMac(): ByteArray {
        val mac0 = readReg(macEfuseReg)
        val mac1 = readReg(macEfuseReg + 4)
        return packBEPair(mac1, mac0).copyOfRange(2, 8)
    }

    companion object { val MAGIC_VALUES = listOf(0x6921506fL, 0x1b31506fL) }
}

class Esp32C3StubLoader(romLoader: ESPLoader) : Esp32C3Rom(romLoader.port, 0, romLoader.traceEnabled) {
    override val isStub = true
    override val flashWriteSize = 0x4000L
    override val statusBytesLength = STATUS_BYTES_LENGTH_DEFAULT
    override fun toStub(): ESPLoader = this
    init { flushInput() }
}

class Esp32C6BetaRom : Esp32C3Rom {
    constructor(port: SerialPortLike, baud: Int, traceEnabled: Boolean = false) : super(port, baud, traceEnabled)
    override val chipName = "ESP32-C6 BETA"
    override val stubJsonName = "esp32c6beta"
    override fun chipDetectMagicValues(): List<Long> = MAGIC_VALUES
    override fun toStub(): ESPLoader = Esp32C3StubLoader(this)
    companion object { val MAGIC_VALUES = listOf(0x0da1806fL) }
}

// =====================================================================
// Chip registry and detection helpers
// =====================================================================

val chipRegistry: List<Pair<List<Long>, (SerialPortLike, Int, Boolean) -> ESPLoader>> = listOf(
    Esp8266Rom.MAGIC_VALUES to { p, b, t -> Esp8266Rom(p, b, t) },
    Esp32Rom.MAGIC_VALUES to { p, b, t -> Esp32Rom(p, b, t) },
    Esp32S2Rom.MAGIC_VALUES to { p, b, t -> Esp32S2Rom(p, b, t) },
    Esp32S3Beta2Rom.MAGIC_VALUES to { p, b, t -> Esp32S3Beta2Rom(p, b, t) },
    Esp32S3Beta3Rom.MAGIC_VALUES to { p, b, t -> Esp32S3Beta3Rom(p, b, t) },
    Esp32C3Rom.MAGIC_VALUES to { p, b, t -> Esp32C3Rom(p, b, t) },
    Esp32C6BetaRom.MAGIC_VALUES to { p, b, t -> Esp32C6BetaRom(p, b, t) }
)

val chipNameFactories: Map<String, (SerialPortLike, Int, Boolean) -> ESPLoader> = mapOf(
    "esp8266" to { p, b, t -> Esp8266Rom(p, b, t) },
    "esp32" to { p, b, t -> Esp32Rom(p, b, t) },
    "esp32s2" to { p, b, t -> Esp32S2Rom(p, b, t) },
    "esp32s3beta2" to { p, b, t -> Esp32S3Beta2Rom(p, b, t) },
    "esp32s3beta3" to { p, b, t -> Esp32S3Beta3Rom(p, b, t) },
    "esp32c3" to { p, b, t -> Esp32C3Rom(p, b, t) },
    "esp32c6beta" to { p, b, t -> Esp32C6BetaRom(p, b, t) }
)

fun detectChip(portLike: SerialPortLike, baud: Int, connectMode: String, traceEnabled: Boolean, connectAttempts: Int): ESPLoader {
    val detectPort = ESPLoader(portLike, baud, traceEnabled)
    detectPort.connect(connectMode, connectAttempts, detecting = true)
    print("Detecting chip type...")
    System.out.flush()
    var result: ESPLoader
    try {
        val chipMagic = detectPort.readReg(CHIP_DETECT_MAGIC_REG_ADDR)
        val match = chipRegistry.firstOrNull { chipMagic in it.first }
            ?: throw FatalError("Unexpected CHIP magic value 0x%08x.".format(chipMagic))
        val inst = match.second(portLike, baud, traceEnabled)
        inst.postConnect()
        print(" " + colorChipname(inst.chipName))
        result = inst
        if (detectPort.syncStubDetected) {
            result = inst.toStub()
            result.syncStubDetected = true
        }
    } finally {
        println()
    }
    return result
}

fun getDefaultConnectedDevice(
    portDescriptor: String,
    connectAttempts: Int,
    initialBaud: Int,
    chip: String,
    trace: Boolean,
    before: String
): ESPLoader? {
    return try {
        val portLike: SerialPortLike = if (portDescriptor.startsWith("socket://")) {
            val url = portDescriptor.substring("socket://".length)
            val (host, portStr) = url.split(":")
            val portNum = portStr.toInt()
            val socket = Socket(host, portNum)
            SocketPort(socket)
        } else {
            // Physical serial port: use jSerialComm
            val serialPort = SerialPort.getCommPort(portDescriptor)
            if (!serialPort.openPort()) throw FatalError("Failed to open port $portDescriptor")
            JSerialPort(serialPort)
        }
        val esp = if (chip == "auto") {
            detectChip(portLike, initialBaud, before, trace, connectAttempts)
        } else {
            val factory = chipNameFactories[chip] ?: throw FatalError("Unknown chip type: $chip")
            val inst = factory(portLike, initialBaud, trace)
            inst.connect(before, connectAttempts)
            inst
        }
        esp
    } catch (e: Exception) {
        println("Failed to connect: ${e.message}")
        null
    }
}

// =====================================================================
// Read-flash operation + CLI
// =====================================================================

fun readFlashToFile(esp: ESPLoader, address: Long, size: Long, filename: String, showProgress: Boolean) {
    val progressFn: ((Long, Long) -> Unit)? = if (!showProgress) null else { progress, length ->
        val msg = "$progress (${"%.0f".format(progress * 100.0 / length)} %)"
        val padding = if (progress == length) "\n" else "\b".repeat(msg.length)
        print(msg + padding)
        System.out.flush()
    }
    val start = System.nanoTime()
    val data = esp.readFlash(address, size, progressFn)
    val elapsed = (System.nanoTime() - start) / 1e9
    val addrStr = colorAddress("0x%x".format(address))
    val sizeStr = colorSize("${data.size}")
    printOverwrite(
        "Read %s bytes at %s in %.1f seconds (%.1f kbit/s)...".format(
            sizeStr, addrStr, elapsed, data.size / elapsed * 8 / 1000
        ),
        lastLine = true
    )
    File(filename).writeBytes(data)
}

data class CliArgs(
    var chip: String = "auto",
    var port: String? = null,
    var baud: Int = ESP_ROM_BAUD,
    var noStub: Boolean = false,
    var noProgress: Boolean = false,
    var trace: Boolean = false,
    var address: Long = 0,
    var size: Long = 0,
    var filename: String = ""
)

fun printUsage() {
    println(
        """
        esptool_read (Kotlin) v$VERSION

        Usage: esptool_read [options] <address> <size> <filename>

        Options:
          -c, --chip <auto|esp8266|esp32|esp32s2|esp32s3beta2|esp32s3beta3|esp32c3|esp32c6beta>
                              Target chip type (default: auto)
          -p, --port <path>   Serial port device, e.g. /dev/ttyUSB0 or COM3
                              or socket://host:port for TCP connection
          -b, --baud <n>      Serial port baud rate (default: $ESP_ROM_BAUD)
          --no-stub           Disable the stub loader (use the slow ROM read instead)
          --no-progress       Suppress progress output
          -t, --trace         Enable protocol trace output
          -h, --help          Show this help and exit
        """.trimIndent()
    )
}

fun parseArgs(argv: Array<String>): CliArgs {
    val args = CliArgs()
    val positional = mutableListOf<String>()
    var i = 0
    while (i < argv.size) {
        val a = argv[i]
        when {
            a == "-h" || a == "--help" -> { printUsage(); exitProcess(0) }
            a == "-c" || a == "--chip" -> { args.chip = argv[++i] }
            a.startsWith("--chip=") -> args.chip = a.substringAfter("=")
            a == "-p" || a == "--port" -> { args.port = argv[++i] }
            a.startsWith("--port=") -> args.port = a.substringAfter("=")
            a == "-b" || a == "--baud" -> { args.baud = argv[++i].toInt() }
            a.startsWith("--baud=") -> args.baud = a.substringAfter("=").toInt()
            a == "--no-stub" -> args.noStub = true
            a == "--no-progress" -> args.noProgress = true
            a == "-t" || a == "--trace" -> args.trace = true
            else -> positional.add(a)
        }
        i++
    }
    if (positional.size < 3) {
        System.err.println("Expected 3 positional arguments: <address> <size> <filename>")
        printUsage()
        exitProcess(2)
    }
    args.address = parseIntAuto(positional[0])
    args.size = parseIntAuto(positional[1])
    args.filename = positional[2]
    return args
}

fun runMain(argv: Array<String>) {
    val args = parseArgs(argv)

    if (args.port == null) {
        // List physical ports only; socket ports don't need listing.
        val ports = SerialPort.getCommPorts().map { it.systemPortName }.sorted()
        println("Found ${ports.size} serial ports")
        args.port = ports.firstOrNull() ?: throw FatalError("No serial ports found.")
    }

    val initialBaud = minOf(ESP_ROM_BAUD, args.baud)
    var esp = getDefaultConnectedDevice(
        args.port!!, DEFAULT_CONNECT_ATTEMPTS, initialBaud, args.chip, args.trace, before = "no_reset"
    ) ?: throw FatalError("Could not connect to any Espressif device.")

    if (!esp.secureDownloadMode) {
        val chipDesc = esp.getChipDescription()
        val features = esp.getChipFeatures().joinToString(", ")
        val crystal = esp.getCrystalFreq()
        println("Chip is ${colorStatus(chipDesc)}")
        println("Features: ${colorStatus(features)}")
        println("Crystal: ${crystal}MHz")
        val mac = esp.readMac()
        val macStr = mac.joinToString(":") { "%02x".format(it.toInt() and 0xFF) }
        println("MAC: $macStr")
    }

    if (!args.noStub) {
        if (esp.secureDownloadMode) {
            println("WARNING: Stub loader not supported in Secure Download Mode, using ROM.")
            args.noStub = true
        } else {
            esp = esp.runStub()
        }
    }

    if (args.baud != initialBaud) {
        try {
            esp.changeBaud(args.baud)
        } catch (e: NotImplementedInROMError) {
            println("WARNING: ROM doesn't support baud change. Keeping initial baud.")
        }
    }

    readFlashToFile(esp, args.address, args.size, args.filename, showProgress = !args.noProgress)

    esp.port.close()
}

fun main(args: Array<String>) {
    try {
        runMain(args)
    } catch (e: FatalError) {
        println("\nA fatal error occurred: ${e.message}")
        exitProcess(2)
    }
}
