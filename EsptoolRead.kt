/*
 * EsptoolRead.kt – minimal esptool (read‑flash only)
 *
 * Port of the reduced Python script. Requires jSerialComm and org.json.
 * Stub‑loader JSON files must be at:
 *   ./targets/stub_flasher/2/<chip>.json
 *   ./targets/stub_flasher/1/<chip>.json
 */

import com.fazecast.jSerialComm.SerialPort
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import kotlin.system.exitProcess

// ============================================================================
// Logger
// ============================================================================

interface TemplateLogger {
    fun print(vararg args: Any?, end: String = "\n", flush: Boolean = false)
    fun note(message: String)
    fun warning(message: String)
    fun error(message: String)
    fun stage(finish: Boolean = false)
    fun progressBar(curIter: Long, totalIters: Long, prefix: String = "", suffix: String = "", barLength: Int = 30)
    fun setVerbosity(verbosity: String)
}

class EsptoolLogger : TemplateLogger {
    var ansiRed = ""
    var ansiYellow = ""
    var ansiBlue = ""
    var ansiNormal = ""
    var ansiClear = ""
    var ansiLineUp = ""
    var ansiLineClear = ""

    private var stageActive = false
    private var newlineCount = 0
    private val keptLines = mutableListOf<String>()
    var smartFeatures = false
        private set
    private var verbosity: String? = null
    private var printAnyway = false

    init {
        setVerbosity("auto")
    }

    private fun setSmartFeatures(override: Boolean? = null) {
        if (override != null) {
            smartFeatures = override
        } else {
            val isTty = System.console() != null
            val term = (System.getenv("TERM") ?: "").lowercase()
            val termSupportsColor = term in setOf("xterm", "xterm-256color", "screen", "screen-256color", "linux", "vt100")
            val noColor = (System.getenv("NO_COLOR") ?: "").trim().lowercase() in setOf("1", "true", "yes")
            smartFeatures = isTty || (termSupportsColor && !noColor)
        }
        if (smartFeatures) {
            ansiRed = "\u001B[1;31m"
            ansiYellow = "\u001B[0;33m"
            ansiBlue = "\u001B[1;36m"
            ansiNormal = "\u001B[0m"
            ansiClear = "\u001B[K"
            ansiLineUp = "\u001B[1A"
            ansiLineClear = "\u001B[2K"
        } else {
            ansiRed = ""; ansiYellow = ""; ansiBlue = ""; ansiNormal = ""
            ansiClear = ""; ansiLineUp = ""; ansiLineClear = ""
        }
    }

    override fun print(vararg args: Any?, end: String, flush: Boolean) {
        printTo(System.out, *args, end = end)
    }

    fun printErr(vararg args: Any?, end: String = "\n") {
        printTo(System.err, *args, end = end)
    }

    private fun printTo(stream: java.io.PrintStream, vararg args: Any?, end: String) {
        if (verbosity == "silent" && !printAnyway) return
        val message = args.joinToString(" ") { it?.toString() ?: "None" }
        if (stageActive) {
            newlineCount += message.count { it == '\n' }
            if (end == "\n") newlineCount += 1
        }
        stream.print(message)
        stream.print(end)
        stream.flush()
        printAnyway = false
    }

    override fun note(message: String) {
        val formatted = "${ansiBlue}Note:${ansiNormal} $message"
        if (stageActive) keptLines.add(formatted)
        print(formatted)
    }

    override fun warning(message: String) {
        val formatted = "${ansiYellow}Warning:${ansiNormal} $message"
        if (stageActive) keptLines.add(formatted)
        print(formatted)
    }

    override fun error(message: String) {
        val formatted = "$ansiRed$message$ansiNormal"
        printAnyway = true
        printErr(formatted)
    }

    override fun stage(finish: Boolean) {
        if (finish) {
            if (!stageActive) return
            stageActive = false
            if (smartFeatures) {
                print("${ansiLineUp}${ansiLineClear}".repeat(newlineCount), end = "", flush = true)
                for (line in keptLines) print(line)
            }
            keptLines.clear()
            newlineCount = 0
        } else {
            stageActive = true
        }
    }

    override fun progressBar(curIter: Long, totalIters: Long, prefix: String, suffix: String, barLength: Int) {
        val filled = if (totalIters == 0L) barLength else (barLength * curIter / totalIters).toInt()
        val bar = when {
            filled == barLength -> "=".repeat(barLength)
            filled == 0 -> " ".repeat(barLength)
            else -> "=".repeat(filled - 1) + ">" + " ".repeat(barLength - filled)
        }
        val percent = String.format("%.1f", 100.0 * (curIter.toDouble() / totalIters.toDouble()))
        val end = if (!smartFeatures || curIter == totalIters) "\n" else ""
        print("\r$ansiClear$prefix[$bar] ${percent.padStart(5)}%$suffix ", end = end, flush = true)
    }

    override fun setVerbosity(verbosity: String) {
        if (verbosity == this.verbosity) return
        this.verbosity = verbosity
        when (verbosity) {
            "auto" -> setSmartFeatures()
            "verbose" -> setSmartFeatures(override = false)
            "silent" -> {}
            "compact" -> setSmartFeatures(override = true)
            else -> throw IllegalArgumentException("Invalid verbosity level: $verbosity")
        }
    }
}

val log = EsptoolLogger()

// ============================================================================
// Utilities
// ============================================================================

fun byteAt(bitstr: ByteArray, index: Int): Int = bitstr[index].toInt() and 0xFF

fun maskToShift(maskIn: Long): Int {
    var mask = maskIn
    var shift = 0
    while (mask and 0x1L == 0L) {
        shift += 1
        mask = mask shr 1
    }
    return shift
}

fun divRoundup(a: Long, b: Long): Long = (a + b - 1) / b

fun flashSizeBytes(size: String?): Long? {
    if (size == null) return null
    return when {
        size.contains("MB") -> size.substring(0, size.indexOf("MB")).toLong() * 1024 * 1024
        size.contains("KB") -> size.substring(0, size.indexOf("KB")).toLong() * 1024
        else -> throw FatalError("Unknown size $size")
    }
}

fun hexify(s: ByteArray, uppercase: Boolean = true): String {
    val fmt = if (uppercase) "%02X" else "%02x"
    return s.joinToString("") { String.format(fmt, it.toInt() and 0xFF) }
}

fun padTo(dataIn: ByteArray, alignment: Int, padCharacter: Byte = 0xFF.toByte()): ByteArray {
    val padMod = dataIn.size % alignment
    if (padMod == 0) return dataIn
    val padding = ByteArray(alignment - padMod) { padCharacter }
    return dataIn + padding
}

fun expandChipName(chipNameIn: String): String {
    var chipName = Regex("(esp32)(?!$)").replace(chipNameIn) { "${it.groupValues[1]}-" }
    chipName = Regex("(beta\\d*)").replace(chipName) { "(${it.groupValues[1]})" }
    chipName = Regex("^[^(]+").replace(chipName) { it.value.uppercase() }
    return chipName
}

fun stripChipName(chipName: String): String = Regex("[-()]").replace(chipName.lowercase(), "")

fun <K, V> getKeyFromValue(dict: Map<K, V>, value: V): K? {
    for ((k, v) in dict) if (v == value) return k
    return null
}

class PrintOnce(private val printCallback: (String) -> Unit) {
    private var alreadyPrinted = false
    operator fun invoke(text: String) {
        if (!alreadyPrinted) {
            printCallback(text)
            alreadyPrinted = true
        }
    }
}

open class FatalError(message: String) : RuntimeException(message) {
    companion object {
        private val ERR_DEFS: Map<Int, String> = mapOf(
            0x100 to "Undefined errors", 0x101 to "Invalid parameter", 0x102 to "Failed to malloc",
            0x103 to "Failed to send out message", 0x104 to "Failed to receive message",
            0x105 to "Invalid message format", 0x106 to "Wrong running result", 0x107 to "Checksum error",
            0x108 to "Flash write error", 0x109 to "Flash read error", 0x10A to "Flash read length error",
            0x10B to "Deflate failed", 0x10C to "Deflate Adler32 error", 0x10D to "Deflate parameter error",
            0x10E to "Invalid RAM binary size", 0x10F to "Invalid RAM binary address",
            0x164 to "Invalid parameter", 0x165 to "Invalid format", 0x166 to "Description too long",
            0x167 to "Bad encoding", 0x169 to "Insufficient storage", 0xC000 to "Bad data length",
            0xC100 to "Bad data checksum", 0xC200 to "Bad blocksize", 0xC300 to "Invalid command",
            0xC400 to "Failed SPI operation", 0xC500 to "Failed SPI unlock", 0xC600 to "Not in flash mode",
            0xC700 to "Inflate error", 0xC800 to "Not enough data", 0xC900 to "Too much data",
            0xCA00 to "NAND program failed", 0xCB00 to "NAND erase failed", 0xFF00 to "Command not implemented"
        )

        fun withResult(message: String, result: ByteArray): FatalError {
            val errCode = ((result[0].toInt() and 0xFF) shl 8) or (result[1].toInt() and 0xFF)
            val full = "$message (result was ${hexify(result)}: ${ERR_DEFS[errCode] ?: "Unknown result"})"
            return when (errCode) {
                0xCA00 -> NANDProgramFailed(full)
                0xCB00 -> NANDEraseFailed(full)
                else -> FatalError(full)
            }
        }
    }
}

class NotImplementedInROMError(bootloaderChipName: String, funcName: String) :
    FatalError("$bootloaderChipName ROM does not support function $funcName.")

class NotSupportedError(esp: ESPLoader, functionName: String) :
    FatalError("$functionName is not supported by ${esp.chipName}.")

open class NANDProgramFailed(message: String) : FatalError(message)
open class NANDEraseFailed(message: String) : FatalError(message)

class UnsupportedCommandError(esp: ESPLoader, op: Int) : RuntimeException(
    if (esp.secureDownloadMode) "This command (${hex(op)}) is not supported in Secure Download Mode"
    else "Invalid (unsupported) command ${hex(op)}"
)

fun hex(x: Long): String = "0x" + java.lang.Long.toHexString(x)
fun hex(x: Int): String = hex(x.toLong() and 0xFFFFFFFFL)
fun hexPad(x: Long, width: Int): String {
    val digits = width - 2
    return "0x" + java.lang.Long.toHexString(x).padStart(digits, '0')
}

fun md5Hex(data: ByteArray): String {
    val digest = MessageDigest.getInstance("MD5").digest(data)
    return digest.joinToString("") { String.format("%02x", it) }
}

// ============================================================================
// Config constants
// ============================================================================

const val DEFAULT_TIMEOUT = 3.0
const val CHIP_ERASE_TIMEOUT = 120.0
const val MAX_TIMEOUT = CHIP_ERASE_TIMEOUT * 2
const val SYNC_TIMEOUT = 0.1
const val MD5_TIMEOUT_PER_MB = 8.0
const val ERASE_REGION_TIMEOUT_PER_MB = 30.0
const val ERASE_WRITE_TIMEOUT_PER_MB = 40.0
const val MEM_END_ROM_TIMEOUT = 0.2
const val DEFAULT_SERIAL_WRITE_TIMEOUT = 10.0
const val DEFAULT_CONNECT_ATTEMPTS = 7
const val WRITE_BLOCK_ATTEMPTS = 3
const val DEFAULT_OPEN_PORT_ATTEMPTS = 1
const val DEFAULT_RESET_DELAY = 0.05
const val DEFAULT_PORT = "/dev/ttyUSB0"
const val ESP_ROM_BAUD = 115200
const val CHIP_DETECT_MAGIC_REG_ADDR = 0x40001000L

const val NAND_BLOCK_SIZE = 0x20000L
const val NAND_BLOCK_COUNT = 1024
const val NAND_TOTAL_SIZE = NAND_BLOCK_COUNT * NAND_BLOCK_SIZE
const val NAND_PAGES_PER_BLOCK = 64

const val TROUBLESHOOTING_GUIDE_URL = "https://docs.espressif.com/projects/esptool/en/latest/troubleshooting.html"

// ============================================================================
// Reset strategies
// ============================================================================

fun sleepSeconds(seconds: Double) {
    if (seconds <= 0) return
    Thread.sleep((seconds * 1000).toLong())
}

class PortIoctlError(val errnoLike: Int, message: String) : IOException(message)

abstract class ResetStrategy(
    protected val port: EspSerialPort,
    protected val resetDelay: Double = DEFAULT_RESET_DELAY,
    protected val flowControl: Boolean = false
) {
    companion object {
        val printOnce = PrintOnce { msg -> log.warning(msg) }
    }

    operator fun invoke() {
        for (retry in 2 downTo 0) {
            try {
                if (!port.isOpen()) port.open()
                reset()
                break
            } catch (e: PortIoctlError) {
                printOnce("Chip was NOT reset. Setting RTS/DTR lines is not supported for port '${port.name}'. Set --before and --after to 'no-reset' manually.")
                break
            } catch (e: IOException) {
                if (retry == 0) throw e
                port.close()
                sleepSeconds(0.5)
            }
        }
    }

    protected abstract fun reset()

    protected fun setDTR(state: Boolean) = port.setDTR(state)
    protected fun setRTS(state: Boolean) {
        port.setRTS(state)
        port.setDTR(port.dtr)
    }
    protected fun setDTRandRTS(dtr: Boolean = false, rts: Boolean = false) {
        port.setDTR(dtr)
        port.setRTS(rts)
    }
    protected fun setHUPCL(enabled: Boolean): Boolean = false
}

class ClassicReset(port: EspSerialPort, resetDelay: Double = DEFAULT_RESET_DELAY, flowControl: Boolean = false) :
    ResetStrategy(port, resetDelay, flowControl) {
    override fun reset() {
        setDTR(false); setRTS(true); sleepSeconds(0.1)
        setDTR(true); setRTS(false); sleepSeconds(resetDelay)
        if (!flowControl) setDTR(false)
    }
}

class UnixTightReset(port: EspSerialPort, resetDelay: Double = DEFAULT_RESET_DELAY, flowControl: Boolean = false) :
    ResetStrategy(port, resetDelay, flowControl) {
    override fun reset() {
        setDTRandRTS(false, false)
        setDTRandRTS(true, true)
        setDTRandRTS(false, true)
        sleepSeconds(0.1)
        setDTRandRTS(true, false)
        sleepSeconds(resetDelay)
        if (!flowControl) { setDTRandRTS(false, false); setDTR(false) }
    }
}

class USBJTAGSerialReset(port: EspSerialPort) : ResetStrategy(port) {
    override fun reset() {
        setRTS(false); setDTR(false); sleepSeconds(0.1)
        setDTR(true); setRTS(false); sleepSeconds(0.1)
        setRTS(true); setDTR(false); setRTS(true); sleepSeconds(0.1)
        setDTR(false); setRTS(false)
    }
}

class HardReset(port: EspSerialPort, private val usesUsb: Boolean = false, flowControl: Boolean = false) :
    ResetStrategy(port, flowControl = flowControl) {
    override fun reset() {
        if (flowControl) {
            val hasHupcl = setHUPCL(false)
            setDTR(false); setRTS(true); sleepSeconds(0.1)
            setDTR(true); sleepSeconds(0.1); setRTS(false)
            if (!hasHupcl) setDTR(false)
        } else {
            setRTS(true)
            if (usesUsb) { sleepSeconds(0.2); setRTS(false); sleepSeconds(0.2) }
            else { sleepSeconds(0.1); setRTS(false) }
        }
    }
}

class CustomReset(port: EspSerialPort, private val seqStr: String) : ResetStrategy(port) {
    override fun reset() {
        for (cmd in seqStr.split("|")) {
            if (cmd.isEmpty()) throw FatalError("Invalid custom reset sequence option format: empty command")
            val letter = cmd[0]
            val arg = cmd.substring(1)
            try {
                when (letter) {
                    'D' -> setDTR(parsePyBool(arg))
                    'R' -> setRTS(parsePyBool(arg))
                    'W' -> sleepSeconds(arg.toDouble())
                    'U' -> {
                        val parts = arg.split(",").map { it.trim() }
                        setDTRandRTS(parsePyBool(parts.getOrElse(0) { "False" }), parsePyBool(parts.getOrElse(1) { "False" }))
                    }
                    else -> throw FatalError("Invalid custom reset sequence option format: unknown command '$letter'")
                }
            } catch (e: NumberFormatException) {
                throw FatalError("Invalid custom reset sequence option format: $e")
            }
        }
    }
    private fun parsePyBool(s: String): Boolean = s.trim().equals("True", ignoreCase = true) || s.trim() == "1"
}

// ============================================================================
// Serial port wrapper
// ============================================================================

class EspSerialPort(val devicePath: String) {
    private val sp: SerialPort = SerialPort.getCommPort(devicePath)
    var dtr: Boolean = false
        private set

    var timeout: Double? = null
        set(value) {
            field = value
            applyTimeouts()
        }
    var writeTimeout: Double? = null

    val name: String get() = devicePath
    val port: String get() = devicePath

    private fun applyTimeouts() {
        val readMs = when (val t = timeout) {
            null -> 0
            else -> (t * 1000).toInt().coerceAtLeast(0)
        }
        val mode = if (timeout == null) SerialPort.TIMEOUT_READ_BLOCKING else SerialPort.TIMEOUT_READ_SEMI_BLOCKING
        sp.setComPortTimeouts(mode or SerialPort.TIMEOUT_WRITE_BLOCKING, readMs, 0)
    }

    fun isOpen(): Boolean = sp.isOpen
    fun open() {
        if (!sp.isOpen) {
            if (!sp.openPort()) throw IOException("Could not open $devicePath, the port is busy or doesn't exist.")
        }
        applyTimeouts()
    }
    fun close() { if (sp.isOpen) sp.closePort() }

    var baudrate: Int
        get() = sp.baudRate
        set(value) {
            if (!sp.setBaudRate(value)) throw FatalError("Failed to set baud rate $value. The driver may not support this rate.")
        }

    fun setDTR(state: Boolean) {
        dtr = state
        try { if (state) sp.setDTR() else sp.clearDTR() } catch (e: Exception) { throw PortIoctlError(-1, "Setting DTR is not supported for port '$devicePath'") }
    }

    fun setRTS(state: Boolean) {
        try { if (state) sp.setRTS() else sp.clearRTS() } catch (e: Exception) { throw PortIoctlError(-1, "Setting RTS is not supported for port '$devicePath'") }
    }

    fun write(data: ByteArray) {
        val out = sp.outputStream ?: throw IOException("Port not open: $devicePath")
        out.write(data); out.flush()
    }
    fun read(n: Int): ByteArray {
        if (n <= 0) return ByteArray(0)
        val ins = sp.inputStream ?: throw IOException("Port not open: $devicePath")
        val buf = ByteArray(n)
        val got = ins.read(buf, 0, n)
        return if (got <= 0) ByteArray(0) else buf.copyOf(got)
    }
    fun inWaiting(): Int = sp.bytesAvailable().coerceAtLeast(0)
    fun flushInput() { sp.flushIOBuffers() }
    fun flushOutput() { sp.flushIOBuffers() }
    fun resetInputBuffer() = flushInput()
}

// ============================================================================
// SLIP reader
// ============================================================================

class SlipReader(private val port: EspSerialPort, private val traceFunction: (String, Boolean) -> Unit) {
    private fun trace(msg: String) = traceFunction(msg, false)

    private fun detectPanicHandler(input: ByteArray) {
        val text = String(input, Charsets.ISO_8859_1)
        val guru = Regex("G?uru Meditation Error: (?:Core \\d panic'ed \\(([a-zA-Z ]*)\\))?", RegexOption.DOT_MATCHES_ALL)
        val fatal = Regex("F?atal exception \\(\\d+\\): (?:([a-zA-Z ]*)?.*epc)?", RegexOption.DOT_MATCHES_ALL)
        val combined = Regex("(?:${guru.pattern}|${fatal.pattern})", RegexOption.DOT_MATCHES_ALL)
        val m = combined.find(text) ?: return
        val causes = listOfNotNull(m.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }, m.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() })
        val causeStr = if (causes.isNotEmpty()) " (${causes[0]})" else ""
        throw FatalError("Guru Meditation Error detected$causeStr.")
    }

    fun next(): ByteArray {
        var partialPacket: ByteArrayOutputStream? = null
        var inEscape = false
        var successfulSlip = false
        while (true) {
            val waiting = port.inWaiting()
            val readBytes = port.read(if (waiting == 0) 1 else waiting)
            if (readBytes.isEmpty()) {
                val msg = if (partialPacket == null) {
                    if (successfulSlip) "Serial data stream stopped: Possible serial noise or corruption." else "No serial data received."
                } else {
                    "Packet content transfer stopped (received ${partialPacket.size()} bytes)."
                }
                trace(msg); throw FatalError(msg)
            }
            trace("${"Read ${readBytes.size} bytes:".padEnd(21)} ${HexFormatter(readBytes)}")
            for (b in readBytes) {
                if (partialPacket == null) {
                    if (b == 0xC0.toByte()) partialPacket = ByteArrayOutputStream()
                    else {
                        trace("Read invalid data: ${HexFormatter(readBytes)}")
                        val remaining = port.read(port.inWaiting())
                        trace("Remaining data in serial buffer: ${HexFormatter(remaining)}")
                        detectPanicHandler(readBytes + remaining)
                        throw FatalError("Invalid head of packet (0x${hexify(byteArrayOf(b), false)}): Possible serial noise or corruption.")
                    }
                } else if (inEscape) {
                    inEscape = false
                    when (b) {
                        0xDC.toByte() -> partialPacket.write(0xC0)
                        0xDD.toByte() -> partialPacket.write(0xDB)
                        else -> {
                            trace("Read invalid data: ${HexFormatter(readBytes)}")
                            val remaining = port.read(port.inWaiting())
                            trace("Remaining data in serial buffer: ${HexFormatter(remaining)}")
                            detectPanicHandler(readBytes + remaining)
                            throw FatalError("Invalid SLIP escape (0xdb, 0x${hexify(byteArrayOf(b), false)}).")
                        }
                    }
                } else if (b == 0xDB.toByte()) {
                    inEscape = true
                } else if (b == 0xC0.toByte()) {
                    val packet = partialPacket.toByteArray()
                    trace("Received full packet: ${HexFormatter(packet)}")
                    return packet
                } else {
                    partialPacket.write(b.toInt())
                }
            }
        }
    }
}

class HexFormatter(private val s: ByteArray, private val autoSplit: Boolean = true) {
    override fun toString(): String {
        if (autoSplit && s.size > 16) {
            val sb = StringBuilder()
            var rest = s
            while (rest.isNotEmpty()) {
                val line = rest.copyOfRange(0, minOf(16, rest.size))
                val asciiLine = line.map { c ->
                    val ch = (c.toInt() and 0xFF).toChar()
                    if (ch == ' ' || (ch.code in 33..126)) ch else '.'
                }.joinToString("")
                rest = if (rest.size > 16) rest.copyOfRange(16, rest.size) else ByteArray(0)
                val left = if (line.size > 8) line.copyOfRange(0, 8) else line
                val right = if (line.size > 8) line.copyOfRange(8, line.size) else ByteArray(0)
                sb.append("\n    ").append(hexify(left, false).padEnd(16)).append(" ").append(hexify(right, false).padEnd(16)).append(" | ").append(asciiLine)
            }
            return sb.toString()
        }
        return hexify(s, false)
    }
}

// ============================================================================
// StubFlasher
// ============================================================================

class StubFlasher(target: ESPLoader, plugins: List<String>? = null) {
    companion object {
        var stubSubdirs: MutableList<String> = mutableListOf("2", "1")
        var stubVersionExplicit: Boolean = false

        fun setStubSubdir(subdir: String) {
            stubSubdirs = (mutableListOf(subdir) + stubSubdirs.filter { it != subdir }).toMutableList()
            stubVersionExplicit = true
        }

        val stubDir: File by lazy {
            val loc = try {
                File(StubFlasher::class.java.protectionDomain.codeSource.location.toURI())
            } catch (e: Exception) {
                File(".")
            }
            val baseDir = if (loc.isFile) loc.parentFile else loc
            File(baseDir, "targets/stub_flasher")
        }
    }

    val text: ByteArray
    val textStart: Long
    val entry: Long
    val data: ByteArray?
    val dataStart: Long?
    val bssStart: Long?
    val pluginSegments: MutableList<Pair<Long, ByteArray>> = mutableListOf()

    init {
        val jsonName = target.stubJsonName()
        val jsonFile = getJsonPath(jsonName, target.chipName)
        val stub = JSONObject(jsonFile.readText())
        text = Base64.getDecoder().decode(stub.getString("text"))
        textStart = stub.getLong("text_start")
        entry = stub.getLong("entry")
        data = if (stub.has("data")) Base64.getDecoder().decode(stub.getString("data")) else null
        dataStart = if (stub.has("data_start")) stub.getLong("data_start") else null
        bssStart = if (stub.has("bss_start")) stub.getLong("bss_start") else null
        if (plugins != null && plugins.isNotEmpty()) {
            if (stub.has("plugin_table_offset")) applyPlugins(stub, plugins, target.chipName)
            else throw FatalError("${target.chipName} stub does not support plugins.")
        }
    }

    private var mutableData: ByteArray? = data

    private fun applyPlugins(stub: JSONObject, plugins: List<String>, chipName: String) {
        val fptOffset = stub.getLong("plugin_table_offset")
        val firstOpcode = if (stub.has("plugin_first_opcode")) stub.getInt("plugin_first_opcode") else 0xD5
        var pluginTextKb = 0.0
        val workingData = (mutableData ?: ByteArray(0)).copyOf()
        val buf = ByteBuffer.wrap(workingData).order(ByteOrder.LITTLE_ENDIAN)
        val pluginsObj = if (stub.has("plugins")) stub.getJSONObject("plugins") else JSONObject()
        var extra = ByteArray(0)
        for (name in plugins) {
            if (!pluginsObj.has(name)) throw FatalError("Plugin '$name' not found in $chipName stub.")
            val pinfo = pluginsObj.getJSONObject(name)
            val ptext = Base64.getDecoder().decode(pinfo.getString("text"))
            val ptextStart = pinfo.getLong("text_start")
            pluginSegments.add(ptextStart to ptext)
            pluginTextKb += ptext.size / 1024.0
            val handlers = pinfo.getJSONObject("handlers")
            for (opcodeStr in handlers.keys()) {
                val opcode = opcodeStr.removePrefix("0x").removePrefix("0X").toInt(16)
                val idx = opcode - firstOpcode
                val handlerOffset = handlers.getLong(opcodeStr)
                val fptEntryAddr = ptextStart + handlerOffset
                val entryOff = (fptOffset + idx * 4).toInt()
                if (entryOff + 4 <= buf.capacity()) buf.putInt(entryOff, fptEntryAddr.toInt())
            }
            val bssSize = if (pinfo.has("bss_size")) pinfo.getInt("bss_size") else 0
            if (bssSize > 0) extra += ByteArray(bssSize)
        }
        mutableData = buf.array() + extra
        val baseTextKb = text.size / 1024.0
        val pluginsMsg = if (pluginSegments.isNotEmpty()) " + ${"%.1f".format(pluginTextKb)} KB (${plugins.joinToString(", ")})" else ""
        log.print("Stub: ${"%.1f".format(baseTextKb)} KB (base)$pluginsMsg")
    }

    fun effectiveData(): ByteArray? = mutableData

    private fun getJsonPath(jsonName: String, chipName: String): File {
        for ((i, subdir) in stubSubdirs.withIndex()) {
            val jsonPath = File(File(stubDir, subdir), jsonName)
            if (jsonPath.exists()) {
                if (i > 0 && stubVersionExplicit) log.warning("$chipName stub version ${stubSubdirs[0]} doesn't exist, using $subdir instead.")
                if (subdir == "1") log.note("Using the deprecated legacy stub flasher. Support for this stub will be removed in a future release.")
                return jsonPath
            }
        }
        throw FatalError("Flasher stub data is missing for $chipName. Reinstall esptool or pass --no-stub.")
    }
}

// ============================================================================
// Pack / unpack helpers
// ============================================================================

fun packLEInt32s(vararg values: Long): ByteArray {
    val buf = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    for (v in values) buf.putInt(v.toInt())
    return buf.array()
}

fun packCmdHeader(b0: Int, op: Int, dataLen: Int, chk: Long): ByteArray {
    val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
    buf.put(b0.toByte()); buf.put(op.toByte()); buf.putShort(dataLen.toShort()); buf.putInt(chk.toInt())
    return buf.array()
}

fun unpackU32LE(data: ByteArray, offset: Int = 0): Long =
    ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

val ESP_CMDS: Map<String, Int> = mapOf(
    "FLASH_BEGIN" to 0x02, "FLASH_DATA" to 0x03, "FLASH_END" to 0x04,
    "MEM_BEGIN" to 0x05, "MEM_END" to 0x06, "MEM_DATA" to 0x07,
    "SYNC" to 0x08, "WRITE_REG" to 0x09, "READ_REG" to 0x0A,
    "SPI_SET_PARAMS" to 0x0B, "SPI_ATTACH" to 0x0D, "READ_FLASH_SLOW" to 0x0E,
    "CHANGE_BAUDRATE" to 0x0F, "FLASH_DEFL_BEGIN" to 0x10,
    "FLASH_DEFL_DATA" to 0x11, "FLASH_DEFL_END" to 0x12,
    "SPI_FLASH_MD5" to 0x13, "GET_SECURITY_INFO" to 0x14,
    "ERASE_FLASH" to 0xD0, "ERASE_REGION" to 0xD1, "READ_FLASH" to 0xD2,
    "RUN_USER_CODE" to 0xD3, "FLASH_ENCRYPT_DATA" to 0xD4,
    "SPI_NAND_ATTACH" to 0xD5, "SPI_NAND_READ_SPARE" to 0xD6,
    "SPI_NAND_WRITE_SPARE" to 0xD7, "SPI_NAND_READ_FLASH" to 0xD8,
    "SPI_NAND_WRITE_FLASH_BEGIN" to 0xD9,
    "SPI_NAND_WRITE_FLASH_DATA" to 0xDA,
    "SPI_NAND_ERASE_FLASH" to 0xDB,
    "SPI_NAND_ERASE_REGION" to 0xDC,
    "SPI_NAND_READ_PAGE_DEBUG" to 0xDD,
    "SPI_NAND_WRITE_FLASH_END" to 0xDE
)

fun timeoutPerMb(secondsPerMb: Double, sizeBytes: Long): Double {
    val result = secondsPerMb * (sizeBytes / 1e6)
    return if (result < DEFAULT_TIMEOUT) DEFAULT_TIMEOUT else result
}

// ============================================================================
// ESPLoader – main class
// ============================================================================

data class ChipInfo(
    val chipName: String,
    val imageChipId: Int? = null,
    val usesMagicValue: Boolean = true,
    val magicValue: Long? = null,
    val spiRegBase: Long = 0L,
    val spiUsrOffs: Long = 0L,
    val spiUsr1Offs: Long = 0L,
    val spiUsr2Offs: Long = 0L,
    val spiMosiDlenOffs: Long? = null,
    val spiMisoDlenOffs: Long? = null,
    val spiW0Offs: Long = 0L,
    val spiAddrRegMsb: Boolean = true,
    val uartClkdivReg: Long = 0x3FF40014L,
    val xtalClkDivider: Int = 1,
    val flashSizes: Map<String, Long> = emptyMap(),
    val flashFrequencies: Map<String, Long> = emptyMap(),
    val bootloaderFlashOffset: Long = 0L,
    val uartDateRegAddr: Long = 0x60000078L,
    val uartClkdivMask: Long = 0xFFFFFL,
    val espRamBlock: Long = 0x1800L,
    val flashWriteSize: Long = 0x400L,
    val flashSectorSize: Long = 0x1000L,
    val iromMapStart: Long = 0x40200000L,
    val iromMapEnd: Long = 0x40300000L,
    val memoryMap: List<MemRegion> = emptyList(),
    val uf2FamilyId: Long = 0L,
    val flashEncryptedWriteAlign: Int = 16,
    val writeFlashAttempts: Int = 2,
    val stubFactory: ((ESPLoader) -> ESPLoader)? = null
)

data class MemRegion(val start: Long, val end: Long, val name: String)

open class ESPLoader(
    private var chipInfo: ChipInfo,
    private val portPath: String,
    private val baud: Int,
    private val traceEnabled: Boolean = false
) {
    // ---- public properties ----
    var chipName: String = chipInfo.chipName
        protected set
    var imageChipId: Int? = chipInfo.imageChipId
    var usesMagicValue: Boolean = chipInfo.usesMagicValue
    var magicValue: Long? = chipInfo.magicValue
    var secureDownloadMode: Boolean = false
    var stubIsDisabled: Boolean = false
    var syncStubDetected: Boolean = false
    val cache = mutableMapOf<String, Any?>(
        "flash_id" to null,
        "usb_vid" to null,
        "usb_pid" to null,
        "security_info" to null
    )
    var espRamBlock: Long = chipInfo.espRamBlock
    var flashWriteSize: Long = chipInfo.flashWriteSize
    val flashSectorSize: Long = chipInfo.flashSectorSize
    val uartDateRegAddr: Long = chipInfo.uartDateRegAddr
    val spiAddrRegMsb: Boolean = chipInfo.spiAddrRegMsb
    val uartClkdivMask: Long = chipInfo.uartClkdivMask
    val iromMapStart: Long = chipInfo.iromMapStart
    val iromMapEnd: Long = chipInfo.iromMapEnd
    val bootloaderFlashOffset: Long = chipInfo.bootloaderFlashOffset
    val writeFlashAttempts: Int = chipInfo.writeFlashAttempts
    val flashEncryptedWriteAlign: Int = chipInfo.flashEncryptedWriteAlign
    val flashSizes: Map<String, Long> = chipInfo.flashSizes
    val flashFrequencies: Map<String, Long> = chipInfo.flashFrequencies
    val spiRegBase: Long = chipInfo.spiRegBase
    val spiUsrOffs: Long = chipInfo.spiUsrOffs
    val spiUsr1Offs: Long = chipInfo.spiUsr1Offs
    val spiUsr2Offs: Long = chipInfo.spiUsr2Offs
    val spiMosiDlenOffs: Long? = chipInfo.spiMosiDlenOffs
    val spiMisoDlenOffs: Long? = chipInfo.spiMisoDlenOffs
    val spiW0Offs: Long = chipInfo.spiW0Offs
    val uartClkdivReg: Long = chipInfo.uartClkdivReg
    val xtalClkDivider: Int = chipInfo.xtalClkDivider
    val memoryMap: List<MemRegion> = chipInfo.memoryMap
    val uf2FamilyId: Long = chipInfo.uf2FamilyId
    var isStub: Boolean = false
        protected set

    lateinit var port: EspSerialPort
        protected set
    private lateinit var slipReader: SlipReader
    private var lastTraceTime: Double? = null
    protected val traceEnabledFlag = traceEnabled

    init {
        port = EspSerialPort(portPath)
        try { port.open() } catch (e: Exception) {
            val hints = listOf(
                Regex("Errno 2|FileNotFoundError", RegexOption.IGNORE_CASE) to "Check if the port is correct and ESP connected",
                Regex("Access is denied", RegexOption.IGNORE_CASE) to "Check if the port is not used by another task"
            )
            var hint = ""
            val msg = e.message ?: ""
            for ((re, h) in hints) if (re.containsMatchIn(msg)) { hint = "\nHint: $h\n"; break }
            throw FatalError("Could not open $portPath, the port is busy or doesn't exist.\n($msg)\n$hint")
        }
        setPortBaudrate(baud)
        port.writeTimeout = DEFAULT_SERIAL_WRITE_TIMEOUT
        slipReader = SlipReader(port) { msg, nl -> trace(msg, nl) }
    }

    // ---- stub view constructor ----
    protected constructor(romLoader: ESPLoader, chipInfoOverride: ChipInfo? = null) : this(
        chipInfo = chipInfoOverride ?: romLoader.chipInfo,
        portPath = romLoader.portPath,
        baud = romLoader.baud,
        traceEnabled = romLoader.traceEnabledFlag
    ) {
        secureDownloadMode = romLoader.secureDownloadMode
        syncStubDetected = romLoader.syncStubDetected
        cache.putAll(romLoader.cache)
        isStub = true
        port = romLoader.port
        slipReader = SlipReader(port) { msg, nl -> trace(msg, nl) }
        flushInput()
    }

    private fun setPortBaudrate(baud: Int) {
        try { port.baudrate = baud } catch (e: Exception) {
            throw FatalError("Failed to set baud rate $baud. The driver may not support this rate.")
        }
    }

    fun close() { port.close() }

    open fun stubJsonName(): String = "${stripChipName(chipName)}.json"

    fun trace(message: String, newline: Boolean = false) {
        if (!traceEnabledFlag) return
        val now = System.currentTimeMillis() / 1000.0
        val delta = lastTraceTime?.let { now - it } ?: 0.0
        lastTraceTime = now
        log.print(if (newline) "\n" else "", " TRACE +${"%.3f".format(delta)}  $message")
    }

    fun checksum(data: ByteArray, stateIn: Int = 0xEF): Int {
        var state = stateIn
        for (b in data) state = state xor (b.toInt() and 0xFF)
        return state
    }

    fun command(op: Int? = null, data: ByteArray = ByteArray(0), chk: Long = 0, waitResponse: Boolean = true, timeout: Double = DEFAULT_TIMEOUT): Pair<Long, ByteArray>? {
        val savedTimeout = port.timeout
        val newTimeout = minOf(timeout, MAX_TIMEOUT)
        if (newTimeout != savedTimeout) port.timeout = newTimeout
        try {
            if (op != null) {
                trace("--- Cmd ${getKeyFromValue(ESP_CMDS, op) ?: "?"} (${String.format("0x%02x", op)}) | data_len ${data.size} | wait_response ${if (waitResponse) 1 else 0} | timeout ${"%.3f".format(timeout)} | data ${HexFormatter(data)} ---", true)
                val pkt = packCmdHeader(0x00, op, data.size, chk) + data
                write(pkt)
            }
            if (!waitResponse) return null
            for (retry in 0 until 100) {
                val p = read()
                if (p.size < 8) continue
                val resp = p[0].toInt() and 0xFF
                val opRet = p[1].toInt() and 0xFF
                val valUnsigned = unpackU32LE(p, 4)
                if (resp != 1) continue
                val respData = p.copyOfRange(8, p.size)
                if (op == null || opRet == op) return valUnsigned to respData
                if (byteAt(respData, 0) != 0 && byteAt(respData, 1) == 0x05) {
                    sleepSeconds(0.2)
                    val origTimeout = port.timeout
                    port.timeout = 0.001
                    port.read(14 * 8)
                    port.timeout = origTimeout
                    flushInput()
                    throw UnsupportedCommandError(this, op)
                }
            }
        } finally {
            if (newTimeout != savedTimeout) port.timeout = savedTimeout
        }
        throw FatalError("Response doesn't match request.")
    }

    fun read(): ByteArray {
        try {
            return slipReader.next()
        } catch (e: FatalError) {
            throw e
        }
    }

    fun write(packet: ByteArray) {
        val out = ByteArrayOutputStream()
        out.write(0xC0)
        for (b in packet) {
            when (b.toInt() and 0xFF) {
                0xDB -> { out.write(0xDB); out.write(0xDD) }
                0xC0 -> { out.write(0xDB); out.write(0xDC) }
                else -> out.write(b.toInt())
            }
        }
        out.write(0xC0)
        val buf = out.toByteArray()
        trace("${"Write ${buf.size} bytes:".padEnd(21)} ${HexFormatter(buf)}", false)
        port.write(buf)
    }

    fun flushInput() {
        port.flushInput()
        slipReader = SlipReader(port) { msg, nl -> trace(msg, nl) }
    }

    data class CheckedResult(val value: Long, val data: ByteArray?)

    fun checkCommand(opDescription: String, op: Int? = null, data: ByteArray = ByteArray(0), chk: Long = 0, respDataLen: Int = 0, timeout: Double = DEFAULT_TIMEOUT): CheckedResult {
        val STATUS_BYTES_LENGTH = 2
        val (valUnsigned, respData) = command(op, data, chk, timeout = timeout) ?: throw FatalError("No response")
        if (respData.size < respDataLen + STATUS_BYTES_LENGTH) {
            val statusBytes = respData.copyOfRange(0, minOf(2, respData.size))
            if (statusBytes.isNotEmpty() && statusBytes[0].toInt() != 0) {
                throw FatalError.withResult("Failed to $opDescription", statusBytes)
            } else {
                throw FatalError("Failed to $opDescription. Only got ${respData.size} byte status response.")
            }
        }
        val statusBytes = respData.copyOfRange(respDataLen, respDataLen + STATUS_BYTES_LENGTH)
        if (statusBytes[0].toInt() != 0) throw FatalError.withResult("Failed to $opDescription", statusBytes)
        return if (respDataLen > 0) CheckedResult(valUnsigned, respData.copyOfRange(0, respDataLen))
        else CheckedResult(valUnsigned, null)
    }

    fun sync() {
        val (v, _) = command(ESP_CMDS["SYNC"], byteArrayOf(0x07, 0x07, 0x12, 0x20) + ByteArray(32) { 0x55.toByte() }, timeout = SYNC_TIMEOUT)!!
        syncStubDetected = v == 0L
        for (i in 0 until 7) {
            val (v2, _) = command()!!
            syncStubDetected = syncStubDetected && (v2 == 0L)
        }
    }

    fun connect(mode: String = "default-reset", attempts: Int = DEFAULT_CONNECT_ATTEMPTS, detecting: Boolean = false, warnings: Boolean = true) {
        var effectiveMode = mode
        if (warnings && (effectiveMode == "no-reset" || effectiveMode == "no-reset-no-sync"))
            log.note("Pre-connection option \"$effectiveMode\" was selected. Connection may fail if the chip is not in bootloader or flasher stub mode.")
        if (port.name.startsWith("socket:")) {
            effectiveMode = "no-reset"
            log.note("It's not possible to reset the chip over a TCP socket. Automatic resetting to bootloader has been disabled, reset the chip manually.")
        }
        log.print("Connecting...", end = "", flush = true)
        var lastError: FatalError? = null
        val resetSequence = constructResetStrategySequence(effectiveMode)
        try {
            var idx = 0
            var i = 0
            while (attempts <= 0 || i < attempts) {
                val strategy = resetSequence[idx % resetSequence.size]
                lastError = connectAttempt(strategy, effectiveMode)
                if (lastError == null) break
                idx++; i++
            }
        } finally {
            log.print("")
        }
        if (lastError != null) {
            var additionalMsg = ""
            if (chipName == "ESP32-C2" && port.baudrate < 115200)
                additionalMsg = "\nNote: Please set a higher baud rate if ESP32-C2 doesn't connect (at least 115200 Bd is recommended)."
            port.close()
            throw FatalError("Failed to connect to $chipName: ${lastError.message}$additionalMsg\nFor troubleshooting steps visit: $TROUBLESHOOTING_GUIDE_URL")
        }
        if (!detecting) {
            // Detect chip if needed – we already know the chip type from the instance.
        }
    }

    private fun connectAttempt(resetStrategy: ResetStrategy, mode: String): FatalError? {
        var lastError: FatalError? = null
        var bootLogDetected = false
        var bootMode = ""
        var downloadMode = false
        if (mode == "no-reset-no-sync") return null
        if (mode != "no-reset") {
            port.resetInputBuffer()
            resetStrategy()
            val waiting = port.inWaiting()
            val readBytes = port.read(waiting)
            val text = String(readBytes, Charsets.ISO_8859_1)
            val m = Regex("boot:(0x[0-9a-fA-F]+)(.*waiting for download)?", RegexOption.DOT_MATCHES_ALL).find(text)
            if (m != null) {
                bootLogDetected = true
                bootMode = m.groupValues[1]
                downloadMode = m.groupValues.getOrNull(2)?.isNotEmpty() == true
            }
        }
        for (i in 0 until 5) {
            try {
                flushInput()
                port.flushOutput()
                sync()
                return null
            } catch (e: FatalError) {
                log.print(".", end = "", flush = true)
                sleepSeconds(0.05)
                lastError = e
            }
        }
        if (bootLogDetected) {
            lastError = FatalError("Wrong boot mode detected ($bootMode)! The chip needs to be in download mode.")
            if (downloadMode) lastError = FatalError("Download mode successfully detected, but getting no sync reply: The serial TX path seems to be down.")
        }
        return lastError
    }

    private fun constructResetStrategySequence(mode: String): List<ResetStrategy> {
        val delay = DEFAULT_RESET_DELAY
        val extraDelay = DEFAULT_RESET_DELAY + 0.5
        if (mode == "usb-reset" || getUsbVidPid()?.second == 0x1001) {
            return listOf(USBJTAGSerialReset(port))
        }
        val flowControl = usesHardwareFlowControl()
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        if (!isWindows && !port.name.startsWith("rfc2217:")) {
            return listOf(
                UnixTightReset(port, delay, flowControl),
                UnixTightReset(port, extraDelay, flowControl),
                ClassicReset(port, delay, flowControl),
                ClassicReset(port, extraDelay, flowControl)
            )
        }
        return listOf(ClassicReset(port, delay, flowControl), ClassicReset(port, extraDelay, flowControl))
    }

    fun getUsbVidPid(): Pair<Int, Int>? {
        val cachedVid = cache["usb_vid"] as Int?
        val cachedPid = cache["usb_pid"] as Int?
        if (cachedVid != null && cachedPid != null) return cachedVid to cachedPid
        val activePort = port.port
        val ports = try { SerialPort.getCommPorts() } catch (e: Exception) { return null }
        for (p in ports) {
            if (p.systemPortName != activePort && p.systemPortPath != activePort) continue
            // Use explicit getters for jSerialComm
            val vid = try { p.getVendorID() } catch (e: Exception) { -1 }
            val pid = try { p.getProductID() } catch (e: Exception) { -1 }
            if (vid > 0 && pid > 0) {
                cache["usb_vid"] = vid
                cache["usb_pid"] = pid
                return vid to pid
            }
        }
        log.print("\nFailed to get VID/PID of a device on $activePort, using standard reset sequence.")
        return null
    }

    fun usesHardwareFlowControl(): Boolean {
        val (vid, pid) = getUsbVidPid() ?: return false
        return (vid == 0x10C4 && pid == 0xEA64)
    }

    fun readReg(addr: Long, timeout: Double = DEFAULT_TIMEOUT): Long {
        val command = packLEInt32s(addr)
        return checkCommand("read target memory", ESP_CMDS["READ_REG"], command, timeout = timeout).value
    }

    fun writeReg(addr: Long, value: Long, mask: Long = 0xFFFFFFFFL, delayUs: Long = 0, delayAfterUs: Long = 0) {
        var command = packLEInt32s(addr, value, mask, delayUs)
        if (delayAfterUs > 0) command += packLEInt32s(uartDateRegAddr, 0, 0, delayAfterUs)
        checkCommand("write target memory", ESP_CMDS["WRITE_REG"], command)
    }

    fun memBegin(size: Long, blocks: Long, blocksize: Long, offset: Long) {
        if (isStub) {
            val stub = StubFlasher(this)
            val loadStart = offset; val loadEnd = offset + size
            val ranges = listOf(
                (stub.bssStart ?: stub.dataStart ?: 0L) to ((stub.dataStart ?: 0L) + (stub.data?.size ?: 0)),
                stub.textStart to (stub.textStart + stub.text.size)
            )
            for ((stubStart, stubEnd) in ranges) {
                if (loadStart < stubEnd && loadEnd > stubStart) {
                    throw FatalError("Stub flasher is resident at ${hexPad(stubStart, 10)}-${hexPad(stubEnd, 10)}. Can't load binary at overlapping address range ${hexPad(loadStart, 10)}-${hexPad(loadEnd, 10)}.")
                }
            }
        }
        checkCommand("enter RAM download mode", ESP_CMDS["MEM_BEGIN"], packLEInt32s(size, blocks, blocksize, offset))
    }

    fun memBlock(data: ByteArray, seq: Long) {
        checkCommand("write to target RAM", ESP_CMDS["MEM_DATA"], packLEInt32s(data.size.toLong(), seq, 0, 0) + data, checksum(data).toLong())
    }

    fun memFinish(entrypoint: Long = 0) {
        val timeout = if (isStub) DEFAULT_TIMEOUT else MEM_END_ROM_TIMEOUT
        val data = packLEInt32s(if (entrypoint == 0L) 1 else 0, entrypoint)
        try {
            checkCommand("leave RAM download mode", ESP_CMDS["MEM_END"], data = data, timeout = timeout)
        } catch (e: FatalError) {
            if (isStub) throw e
        }
    }

    open fun getEraseSize(offset: Long, size: Long): Long = size

    fun flashBegin(size: Long, offset: Long, encryptedWrite: Boolean = false, logging: Boolean = true): Long {
        val numBlocks = (size + flashWriteSize - 1) / flashWriteSize
        val eraseSize = getEraseSize(offset, size)
        val t0 = System.currentTimeMillis()
        val timeout = if (isStub) DEFAULT_TIMEOUT else timeoutPerMb(ERASE_REGION_TIMEOUT_PER_MB, size)
        var params = packLEInt32s(eraseSize, numBlocks, flashWriteSize, offset)
        if (isStub || chipName !in setOf("ESP32", "ESP8266")) params += packLEInt32s(if (encryptedWrite) 1 else 0)
        checkCommand("enter flash download mode", ESP_CMDS["FLASH_BEGIN"], params, timeout = timeout)
        if (size != 0L && !isStub && logging) log.print("Took ${"%.2f".format((System.currentTimeMillis() - t0) / 1000.0)}s to erase flash block.")
        return numBlocks
    }

    fun flashFinish(reboot: Boolean = false, timeout: Double = DEFAULT_TIMEOUT) {
        val pkt = packLEInt32s(if (!reboot) 1 else 0)
        checkCommand("leave flash download mode", ESP_CMDS["FLASH_END"], pkt, timeout = timeout)
    }

    fun run(reboot: Boolean = false) {
        flashBegin(0, 0)
        flashFinish(reboot)
    }

    fun flashId(cacheOk: Boolean = true): Long {
        if (!cacheOk || cache["flash_id"] == null) {
            val SPIFLASH_RDID = 0x9F
            cache["flash_id"] = runSpiflashCommand(SPIFLASH_RDID, ByteArray(0), 24)
        }
        return cache["flash_id"] as Long
    }

    fun runSpiflashCommand(spiflashCommand: Int, data: ByteArray = ByteArray(0), readBits: Int = 0, addrIn: Long? = null, addrLen: Int = 0, dummyLen: Int = 0): Long {
        val SPI_USR_COMMAND = 1L shl 31; val SPI_USR_ADDR = 1L shl 30; val SPI_USR_DUMMY = 1L shl 29
        val SPI_USR_MISO = 1L shl 28; val SPI_USR_MOSI = 1L shl 27
        val base = spiRegBase
        val SPI_CMD_REG = base + 0x00; val SPI_ADDR_REG = base + 0x04
        val SPI_USR_REG = base + spiUsrOffs; val SPI_USR1_REG = base + spiUsr1Offs; val SPI_USR2_REG = base + spiUsr2Offs
        val SPI_W0_REG = base + spiW0Offs
        val SPI_CMD_USR = 1L shl 18; val SPI_USR2_COMMAND_LEN_SHIFT = 28; val SPI_USR_ADDR_LEN_SHIFT = 26
        if (readBits > 32) throw FatalError("Reading more than 32 bits back from a SPI flash operation is unsupported")
        if (data.size > 64) throw FatalError("Writing more than 64 bytes of data with one SPI command is unsupported")
        val dataBits = data.size * 8
        val setDataLengths: (Int, Int) -> Unit = if (spiMosiDlenOffs != null) { mosiBits, misoBits ->
            val SPI_MOSI_DLEN_REG = base + spiMosiDlenOffs!!
            val SPI_MISO_DLEN_REG = base + (spiMisoDlenOffs ?: 0L)
            if (mosiBits > 0) writeReg(SPI_MOSI_DLEN_REG, (mosiBits - 1).toLong())
            if (misoBits > 0) writeReg(SPI_MISO_DLEN_REG, (misoBits - 1).toLong())
            var flags = 0L
            if (dummyLen > 0) flags = flags or (dummyLen - 1).toLong()
            if (addrLen > 0) flags = flags or ((addrLen - 1).toLong() shl SPI_USR_ADDR_LEN_SHIFT)
            if (flags != 0L) writeReg(SPI_USR1_REG, flags)
        } else { mosiBits, misoBits ->
            val SPI_DATA_LEN_REG = SPI_USR1_REG
            val SPI_MOSI_BITLEN_S = 17; val SPI_MISO_BITLEN_S = 8
            val mosiMask = if (mosiBits == 0) 0L else (mosiBits - 1).toLong()
            val misoMask = if (misoBits == 0) 0L else (misoBits - 1).toLong()
            var flags = (misoMask shl SPI_MISO_BITLEN_S) or (mosiMask shl SPI_MOSI_BITLEN_S)
            if (dummyLen > 0) flags = flags or (dummyLen - 1).toLong()
            if (addrLen > 0) flags = flags or ((addrLen - 1).toLong() shl SPI_USR_ADDR_LEN_SHIFT)
            writeReg(SPI_DATA_LEN_REG, flags)
        }
        val oldSpiUsr = readReg(SPI_USR_REG); val oldSpiUsr2 = readReg(SPI_USR2_REG)
        var flags = SPI_USR_COMMAND
        if (readBits > 0) flags = flags or SPI_USR_MISO
        if (dataBits > 0) flags = flags or SPI_USR_MOSI
        if (addrLen > 0) flags = flags or SPI_USR_ADDR
        if (dummyLen > 0) flags = flags or SPI_USR_DUMMY
        setDataLengths(dataBits, readBits)
        writeReg(SPI_USR_REG, flags)
        writeReg(SPI_USR2_REG, ((7L shl SPI_USR2_COMMAND_LEN_SHIFT)) or spiflashCommand.toLong())
        if (addrLen > 0) {
            var addr = addrIn ?: 0L
            if (spiAddrRegMsb) addr = addr shl (32 - addrLen)
            writeReg(SPI_ADDR_REG, addr)
        }
        if (dataBits == 0) {
            writeReg(SPI_W0_REG, 0)
        } else {
            val padded = padTo(data, 4, 0)
            var nextReg = SPI_W0_REG
            var i = 0
            while (i < padded.size) {
                val word = unpackU32LE(padded, i)
                writeReg(nextReg, word)
                nextReg += 4
                i += 4
            }
        }
        writeReg(SPI_CMD_REG, SPI_CMD_USR)
        var done = false
        for (i in 0 until 10) {
            if ((readReg(SPI_CMD_REG) and SPI_CMD_USR) == 0L) { done = true; break }
        }
        if (!done) throw FatalError("SPI command did not complete in time")
        val status = readReg(SPI_W0_REG)
        writeReg(SPI_USR_REG, oldSpiUsr)
        writeReg(SPI_USR2_REG, oldSpiUsr2)
        return status
    }

    fun readSpiflashSfdp(addr: Long, readBits: Int): Long {
        val CMD_RDSFDP = 0x5A
        return runSpiflashCommand(CMD_RDSFDP, readBits = readBits, addrIn = addr, addrLen = 24, dummyLen = 8)
    }

    fun flashSetParameters(size: Long) {
        val flId = 0L; val totalSize = size; val blockSize = 64L * 1024; val sectorSize = 4L * 1024; val pageSize = 256L; val statusMask = 0xFFFFL
        checkCommand("set SPI params", ESP_CMDS["SPI_SET_PARAMS"], packLEInt32s(flId, totalSize, blockSize, sectorSize, pageSize, statusMask))
    }

    fun flashSpiAttach(hspiArg: Long) {
        var arg = packLEInt32s(hspiArg)
        if (!isStub) arg += byteArrayOf(0, 0, 0, 0)
        checkCommand("configure SPI flash pins", ESP_CMDS["SPI_ATTACH"], arg)
    }

    fun flashSpiNandAttach(hspiArg: Long) {
        var arg = packLEInt32s(hspiArg)
        if (!isStub) arg += byteArrayOf(0, 0, 0, 0)
        val (v, data) = command(ESP_CMDS["SPI_NAND_ATTACH"], arg)!!
        if (data.size < 3) {
            if (data.size >= 2 && data[1].toInt() != 0) throw FatalError.withResult("Failed to configure SPI NAND flash pins", data.copyOfRange(0, 2))
            throw FatalError("Failed to configure SPI NAND flash pins. Only got ${data.size} byte response.")
        }
        val statusBytes = data.copyOfRange(1, 3)
        if (statusBytes[0].toInt() != 0) throw FatalError.withResult("Failed to configure SPI NAND flash pins", statusBytes)
        val statusReg = (v shr 24) and 0xFF; val mfrId = (v shr 16) and 0xFF; val devId = v and 0xFFFF; val protReg = byteAt(data, 0)
        val known = mapOf((0xEF to 0xAA21) to "Winbond W25N01GV (1Gbit)")
        val chipDesc = known[mfrId.toInt() to devId.toInt()]
        if (chipDesc != null) log.print("Detected NAND chip: $chipDesc")
        else throw FatalError("Unrecognized NAND JEDEC ID (mfr=${String.format("0x%02x", mfrId)}, dev=${String.format("0x%04x", devId)}). Only Winbond W25N01GV is supported.")
        trace("NAND debug: status=${String.format("0x%02x", statusReg)}, JEDEC ID: mfr=${String.format("0x%02x", mfrId)} dev=${String.format("0x%04x", devId)}, prot=${String.format("0x%02x", protReg)}")
        if (protReg != 0) log.warning("NAND protection register is ${String.format("0x%02x", protReg)} (expected 0x00); program/erase may not persist.")
    }

    fun readNandSpare(pageNumber: Long): ByteArray = checkCommand("read NAND spare", ESP_CMDS["SPI_NAND_READ_SPARE"], packLEInt32s(pageNumber)).data ?: ByteArray(0)

    open fun readFlashSlow(offset: Long, length: Long, progressFn: ((Long, Long, Long) -> Unit)?): ByteArray {
        throw NotImplementedInROMError(chipName, "read_flash_slow")
    }

    fun readFlash(offset: Long, length: Long, progressFn: ((Long, Long, Long) -> Unit)? = null): ByteArray {
        if (!isStub) return readFlashSlow(offset, length, progressFn)
        checkCommand("read flash", ESP_CMDS["READ_FLASH"], packLEInt32s(offset, length, flashSectorSize, 64))
        var data = ByteArray(0)
        while (data.size < length) {
            port.timeout = 3.0
            val p = read()
            data += p
            val dataLen = data.size.toLong()
            if (dataLen < length && p.size < flashSectorSize) {
                throw FatalError("Corrupt data, expected ${hex(flashSectorSize)} bytes but received ${hex(p.size.toLong())} bytes.")
            }
            write(packLEInt32s(dataLen))
            if (progressFn != null && (dataLen % 1024 == 0L || dataLen == length)) progressFn(dataLen, length, offset)
        }
        if (data.size > length) throw FatalError("Read more than expected.")
        val digestFrame = read()
        if (digestFrame.size != 16) throw FatalError("Expected digest, got: ${hexify(digestFrame)}")
        val expectedDigest = hexify(digestFrame).uppercase()
        val digest = md5Hex(data).uppercase()
        if (digest != expectedDigest) throw FatalError("Digest mismatch: expected $expectedDigest, got $digest")
        return data
    }

    fun readFlashNand(offset: Long, length: Long, progressFn: ((Long, Long, Long) -> Unit)? = null): ByteArray {
        if (!isStub) throw FatalError("NAND read_flash is only supported via the stub loader.")
        checkCommand("read NAND flash", ESP_CMDS["SPI_NAND_READ_FLASH"], packLEInt32s(offset, length, flashSectorSize, NAND_PAGES_PER_BLOCK.toLong()))
        val prevTimeout = port.timeout
        port.timeout = 10.0
        var data = ByteArray(0)
        try {
            while (data.size < length) {
                val p = read()
                data += p
                val dataLen = data.size.toLong()
                if (dataLen < length && p.size < flashSectorSize) throw FatalError("Corrupt data, expected ${hex(flashSectorSize)} bytes but received ${hex(p.size.toLong())} bytes.")
                write(packLEInt32s(dataLen))
                if (progressFn != null && (dataLen % 1024 == 0L || dataLen == length)) progressFn(dataLen, length, offset)
            }
            if (data.size > length) throw FatalError("Read more than expected.")
            val digestFrame = read()
            if (digestFrame.size != 16) throw FatalError("Expected digest, got: ${hexify(digestFrame)}")
            val expectedDigest = hexify(digestFrame).uppercase()
            val digest = md5Hex(data).uppercase()
            if (digest != expectedDigest) throw FatalError("Digest mismatch: expected $expectedDigest, got $digest")
        } finally {
            port.timeout = prevTimeout
        }
        return data
    }

    open fun getCrystalFreq(): Int {
        val uartDiv = readReg(uartClkdivReg) and uartClkdivMask
        val estXtal = (port.baudrate * uartDiv) / 1e6 / xtalClkDivider
        val normXtal = if (estXtal > 45) 48 else if (estXtal > 33) 40 else 26
        if (kotlin.math.abs(normXtal - estXtal) > 1) {
            log.warning("Detected crystal freq ${"%.2f".format(estXtal)} MHz is quite different to normalized freq $normXtal MHz. Unsupported crystal in use?")
        }
        return normXtal
    }

    data class SecurityInfo(
        val flags: Long, val flashCryptCnt: Int, val keyPurposes: List<Int>,
        val chipId: Long?, val apiVersion: Long?, val parsedFlags: Map<String, Boolean>
    )

    fun getSecurityInfo(cacheOk: Boolean = true): SecurityInfo {
        (cache["security_info"] as SecurityInfo?)?.let { if (cacheOk) return it }
        var res: SecurityInfo
        try {
            val r = checkCommand("get security info", ESP_CMDS["GET_SECURITY_INFO"], respDataLen = 20).data!!
            val flags = unpackU32LE(r, 0)
            val flashCryptCnt = byteAt(r, 4)
            val keyPurposes = (0 until 7).map { byteAt(r, 5 + it) }
            val chipId = unpackU32LE(r, 12)
            val apiVersion = unpackU32LE(r, 16)
            res = SecurityInfo(flags, flashCryptCnt, keyPurposes, chipId, apiVersion, parseSecurityFlags(flags))
        } catch (e: FatalError) {
            val r = checkCommand("get security info", ESP_CMDS["GET_SECURITY_INFO"], respDataLen = 12).data!!
            val flags = unpackU32LE(r, 0)
            val flashCryptCnt = byteAt(r, 4)
            val keyPurposes = (0 until 7).map { byteAt(r, 5 + it) }
            res = SecurityInfo(flags, flashCryptCnt, keyPurposes, null, null, parseSecurityFlags(flags))
        }
        cache["security_info"] = res
        return res
    }

    private fun parseSecurityFlags(flagsValue: Long): Map<String, Boolean> {
        val map = mapOf(
            "SECURE_BOOT_EN" to (1L shl 0), "SECURE_BOOT_AGGRESSIVE_REVOKE" to (1L shl 1),
            "SECURE_DOWNLOAD_ENABLE" to (1L shl 2), "SECURE_BOOT_KEY_REVOKE0" to (1L shl 3),
            "SECURE_BOOT_KEY_REVOKE1" to (1L shl 4), "SECURE_BOOT_KEY_REVOKE2" to (1L shl 5),
            "SOFT_DIS_JTAG" to (1L shl 6), "HARD_DIS_JTAG" to (1L shl 7), "DIS_USB" to (1L shl 8),
            "DIS_DOWNLOAD_DCACHE" to (1L shl 9), "DIS_DOWNLOAD_ICACHE" to (1L shl 10)
        )
        return map.mapValues { (_, mask) -> (flagsValue and mask) != 0L }
    }

    fun runStub(stubIn: StubFlasher? = null): ESPLoader {
        log.stage()
        val stub = stubIn ?: StubFlasher(this)
        if (syncStubDetected) {
            log.stage(finish = true)
            log.print("Stub flasher is already running. No upload is necessary.")
            val factory = chipInfo.stubFactory
            return factory?.invoke(this) ?: this
        }
        val secureBootWorkflow = (chipName == "ESP32-S3" && getSecureBootEnabled())
        log.print("Uploading stub flasher...")
        uploadSegment(stub.text, stub.textStart)
        stub.effectiveData()?.let { d -> stub.dataStart?.let { ds -> uploadSegment(d, ds) } }
        for ((loadAddr, segmentBytes) in stub.pluginSegments) uploadSegment(segmentBytes, loadAddr)
        log.print("Running stub flasher...")
        var storedReadPointer = 0L
        val romSpiflashLegacyFuncsReadPtr = 0x3FCEF688L
        if (!secureBootWorkflow) {
            memFinish(stub.entry)
        } else {
            memFinish(0)
            storedReadPointer = readReg(romSpiflashLegacyFuncsReadPtr)
            writeReg(romSpiflashLegacyFuncsReadPtr, stub.entry)
            command(ESP_CMDS["READ_FLASH_SLOW"], packLEInt32s(0, 0), waitResponse = false)
        }
        val p = try { read() } catch (e: FatalError) {
            throw FatalError("Failed to start stub flasher. There was no response.\nFor troubleshooting steps visit: $TROUBLESHOOTING_GUIDE_URL")
        }
        if (!p.contentEquals("OHAI".toByteArray(Charsets.US_ASCII))) {
            throw FatalError("Failed to start stub flasher. Unexpected response: ${String(p, Charsets.ISO_8859_1)}")
        }
        if (secureBootWorkflow) writeReg(romSpiflashLegacyFuncsReadPtr, storedReadPointer)
        log.stage(finish = true)
        log.print("Stub flasher running.")
        val factory = chipInfo.stubFactory
        return factory?.invoke(this) ?: this
    }

    private fun uploadSegment(data: ByteArray, offs: Long) {
        val length = data.size.toLong()
        val blocks = (length + espRamBlock - 1) / espRamBlock
        memBegin(length, blocks, espRamBlock, offs)
        for (seq in 0 until blocks) {
            val fromOffs = (seq * espRamBlock).toInt()
            val toOffs = minOf(fromOffs + espRamBlock.toInt(), data.size)
            memBlock(data.copyOfRange(fromOffs, toOffs), seq)
        }
    }

    open fun getChipDescription(): String = chipName
    open fun getChipFeatures(): List<String> = emptyList()
    open fun getPkgVersion(): Int = 0
    open fun getMinorChipVersion(): Int = 0
    open fun getMajorChipVersion(): Int = 0
    open fun getSecureBootEnabled(): Boolean = false
    open fun getSecureBootV1Enabled(): Boolean = false
    open fun readMac(macType: String = "BASE_MAC"): List<Int>? = null
    open fun chipId(): Long = throw NotSupportedError(this, "Function chip_id")

    fun hardReset(usesUsb: Boolean = false) {
        if (port.name.startsWith("socket:")) {
            log.note("It's not possible to reset the chip over a TCP socket. Automatic hard reset has been disabled, reset the chip manually if needed.")
            return
        }
        log.print("Hard resetting via RTS pin...")
        HardReset(port, usesUsb, flowControl = usesHardwareFlowControl())()
    }

    fun softReset(stayInBootloader: Boolean) {
        if (!isStub) {
            if (stayInBootloader) return
            flashBegin(0, 0); flashFinish(false)
        } else {
            if (stayInBootloader) {
                flashBegin(0, 0); flashFinish(true)
            } else if (chipName != "ESP8266") {
                throw FatalError("Soft resetting is currently only supported on ESP8266")
            } else {
                command(ESP_CMDS["RUN_USER_CODE"], waitResponse = false)
            }
        }
    }

    fun watchdogReset() {
        log.note("Watchdog hard reset is not supported on $chipName, attempting classic hard reset instead.")
        hardReset()
    }

    open fun changeBaud(baud: Int) {
        if (chipName == "ESP32") {
            // ESP32 workaround
            val RTCCALICFG1 = 0x3FF5F06CL
            val TIMERS_RTC_CALI_VALUE_S = 7
            val TIMERS_RTC_CALI_VALUE = 0x01FFFFFFL
            val caliVal = (readReg(RTCCALICFG1) shr TIMERS_RTC_CALI_VALUE_S) and TIMERS_RTC_CALI_VALUE
            val clk8MFreq = readReg(0x3FF5A000L + 4*4) and 0xFF
            val romCalculatedFreq = caliVal * 15625 * clk8MFreq / 40
            val validFreq = if (romCalculatedFreq > 33000000) 40000000 else 26000000
            val falseRomBaud = (baud * romCalculatedFreq / validFreq).toInt()
            log.print("Changing baud rate to $baud...")
            command(ESP_CMDS["CHANGE_BAUDRATE"], packLEInt32s(falseRomBaud.toLong(), 0))
            log.print("Changed.")
            setPortBaudrate(baud)
            sleepSeconds(0.05); flushInput()
            return
        }
        log.print("Changing baud rate to $baud...")
        val secondArg = if (isStub) port.baudrate.toLong() else 0L
        command(ESP_CMDS["CHANGE_BAUDRATE"], packLEInt32s(baud.toLong(), secondArg))
        log.print("Changed.")
        setPortBaudrate(baud)
        sleepSeconds(0.05); flushInput()
    }

    private fun setPortBaudrate(baud: Int) {
        try { port.baudrate = baud } catch (e: Exception) {
            throw FatalError("Failed to set baud rate $baud. The driver may not support this rate.")
        }
    }
}

// ============================================================================
// Chip definitions
// ============================================================================

val chipDefinitions: Map<String, ChipInfo> = mapOf(
    "esp8266" to ChipInfo(
        chipName = "ESP8266",
        usesMagicValue = true,
        magicValue = 0xFFF0C101L,
        spiRegBase = 0x60000200L,
        spiUsrOffs = 0x1C, spiUsr1Offs = 0x20, spiUsr2Offs = 0x24,
        spiMosiDlenOffs = null, spiMisoDlenOffs = null,
        spiW0Offs = 0x40,
        uartClkdivReg = 0x60000014L,
        xtalClkDivider = 2,
        flashSizes = mapOf(
            "512KB" to 0x00L, "256KB" to 0x10L, "1MB" to 0x20L, "2MB" to 0x30L,
            "4MB" to 0x40L, "2MB-c1" to 0x50L, "4MB-c1" to 0x60L, "8MB" to 0x80L, "16MB" to 0x90L
        ),
        flashFrequencies = mapOf("80m" to 0xFL, "40m" to 0x0L, "26m" to 0x1L, "20m" to 0x2L),
        bootloaderFlashOffset = 0L,
        memoryMap = listOf(
            MemRegion(0x3FF00000, 0x3FF00010, "DPORT"),
            MemRegion(0x3FFE8000, 0x40000000, "DRAM"),
            MemRegion(0x40100000, 0x40108000, "IRAM"),
            MemRegion(0x40201010, 0x402E1010, "IROM")
        ),
        stubFactory = { rom -> ESP8266StubLoader(rom) }
    ),
    "esp32" to ChipInfo(
        chipName = "ESP32",
        imageChipId = 0,
        usesMagicValue = true,
        magicValue = 0x00F01D83L,
        spiRegBase = 0x3FF42000L,
        spiUsrOffs = 0x1C, spiUsr1Offs = 0x20, spiUsr2Offs = 0x24,
        spiMosiDlenOffs = 0x28L, spiMisoDlenOffs = 0x2CL,
        spiW0Offs = 0x80,
        uartClkdivReg = 0x3FF40014L,
        xtalClkDivider = 1,
        flashSizes = mapOf(
            "1MB" to 0x00L, "2MB" to 0x10L, "4MB" to 0x20L, "8MB" to 0x30L,
            "16MB" to 0x40L, "32MB" to 0x50L, "64MB" to 0x60L, "128MB" to 0x70L
        ),
        flashFrequencies = mapOf("80m" to 0xFL, "40m" to 0x0L, "26m" to 0x1L, "20m" to 0x2L),
        bootloaderFlashOffset = 0x1000L,
        memoryMap = listOf(
            MemRegion(0x00000000, 0x00010000, "PADDING"),
            MemRegion(0x3F400000, 0x3F800000, "DROM"),
            MemRegion(0x3F800000, 0x3FC00000, "EXTRAM_DATA"),
            MemRegion(0x3FF80000, 0x3FF82000, "RTC_DRAM"),
            MemRegion(0x3FF90000, 0x40000000, "BYTE_ACCESSIBLE"),
            MemRegion(0x3FFAE000, 0x40000000, "DRAM"),
            MemRegion(0x3FFE0000, 0x3FFFFFFC, "DIRAM_DRAM"),
            MemRegion(0x40000000, 0x40070000, "IROM"),
            MemRegion(0x40070000, 0x40078000, "CACHE_PRO"),
            MemRegion(0x40078000, 0x40080000, "CACHE_APP"),
            MemRegion(0x40080000, 0x400A0000, "IRAM"),
            MemRegion(0x400A0000, 0x400BFFFC, "DIRAM_IRAM"),
            MemRegion(0x400C0000, 0x400C2000, "RTC_IRAM"),
            MemRegion(0x400D0000, 0x40400000, "IROM"),
            MemRegion(0x50000000, 0x50002000, "RTC_DATA")
        ),
        stubFactory = { rom -> ESP32StubLoader(rom) }
    ),
    "esp32s2" to ChipInfo(
        chipName = "ESP32-S2",
        imageChipId = 2,
        usesMagicValue = true,
        magicValue = 0x000007C6L,
        spiRegBase = 0x3F402000L,
        spiUsrOffs = 0x18, spiUsr1Offs = 0x1C, spiUsr2Offs = 0x20,
        spiMosiDlenOffs = 0x24L, spiMisoDlenOffs = 0x28L,
        spiW0Offs = 0x58,
        spiAddrRegMsb = false,
        uartClkdivReg = 0x3F400014L,
        stubFactory = { rom -> ESP32S2StubLoader(rom) }
    ),
    "esp32s3" to ChipInfo(
        chipName = "ESP32-S3",
        imageChipId = 9,
        usesMagicValue = false,
        spiRegBase = 0x60002000L,
        spiUsrOffs = 0x18, spiUsr1Offs = 0x1C, spiUsr2Offs = 0x20,
        spiMosiDlenOffs = 0x24L, spiMisoDlenOffs = 0x28L,
        spiW0Offs = 0x58,
        spiAddrRegMsb = false,
        uartDateRegAddr = 0x60000080L,
        uartClkdivReg = 0x60000014L,
        stubFactory = { rom -> ESP32S3StubLoader(rom) }
    ),
    "esp32c3" to ChipInfo(
        chipName = "ESP32-C3",
        imageChipId = 5,
        usesMagicValue = false,
        spiRegBase = 0x60002000L,
        spiUsrOffs = 0x18, spiUsr1Offs = 0x1C, spiUsr2Offs = 0x20,
        spiMosiDlenOffs = 0x24L, spiMisoDlenOffs = 0x28L,
        spiW0Offs = 0x58,
        spiAddrRegMsb = false,
        uartDateRegAddr = 0x60000000L + 0x7C,
        uartClkdivReg = 0x60000014L,
        stubFactory = { rom -> ESP32C3StubLoader(rom) }
    ),
    "esp32c2" to ChipInfo(
        chipName = "ESP32-C2",
        imageChipId = 12,
        usesMagicValue = false,
        spiRegBase = 0x60002000L,
        spiUsrOffs = 0x18, spiUsr1Offs = 0x1C, spiUsr2Offs = 0x20,
        spiMosiDlenOffs = 0x24L, spiMisoDlenOffs = 0x28L,
        spiW0Offs = 0x58,
        spiAddrRegMsb = false,
        uartDateRegAddr = 0x60000000L + 0x7C,
        uartClkdivReg = 0x60000014L,
        flashFrequencies = mapOf("60m" to 0xFL, "30m" to 0x0L, "20m" to 0x1L, "15m" to 0x2L),
        stubFactory = { rom -> ESP32C2StubLoader(rom) }
    ),
    "esp32c6" to ChipInfo(
        chipName = "ESP32-C6",
        imageChipId = 13,
        usesMagicValue = false,
        spiRegBase = 0x60003000L,
        spiUsrOffs = 0x18, spiUsr1Offs = 0x1C, spiUsr2Offs = 0x20,
        spiMosiDlenOffs = 0x24L, spiMisoDlenOffs = 0x28L,
        spiW0Offs = 0x58,
        spiAddrRegMsb = false,
        uartDateRegAddr = 0x60000000L + 0x7C,
        uartClkdivReg = 0x60000014L,
        stubFactory = { rom -> ESP32C6StubLoader(rom) }
    ),
    "esp32c61" to ChipInfo(
        chipName = "ESP32-C61",
        imageChipId = 20,
        usesMagicValue = false,
        spiRegBase = 0x60003000L,
        spiUsrOffs = 0x18, spiUsr1Offs = 0x1C, spiUsr2Offs = 0x20,
        spiMosiDlenOffs = 0x24L, spiMisoDlenOffs = 0x28L,
        spiW0Offs = 0x58,
        spiAddrRegMsb = false,
        uartDateRegAddr = 0x60000000L + 0x7C,
        uartClkdivReg = 0x60000014L,
        stubFactory = { rom -> ESP32C61StubLoader(rom) }
    ),
    "esp32c5" to ChipInfo(
        chipName = "ESP32-C5",
        imageChipId = 23,
        usesMagicValue = false,
        spiRegBase = 0x60003000L,
        spiUsrOffs = 0x18, spiUsr1Offs = 0x1C, spiUsr2Offs = 0x20,
        spiMosiDlenOffs = 0x24L, spiMisoDlenOffs = 0x28L,
        spiW0Offs = 0x58,
        spiAddrRegMsb = false,
        uartDateRegAddr = 0x60000000L + 0x7C,
        uartClkdivReg = 0x60000014L,
        stubFactory = { rom -> ESP32C5StubLoader(rom) }
    ),
    "esp32e22" to ChipInfo(
        chipName = "ESP32-E22",
        imageChipId = 31,
        usesMagicValue = false,
        stubFactory = { rom -> ESP32E22StubLoader(rom) }
    ),
    "esp32h2" to ChipInfo(
        chipName = "ESP32-H2",
        imageChipId = 16,
        usesMagicValue = false,
        spiRegBase = 0x60003000L,
        spiUsrOffs = 0x18, spiUsr1Offs = 0x1C, spiUsr2Offs = 0x20,
        spiMosiDlenOffs = 0x24L, spiMisoDlenOffs = 0x28L,
        spiW0Offs = 0x58,
        spiAddrRegMsb = false,
        uartDateRegAddr = 0x60000000L + 0x7C,
        uartClkdivReg = 0x60000014L,
        stubFactory = { rom -> ESP32H2StubLoader(rom) }
    ),
    "esp32h21" to ChipInfo(
        chipName = "ESP32-H21",
        imageChipId = 25,
        usesMagicValue = false,
        stubFactory = { rom -> ESP32H21StubLoader(rom) }
    ),
    "esp32p4" to ChipInfo(
        chipName = "ESP32-P4",
        imageChipId = 18,
        usesMagicValue = false,
        stubFactory = { rom -> ESP32P4StubLoader(rom) }
    ),
    "esp32h4" to ChipInfo(
        chipName = "ESP32-H4",
        imageChipId = 28,
        usesMagicValue = false,
        stubFactory = { rom -> ESP32H4StubLoader(rom) }
    ),
    "esp32s31" to ChipInfo(
        chipName = "ESP32-S31",
        imageChipId = 32,
        usesMagicValue = false,
        stubFactory = { rom -> ESP32S31StubLoader(rom) }
    )
)

// Stub loader classes
class ESP8266StubLoader(rom: ESPLoader) : ESPLoader(rom, chipDefinitions["esp8266"]!!) {
    init { isStub = true; flashWriteSize = 0x4000L }
    override fun getEraseSize(offset: Long, size: Long): Long = size
}
class ESP32StubLoader(rom: ESPLoader) : ESPLoader(rom, chipDefinitions["esp32"]!!) {
    init { isStub = true; flashWriteSize = 0x4000L }
}
class ESP32S2StubLoader(rom: ESPLoader) : ESPLoader(rom, chipDefinitions["esp32s2"]!!) {
    init { isStub = true; flashWriteSize = 0x4000L }
}
class ESP32S3StubLoader(rom: ESPLoader) : ESPLoader(rom, chipDefinitions["esp32s3"]!!) {
    init { isStub = true; flashWriteSize = 0x4000L }
}
class ESP32C3StubLoader(rom: ESPLoader) : ESPLoader(rom, chipDefinitions["esp32c3"]!!) {
    init { isStub = true; flashWriteSize = 0x4000L }
}
class ESP32C2StubLoader(rom: ESPLoader) : ESPLoader(rom, chipDefinitions["esp32c2"]!!) {
    init { isStub = true; flashWriteSize = 0x4000L }
}
class ESP32C6StubLoader(rom: ESPLoader) : ESPLoader(rom, chipDefinitions["esp32c6"]!!) {
    init { isStub = true; flashWriteSize = 0x4000L }
}
class ESP32C61StubLoader(rom: ESPLoader) : ESPLoader(rom, chipDefinitions["esp32c61"]!!) {
    init { isStub = true; flashWriteSize = 0x4000L }
}
class ESP32C5StubLoader(rom: ESPLoader) : ESPLoader(rom, chipDefinitions["esp32c5"]!!) {
    init { isStub = true; flashWriteSize = 0x4000L }
}
class ESP32E22StubLoader(rom: ESPLoader) : ESPLoader(rom, chipDefinitions["esp32e22"]!!) {
    init { isStub = true; flashWriteSize = 0x4000L }
}
class ESP32H2StubLoader(rom: ESPLoader) : ESPLoader(rom, chipDefinitions["esp32h2"]!!) {
    init { isStub = true; flashWriteSize = 0x4000L }
}
class ESP32H21StubLoader(rom: ESPLoader) : ESPLoader(rom, chipDefinitions["esp32h21"]!!) {
    init { isStub = true; flashWriteSize = 0x4000L }
}
class ESP32P4StubLoader(rom: ESPLoader) : ESPLoader(rom, chipDefinitions["esp32p4"]!!) {
    init { isStub = true; flashWriteSize = 0x4000L }
}
class ESP32H4StubLoader(rom: ESPLoader) : ESPLoader(rom, chipDefinitions["esp32h4"]!!) {
    init { isStub = true; flashWriteSize = 0x4000L }
}
class ESP32S31StubLoader(rom: ESPLoader) : ESPLoader(rom, chipDefinitions["esp32s31"]!!) {
    init { isStub = true; flashWriteSize = 0x4000L }
}

// ============================================================================
// Command-line helpers
// ============================================================================

fun detectChip(port: String = DEFAULT_PORT, baud: Int = ESP_ROM_BAUD,
               connectMode: String = "default-reset", traceEnabled: Boolean = false,
               connectAttempts: Int = DEFAULT_CONNECT_ATTEMPTS): ESPLoader {
    // First try chips that don't use magic value (newer ones)
    for ((_, info) in chipDefinitions.filter { !it.value.usesMagicValue }) {
        try {
            val esp = ESPLoader(info, port, baud, traceEnabled)
            esp.connect(connectMode, connectAttempts, detecting = true)
            val chipId = esp.getChipId()
            if (chipId == info.imageChipId?.toLong()) {
                return esp
            }
            esp.close()
        } catch (e: Exception) { /* ignore */ }
    }

    // Then try magic value chips
    for ((_, info) in chipDefinitions.filter { it.value.usesMagicValue }) {
        try {
            val esp = ESPLoader(info, port, baud, traceEnabled)
            esp.connect(connectMode, connectAttempts, detecting = true)
            val magic = esp.readReg(CHIP_DETECT_MAGIC_REG_ADDR)
            if (magic == info.magicValue) {
                return esp
            }
            esp.close()
        } catch (e: Exception) { /* ignore */ }
    }

    throw FatalError("Failed to autodetect chip type.")
}

fun getFlashInfo(esp: ESPLoader, cacheOk: Boolean = true): Triple<Int, Int, String?> {
    val flashId = esp.flashId(cacheOk)
    val vendor = (flashId and 0xFF).toInt()
    val device = (((flashId shr 16) and 0xFF) or ((flashId shr 8) and 0xFF) shl 8).toInt()
    val size = if (vendor == 0x1F) {
        val sizeId = ((flashId shr 8) and 0x1F).toInt()
        mapOf(0x04 to "512KB", 0x05 to "1MB", 0x06 to "2MB", 0x07 to "4MB", 0x08 to "8MB", 0x09 to "16MB")[sizeId]
    } else {
        val sizeId = ((flashId shr 16) and 0xFF).toInt()
        mapOf(
            0x12 to "256KB", 0x13 to "512KB", 0x14 to "1MB", 0x15 to "2MB",
            0x16 to "4MB", 0x17 to "8MB", 0x18 to "16MB", 0x19 to "32MB",
            0x1A to "64MB", 0x1B to "128MB", 0x1C to "256MB", 0x20 to "64MB",
            0x21 to "128MB", 0x22 to "256MB", 0x32 to "256KB", 0x33 to "512KB",
            0x34 to "1MB", 0x35 to "2MB", 0x36 to "4MB", 0x37 to "8MB",
            0x38 to "16MB", 0x39 to "32MB", 0x3A to "64MB"
        )[sizeId]
    }
    return Triple(vendor, device, size)
}

fun detectFlashSize(esp: ESPLoader): String? {
    if (esp.secureDownloadMode) throw FatalError("Detecting flash size is not supported in secure download mode.")
    return getFlashInfo(esp).third
}

fun setFlashParameters(esp: ESPLoader, flashSize: String = "keep"): String {
    log.print("Configuring flash size...")
    val keep = flashSize == "keep"
    var effectiveSize = flashSize
    if (flashSize == "detect") {
        effectiveSize = detectFlashSize(esp) ?: run { log.warning("Could not auto-detect flash size, defaulting to 4MB."); "4MB" }
        log.print("Auto-detected flash size: $effectiveSize")
    } else if (flashSize == "keep") {
        effectiveSize = if (esp.secureDownloadMode) "keep" else (detectFlashSize(esp) ?: "keep")
        if (!esp.isStub) log.note("In case of failure, please set a specific flash size.")
    }
    if (effectiveSize != "keep") {
        val sizeBytes = flashSizeBytes(effectiveSize) ?: throw FatalError("Invalid flash size")
        esp.flashSetParameters(sizeBytes)
        if (!(esp.isStub && esp.chipName in listOf("ESP32-S3", "ESP32-P4", "ESP32-C5")) && sizeBytes > 16 * 1024 * 1024) {
            log.note("Flash sizes larger than 16MB are not fully supported.")
        }
    }
    return if (keep) "keep" else effectiveSize
}

fun readFlashNandWithSkip(esp: ESPLoader, address: Long, size: Long,
                          progressFn: ((Long, Long, Long) -> Unit)? = null,
                          nandEndAddress: Long? = null): ByteArray {
    val endAddr = nandEndAddress ?: NAND_TOTAL_SIZE
    var accumulated = ByteArray(0)
    var physAddr = address
    while (accumulated.size < size) {
        if (physAddr >= endAddr) throw FatalError("Reached NAND end address ${hex(endAddr)} before reading the requested $size bytes.")
        val pageNum = physAddr / NAND_BLOCK_SIZE * NAND_PAGES_PER_BLOCK
        val spare = esp.readNandSpare(pageNum)
        val bb = if (spare.isNotEmpty()) spare[0].toInt() and 0xFF else 0xFF
        if (bb != 0xFF) {
            log.print("Skipping bad block at ${hexPad(physAddr, 10)} during read")
            physAddr += NAND_BLOCK_SIZE
            if (physAddr >= endAddr) throw FatalError("Reached NAND end address ${hex(endAddr)} before reading the requested $size bytes.")
            continue
        }
        val remaining = size - accumulated.size
        val readSize = minOf(NAND_BLOCK_SIZE - (physAddr % NAND_BLOCK_SIZE), remaining)
        val chunk = esp.readFlashNand(physAddr, readSize, null)
        accumulated += chunk
        progressFn?.invoke(accumulated.size.toLong(), size, address)
        physAddr += readSize
    }
    return accumulated
}

fun attachFlash(esp: ESPLoader, spiConnection: Any? = null, flashType: String = "nor") {
    fun defineSpiConn(conn: List<Int>): Pair<String, Long> {
        val (clk, q, d, hd, cs) = conn
        val txt = "CLK:$clk, Q:$q, D:$d, HD:$hd, CS:$cs"
        val value = (hd.toLong() shl 24) or (cs.toLong() shl 18) or (d.toLong() shl 12) or (q.toLong() shl 6) or clk.toLong()
        return txt to value
    }
    if (spiConnection != null) {
        val value = when {
            spiConnection == "SPI" -> 0L
            spiConnection == "HSPI" -> 1L
            else -> {
                defineSpiConn(spiConnection as List<Int>).second
            }
        }
        log.print("Configuring SPI ${if (flashType == "nand") "NAND" else "NOR"} flash mode...")
        if (flashType == "nand") esp.flashSpiNandAttach(value) else esp.flashSpiAttach(value)
    } else if (flashType == "nand") {
        log.print("Enabling default SPI NAND flash mode...")
        esp.flashSpiNandAttach(0)
    } else if (!esp.isStub) {
        log.print("Enabling default SPI flash mode...")
        esp.flashSpiAttach(0)
    }
}

fun readFlash(esp: ESPLoader, address: Long, size: Long, output: String? = null,
              flashSize: String = "keep", noProgress: Boolean = false,
              flashType: String = "nor", nandEndAddress: Long? = null): ByteArray? {
    if (flashType != "nand") setFlashParameters(esp, flashSize)
    val progressFn = if (noProgress) null else { progress: Long, length: Long, offset: Long ->
        log.progressBar(progress, length, "Reading from ${hexPad(offset + progress, 10)} ", " $progress/$length bytes...")
    }
    log.stage()
    val t0 = System.currentTimeMillis()
    val data = if (flashType == "nand") {
        log.warning("NAND flash support is experimental and may change without notice.")
        readFlashNandWithSkip(esp, address, size, progressFn, nandEndAddress)
    } else {
        esp.readFlash(address, size, progressFn)
    }
    val t = (System.currentTimeMillis() - t0) / 1000.0
    val speedMsg = if (t > 0) " (${"%.1f".format(data.size / t * 8 / 1000)} kbit/s)" else ""
    val destMsg = if (output != null) " to '$output'" else ""
    log.stage(finish = true)
    log.print("Read ${data.size} bytes from ${hexPad(address, 10)} in ${"%.1f".format(t)} seconds$speedMsg$destMsg.")
    if (output != null) { File(output).writeBytes(data); return null }
    return data
}

fun readMac(esp: ESPLoader) {
    fun printMac(label: String, mac: List<Int>?) {
        if (mac == null) return
        log.print("${label.padEnd(20)}${mac.joinToString(":") { String.format("%02x", it) }}")
    }
    val eui64 = esp.readMac("EUI64")
    if (eui64 != null) {
        printMac("MAC", eui64)
        printMac("BASE MAC", esp.readMac("BASE_MAC"))
        printMac("MAC_EXT", esp.readMac("MAC_EXT"))
    } else {
        printMac("MAC", esp.readMac("BASE_MAC"))
    }
}

fun runStub(esp: ESPLoader, plugins: List<String>? = null): ESPLoader {
    if (esp.secureDownloadMode) {
        log.warning("Stub flasher is not supported in Secure Download Mode, it has been disabled. Set --no-stub to suppress this warning.")
    } else if (esp.chipName == "ESP32-C3" && esp.getSecureBootEnabled()) {
        log.warning("Stub flasher is not supported on ESP32-C3 with Secure Boot, it has been disabled. Set --no-stub to suppress this warning.")
    } else if (!esp.isStub && esp.stubIsDisabled) {
        log.warning("Stub flasher has been disabled for compatibility, set --no-stub to suppress this warning.")
    } else if (esp.chipName in listOf("ESP32-H21", "ESP32-E22")) {
        log.warning("Stub flasher is not yet supported on ${esp.chipName}, it has been disabled. Set --no-stub to suppress this warning.")
    } else {
        try {
            val stub = StubFlasher(esp, plugins)
            return esp.runStub(stub)
        } catch (e: Exception) {
            if (System.getProperty("os.name").lowercase().contains("mac") && esp.getUsbVidPid()?.second == 0x55D4) {
                log.print()
                log.note("If issues persist, try installing the WCH USB-to-Serial MacOS driver.")
            }
            throw e
        }
    }
    return esp
}

// ============================================================================
// Main entry point
// ============================================================================

fun main(args: Array<String>) {
    try {
        var port = DEFAULT_PORT
        var baud = ESP_ROM_BAUD
        var chip = "auto"
        var before = "default-reset"
        var after = "hard-reset"
        var noStub = false
        var stubVersion: String? = null
        var trace = false
        var verbose = false
        var silent = false
        var connectAttempts = DEFAULT_CONNECT_ATTEMPTS
        var address = 0L
        var size = 0L
        var output: String? = null
        var noProgress = false
        var flashSize = "keep"
        var flashType = "nor"
        var spiConnection: Any? = null

        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--port", "-p" -> { port = args[++i] }
                "--baud", "-b" -> { baud = args[++i].toInt() }
                "--chip", "-c" -> { chip = args[++i] }
                "--before" -> { before = args[++i] }
                "--after", "-a" -> { after = args[++i] }
                "--no-stub" -> { noStub = true }
                "--stub-version" -> { stubVersion = args[++i] }
                "--trace", "-t" -> { trace = true }
                "--verbose", "-v" -> { verbose = true }
                "--silent", "-s" -> { silent = true }
                "--connect-attempts" -> { connectAttempts = args[++i].toInt() }
                "--no-progress", "-p" -> { noProgress = true }
                "--flash-size", "-fs" -> { flashSize = args[++i] }
                "--flash-type", "-ft" -> { flashType = args[++i] }
                "--spi-connection", "-sc" -> {
                    val v = args[++i]
                    spiConnection = if (v.uppercase() in listOf("SPI", "HSPI")) v.uppercase()
                    else v.split(",").map { it.toInt() }
                }
                "read-flash" -> {
                    address = args[++i].toLong(0)
                    size = args[++i].toLong(0)
                    output = args[++i]
                }
                "version" -> { log.print("esptool v5.3.1 (read-only)"); return }
                else -> { log.error("Unknown argument: ${args[i]}"); exitProcess(1) }
            }
            i++
        }

        if (output == null) {
            log.error("Usage: ... read-flash <address> <size> <output>")
            exitProcess(1)
        }

        if (verbose && silent) throw FatalError("Cannot use both --verbose and --silent.")
        if (trace && silent) throw FatalError("Cannot use both --trace and --silent.")
        if (verbose) log.setVerbosity("verbose") else if (silent) log.setVerbosity("silent")

        log.print("esptool v5.3.1 (read-only)")
        if (stubVersion != null) StubFlasher.setStubSubdir(stubVersion)

        val esp = if (chip == "auto") {
            detectChip(port, baud, before, trace, connectAttempts)
        } else {
            val info = chipDefinitions[chip] ?: throw FatalError("Unknown chip: $chip")
            val esp = ESPLoader(info, port, baud, trace)
            esp.connect(before, connectAttempts)
            esp
        }

        log.stage(finish = true)
        log.print("Connected to ${esp.chipName} on ${esp.port.port}:")
        if (esp.secureDownloadMode) log.print("${"Chip type:".padEnd(20)}${esp.chipName} in Secure Download Mode")
        else {
            log.print("${"Chip type:".padEnd(20)}${esp.getChipDescription()}")
            log.print("${"Features:".padEnd(20)}${esp.getChipFeatures().joinToString(", ")}")
            log.print("${"Crystal frequency:".padEnd(20)}${esp.getCrystalFreq()}MHz")
            esp.getUsbMode()?.let { log.print("${"USB mode:".padEnd(20)}$it") }
            readMac(esp)
        }
        log.print()

        if (!noStub) runStub(esp)
        if (baud > ESP_ROM_BAUD) esp.changeBaud(baud)

        attachFlash(esp, spiConnection, flashType)
        val effectiveSize = if (flashType == "nand") size else {
            val s = if (size == 0L && flashSize == "all") detectFlashSize(esp)?.let { flashSizeBytes(it) } ?: throw FatalError("Could not detect size")
            else size
            // check bounds
            if (!(esp.isStub && esp.chipName in listOf("ESP32-S3", "ESP32-P4", "ESP32-C5", "ESP32-C61")) &&
                address + s > 0x1000000) throw FatalError("Can't access flash regions larger than 16MB")
            if (!esp.secureDownloadMode) {
                val detected = detectFlashSize(esp)
                if (detected != null) {
                    val detectedBytes = flashSizeBytes(detected) ?: return
                    if (address + s > detectedBytes) throw FatalError("Can't access flash regions larger than detected flash size (${hex(detectedBytes)})")
                }
            }
            s
        }

        readFlash(esp, address, effectiveSize, output, noProgress = noProgress, flashType = flashType)

        // after operation
        when (after) {
            "hard-reset" -> esp.hardReset()
            "soft-reset" -> esp.softReset(false)
            "no-reset-stub" -> log.print("Staying in flasher stub.")
            "watchdog-reset" -> esp.watchdogReset()
            "no-reset" -> { log.print("Staying in bootloader."); if (esp.isStub) esp.softReset(true) }
            else -> throw FatalError("Invalid reset mode: $after")
        }
        esp.close()
    } catch (e: FatalError) {
        log.error("\nA fatal error occurred: ${e.message}")
        exitProcess(2)
    } catch (e: Exception) {
        log.error("\nA serial exception error occurred: ${e.message}")
        log.error("Note: This error originates from the serial library. It is likely not a problem with esptool, but with the hardware connection or drivers.")
        log.error("For troubleshooting steps visit: $TROUBLESHOOTING_GUIDE_URL")
        exitProcess(1)
    }
}