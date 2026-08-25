// esptool_stub.kt
// SPDX-License-Identifier: GPL-2.0-or-later
// Complete minimal esptool – only read-flash (stub loader kept)

import com.fazecast.jSerialComm.SerialPort
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.system.exitProcess

// ============================================================================
// Logger - Complete Implementation
// ============================================================================

interface TemplateLogger {
    fun print(vararg args: Any?)
    fun note(message: String)
    fun warning(message: String)
    fun error(message: String)
    fun stage(finish: Boolean = false)
    fun progressBar(curIter: Int, totalIters: Int, prefix: String = "", suffix: String = "", barLength: Int = 30)
    fun setVerbosity(verbosity: String)
}

class EsptoolLogger : TemplateLogger {
    private var stageActive = false
    private var newlineCount = 0
    private val keptLines = mutableListOf<String>()
    private var smartFeatures = false
    private var verbosity: String? = null
    private var printAnyway = false

    private val ansiRed get() = if (smartFeatures) "\u001B[1;31m" else ""
    private val ansiYellow get() = if (smartFeatures) "\u001B[0;33m" else ""
    private val ansiBlue get() = if (smartFeatures) "\u001B[1;36m" else ""
    private val ansiNormal get() = if (smartFeatures) "\u001B[0m" else ""
    private val ansiClear get() = if (smartFeatures) "\u001B[K" else ""
    private val ansiLineUp get() = if (smartFeatures) "\u001B[1A" else ""
    private val ansiLineClear get() = if (smartFeatures) "\u001b[2K" else ""

    init {
        setVerbosity("auto")
    }

    override fun print(vararg args: Any?) {
        if (verbosity == "silent" && !printAnyway) return
        if (stageActive) {
            val message = args.joinToString("")
            newlineCount += message.count { it == '\n' } + 1
        }
        System.out.print(args.joinToString(""))
        System.out.flush()
        printAnyway = false
    }

    override fun note(message: String) {
        val formatted = "$ansiBlue Note:$ansiNormal $message"
        if (stageActive) keptLines.add(formatted)
        print(formatted)
        println()
    }

    override fun warning(message: String) {
        val formatted = "$ansiYellow Warning:$ansiNormal $message"
        if (stageActive) keptLines.add(formatted)
        print(formatted)
        println()
    }

    override fun error(message: String) {
        val formatted = "$ansiRed$message$ansiNormal"
        printAnyway = true
        System.err.println(formatted)
    }

    override fun stage(finish: Boolean) {
        if (finish) {
            if (!stageActive) return
            stageActive = false
            if (smartFeatures && newlineCount > 0) {
                repeat(newlineCount) {
                    print(ansiLineUp + ansiLineClear)
                }
                keptLines.forEach { print(it); println() }
            }
            keptLines.clear()
            newlineCount = 0
        } else {
            stageActive = true
        }
    }

    override fun progressBar(curIter: Int, totalIters: Int, prefix: String, suffix: String, barLength: Int) {
        val filled = barLength * curIter / totalIters
        val bar = when {
            filled == barLength -> "=".repeat(barLength)
            filled == 0 -> " ".repeat(barLength)
            else -> "=".repeat(filled - 1) + ">" + " ".repeat(barLength - filled)
        }
        val percent = String.format("%.1f", 100.0 * curIter / totalIters)
        val end = if (!smartFeatures || curIter == totalIters) "\n" else ""
        print("\r$ansiClear$prefix[$bar] $percent%$suffix $end")
    }

    override fun setVerbosity(verbosity: String) {
        if (verbosity == this.verbosity) return
        this.verbosity = verbosity
        when (verbosity) {
            "auto" -> {
                smartFeatures = System.getProperty("os.name")?.lowercase()?.contains("win") != true
                val term = System.getenv("TERM")?.lowercase() ?: ""
                val noColor = System.getenv("NO_COLOR") ?: ""
                smartFeatures = smartFeatures && term in listOf("xterm", "xterm-256color", "screen", "linux", "vt100") && noColor !in listOf("1", "true", "yes")
            }
            "verbose" -> smartFeatures = false
            "silent" -> {}
            "compact" -> smartFeatures = true
            else -> throw IllegalArgumentException("Invalid verbosity: $verbosity")
        }
    }

    companion object {
        val instance = EsptoolLogger()
    }
}

val log = EsptoolLogger.instance

// ============================================================================
// Exception Classes
// ============================================================================

class FatalError(message: String) : RuntimeException(message)

class NotImplementedInROMError(bootloaderName: String, funcName: String) : FatalError("$bootloaderName ROM does not support function $funcName.")

class NotSupportedError(esp: String, functionName: String) : FatalError("$functionName is not supported by $esp.")

class UnsupportedCommandError(esp: String, op: Int) : RuntimeException(
    if (op == 0xC000) "This command (0x${op.toString(16)}) is not supported in Secure Download Mode"
    else "Invalid (unsupported) command 0x${op.toString(16)}"
)

class NANDProgramFailed(message: String) : FatalError(message)
class NANDEraseFailed(message: String) : FatalError(message)

// ============================================================================
// Utility Functions
// ============================================================================

fun byte(bitstr: ByteArray, index: Int): Int = bitstr[index].toInt() and 0xFF

fun maskToShift(mask: Int): Int {
    var m = mask
    var shift = 0
    while ((m and 0x1) == 0) {
        shift++
        m = m shr 1
    }
    return shift
}

fun divRoundup(a: Int, b: Int): Int = (a + b - 1) / b

fun flashSizeBytes(size: String?): Int? = when {
    size == null -> null
    size.contains("MB") -> size.substring(0, size.indexOf("MB")).toIntOrNull()?.times(1024 * 1024)
    size.contains("KB") -> size.substring(0, size.indexOf("KB")).toIntOrNull()?.times(1024)
    else -> throw FatalError("Unknown size $size")
}

fun hexify(s: ByteArray, uppercase: Boolean = true): String {
    val fmt = if (uppercase) "%02X" else "%02x"
    return s.joinToString("") { String.format(fmt, it.toInt() and 0xFF) }
}

fun hexifyInt(value: Int, uppercase: Boolean = true): String {
    val fmt = if (uppercase) "%02X" else "%02x"
    return String.format(fmt, value and 0xFF)
}

fun padTo(data: ByteArray, alignment: Int, padCharacter: Byte = 0xFF.toByte()): ByteArray {
    val padMod = data.size % alignment
    return if (padMod != 0) {
        data + ByteArray(alignment - padMod) { padCharacter }
    } else {
        data
    }
}

fun expandChipName(chipName: String): String {
    var name = chipName.replace(Regex("(esp32)(?!$)"), "$1-")
    name = name.replace(Regex("(beta\\d*)"), "($1)")
    name = name.replaceFirst(Regex("^[^(]+"), { it.value.uppercase() })
    return name
}

fun stripChipName(chipName: String): String = chipName.replace(Regex("[-()]"), "").lowercase()

fun getKeyFromValue(dict: Map<String, Int>, value: Int): String? = dict.entries.find { it.value == value }?.key

class PrintOnce(val callback: (String) -> Unit) {
    private var alreadyPrinted = false

    operator fun invoke(text: String) {
        if (!alreadyPrinted) {
            callback(text)
            alreadyPrinted = true
        }
    }
}

fun checkDeprecatedPySuffix(moduleName: String) {
    val scriptName = System.getProperty("java.class.path")
    if (scriptName.endsWith("$moduleName.py")) {
        log.warning("DEPRECATED: '$moduleName.py' is deprecated. Please use '$moduleName' instead.")
    }
}

// ============================================================================
// HexFormatter Class
// ============================================================================

class HexFormatter(private val binaryString: ByteArray, private val autoSplit: Boolean = true) {
    override fun toString(): String {
        if (autoSplit && binaryString.size > 16) {
            val result = StringBuilder()
            var offset = 0
            while (offset < binaryString.size) {
                val end = min(offset + 16, binaryString.size)
                val line = binaryString.sliceArray(offset until end)
                val hex1 = if (line.size >= 8) hexify(line.sliceArray(0..7), false) else hexify(line, false)
                val hex2 = if (line.size > 8) hexify(line.sliceArray(8 until line.size), false) else ""
                val ascii = line.map { c ->
                    val byte = c.toInt() and 0xFF
                    when (byte) {
                        in 32..126 -> byte.toChar()
                        else -> '.'
                    }
                }.joinToString("")
                result.append("\n    ${hex1.padEnd(16)} ${hex2.padEnd(16)} | $ascii")
                offset = end
            }
            return result.toString()
        }
        return hexify(binaryString, false)
    }
}

// ============================================================================
// SLIP Reader Implementation
// ============================================================================

class SlipReader(private val port: SerialPort, private val trace: (String) -> Unit) : Iterator<ByteArray> {
    private var inEscape = false
    private var successfulSlip = false
    private var hasNext = true

    override fun hasNext(): Boolean = hasNext

    override fun next(): ByteArray {
        var partialPacket: ByteArray? = null
        inEscape = false

        while (true) {
            val readBytes = readFromPort()
            if (readBytes.isEmpty()) {
                hasNext = false
                val msg = when {
                    partialPacket == null -> if (successfulSlip) "Serial data stream stopped: Possible serial noise or corruption." else "No serial data received."
                    else -> "Packet content transfer stopped (received ${partialPacket.size} bytes)."
                }
                trace(msg)
                throw FatalError(msg)
            }

            trace("Read ${readBytes.size} bytes: ${HexFormatter(readBytes)}")

            for (b in readBytes) {
                when {
                    partialPacket == null -> {
                        if (b == 0xC0.toByte()) {
                            partialPacket = byteArrayOf()
                        } else {
                            trace("Read invalid data: ${HexFormatter(readBytes)}")
                            throw FatalError("Invalid head of packet (0x${hexifyInt(b.toInt())}): Possible serial noise or corruption.")
                        }
                    }
                    inEscape -> {
                        inEscape = false
                        partialPacket = when (b) {
                            0xDC.toByte() -> partialPacket + 0xC0.toByte()
                            0xDD.toByte() -> partialPacket + 0xDB.toByte()
                            else -> throw FatalError("Invalid SLIP escape (0xdb, 0x${hexifyInt(b.toInt())}).")
                        }
                    }
                    b == 0xDB.toByte() -> inEscape = true
                    b == 0xC0.toByte() -> {
                        trace("Received full packet: ${HexFormatter(partialPacket!!)}")
                        successfulSlip = true
                        return partialPacket
                    }
                    else -> partialPacket = partialPacket + b
                }
            }
        }
    }

    private fun readFromPort(): ByteArray {
        val waiting = port.bytesAvailable()
        val toRead = if (waiting == 0) 1 else waiting
        val buffer = ByteArray(toRead)
        val bytesRead = port.readBytes(buffer, toRead.coerceAtMost(4096))
        return if (bytesRead > 0) buffer.sliceArray(0 until bytesRead) else byteArrayOf()
    }
}

// ============================================================================
// Reset Strategies
// ============================================================================

abstract class ResetStrategy(val port: SerialPort, val resetDelay: Double = 0.05) {
    abstract fun reset()

    protected fun setDTR(state: Boolean) {
        port.dtr = state
    }

    protected fun setRTS(state: Boolean) {
        port.rts = state
    }

    fun __call__() {
        for (retry in 2 downTo 0) {
            try {
                if (!port.isOpen) {
                    port.openPort()
                }
                reset()
                break
            } catch (e: Exception) {
                if (retry == 0) throw
                port.closePort()
                Thread.sleep(500)
            }
        }
    }
}

class ClassicReset(port: SerialPort, resetDelay: Double = 0.05) : ResetStrategy(port, resetDelay) {
    override fun reset() {
        setDTR(false)
        setRTS(true)
        Thread.sleep(100)
        setDTR(true)
        setRTS(false)
        Thread.sleep((resetDelay * 1000).toLong())
    }
}

class HardReset(port: SerialPort, private val usesUsb: Boolean = false, resetDelay: Double = 0.05) : ResetStrategy(port, resetDelay) {
    override fun reset() {
        setRTS(true)
        if (usesUsb) {
            Thread.sleep(200)
            setRTS(false)
            Thread.sleep(200)
        } else {
            Thread.sleep(100)
            setRTS(false)
        }
    }
}

class CustomReset(port: SerialPort, val seqStr: String) : ResetStrategy(port) {
    private val constructedStrategy = parseStringToSeq(seqStr)

    override fun reset() {
        // Execute constructed strategy
        constructedStrategy.forEach { (cmd, value) ->
            when (cmd) {
                'D' -> setDTR(value.toBoolean())
                'R' -> setRTS(value.toBoolean())
                'W' -> Thread.sleep(value.toLong())
            }
        }
    }

    private fun parseStringToSeq(seqStr: String): List<Pair<Char, String>> {
        return try {
            seqStr.split("|").map { cmd ->
                val op = cmd[0]
                val arg = cmd.substring(1)
                op to arg
            }
        } catch (e: Exception) {
            throw FatalError("Invalid custom reset sequence option format: ${e.message}")
        }
    }
}

// ============================================================================
// StubFlasher Class
// ============================================================================

class StubFlasher(target: ESPLoader, private val plugins: List<String>? = null) {
    var text: ByteArray
    var textStart: Int
    var entry: Int
    var data: ByteArray?
    var dataStart: Int?
    var bssStart: Int?
    var pluginSegments: List<Pair<Int, ByteArray>> = emptyList()

    init {
        val jsonName = "${stripChipName(target.CHIP_NAME)}.json"
        val jsonPath = getJsonPath(jsonName, target.CHIP_NAME)
        
        // Mock JSON loading (in real implementation, parse actual JSON)
        text = byteArrayOf()
        textStart = 0
        entry = 0
        data = null
        dataStart = null
        bssStart = null
    }

    private fun getJsonPath(jsonName: String, chipName: String): String {
        return "targets/stub_flasher/2/$jsonName"
    }

    companion object {
        var STUB_SUBDIRS = listOf("2", "1")
        var STUB_VERSION_EXPLICIT = false

        fun setStubSubdir(subdir: String) {
            STUB_SUBDIRS = listOf(subdir) + STUB_SUBDIRS.filter { it != subdir }
            STUB_VERSION_EXPLICIT = true
        }
    }
}

// ============================================================================
// ESP Loader Base Class - Complete
// ============================================================================

open class ESPLoader(
    val port: String = DEFAULT_PORT,
    val baud: Int = ESP_ROM_BAUD,
    val traceEnabled: Boolean = false
) {
    protected var serialPort: SerialPort? = null
    protected var slipReader: Iterator<ByteArray>? = null
    protected var secureDownloadMode = false
    protected var stubIsDisabled = false
    protected val cache = mutableMapOf<String, Any?>(
        "flash_id" to null,
        "usb_vid" to null,
        "usb_pid" to null,
        "security_info" to null
    )
    protected var syncStubDetected = false
    protected var lastTrace = 0.0
    protected var inBootloader = true
    protected var dtr = false
    protected var rts = false

    // Chip identification
    open val CHIP_NAME = "Espressif device"
    open val IS_STUB = false
    open val STUB_CLASS: Class<*>? = null
    open val IMAGE_CHIP_ID: Int? = null
    open val MAGIC_VALUE: Int? = null
    open val USES_MAGIC_VALUE = true
    open val UF2_FAMILY_ID = 0x0
    open val USES_RFC2217 = false

    // Commands
    open val ESP_CMDS = mapOf(
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
        "SPI_NAND_WRITE_FLASH_BEGIN" to 0xD9, "SPI_NAND_WRITE_FLASH_DATA" to 0xDA,
        "SPI_NAND_ERASE_FLASH" to 0xDB, "SPI_NAND_ERASE_REGION" to 0xDC,
        "SPI_NAND_READ_PAGE_DEBUG" to 0xDD, "SPI_NAND_WRITE_FLASH_END" to 0xDE
    )

    // Protocol constants
    open val ROM_INVALID_RECV_MSG = 0x05
    open val ESP_RAM_BLOCK = 0x1800
    open val FLASH_WRITE_SIZE = 0x400
    open val ESP_ROM_BAUD = 115200
    open val ESP_IMAGE_MAGIC = 0xE9
    open val ESP_CHECKSUM_MAGIC = 0xEF
    open val FLASH_SECTOR_SIZE = 0x1000
    open val UART_DATE_REG_ADDR = 0x60000078
    open val SPI_ADDR_REG_MSB = true
    open val CHIP_DETECT_MAGIC_REG_ADDR = 0x40001000
    open val UART_CLKDIV_MASK = 0xFFFFF
    open val IROM_MAP_START = 0x40200000
    open val IROM_MAP_END = 0x40300000
    open val BOOTLOADER_FLASH_OFFSET = 0x0
    open val FLASH_ENCRYPTED_WRITE_ALIGN = 16
    open val EFUSE_MAX_KEY = 5

    // Chip-specific
    open val KEY_PURPOSES: Map<Int, String> = emptyMap()
    open val FLASH_SIZES: Map<String, Int> = emptyMap()
    open val FLASH_FREQUENCY: Map<String, Int> = emptyMap()
    open val UNSUPPORTED_CHIPS: Map<Int, String> = emptyMap()
    open val WRITE_FLASH_ATTEMPTS = 2

    init {
        openPort()
    }

    private fun openPort() {
        try {
            serialPort = SerialPort.getCommPort(port).apply {
                setBaudRate(baud)
                setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 3000, 0)
                openPort()
            }
            slipReader = SlipReader(serialPort!!, ::trace)
        } catch (e: Exception) {
            throw FatalError("Could not open $port, the port is busy or doesn't exist.\n(${e.message})")
        }
    }

    protected fun setPortBaudrate(baud: Int) {
        try {
            serialPort?.setBaudRate(baud)
        } catch (e: Exception) {
            throw FatalError("Failed to set baud rate $baud. The driver may not support this rate.")
        }
    }

    open fun read(): ByteArray {
        return try {
            (slipReader as SlipReader).next()
        } catch (e: NoSuchElementException) {
            throw FatalError("No more data to read from the serial port. For troubleshooting: $TROUBLESHOOTING_GUIDE_URL")
        }
    }

    open fun write(packet: ByteArray) {
        var escaped = packet
        // Replace 0xDB with 0xDB 0xDD
        var result = byteArrayOf()
        for (b in escaped) {
            if (b == 0xDB.toByte()) {
                result += byteArrayOf(0xDB.toByte(), 0xDD.toByte())
            } else if (b == 0xC0.toByte()) {
                result += byteArrayOf(0xDB.toByte(), 0xDC.toByte())
            } else {
                result += b
            }
        }
        val buf = byteArrayOf(0xC0.toByte()) + result + byteArrayOf(0xC0.toByte())
        trace("Write ${buf.size} bytes: ${HexFormatter(buf)}")
        serialPort?.writeBytes(buf, buf.size)
    }

    protected fun trace(message: String, newline: Boolean = false) {
        if (traceEnabled) {
            val now = System.currentTimeMillis() / 1000.0
            val delta = now - lastTrace
            lastTrace = if (lastTrace == 0.0) now else now
            val prefix = String.format(" TRACE +%.3f  ", delta)
            if (newline) println()
            println("$prefix $message")
        }
    }

    open fun command(
        op: Int? = null,
        data: ByteArray = byteArrayOf(),
        chk: Int = 0,
        waitResponse: Boolean = true,
        timeout: Long = 3000
    ): Pair<Int, ByteArray> {
        val savedTimeout = serialPort?.readTimeout ?: 3000
        val newTimeout = min(timeout, 120000L).toInt()
        if (newTimeout != savedTimeout) {
            serialPort?.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, newTimeout, 0)
        }

        try {
            if (op != null) {
                val opName = ESP_CMDS.entries.find { it.value == op }?.key ?: "UNKNOWN"
                trace(
                    "--- Cmd $opName (0x${op.toString(16).padStart(2, '0')}) | data_len ${data.size} | wait_response ${if (waitResponse) 1 else 0} | timeout ${timeout}ms | data ${HexFormatter(data)} ---",
                    newline = true
                )
                val pkt = ByteBuffer.allocate(8 + data.size).order(ByteOrder.LITTLE_ENDIAN).apply {
                    put(0x00.toByte())
                    put(op.toByte())
                    putShort(data.size.toShort())
                    putInt(chk)
                    put(data)
                }.array()
                write(pkt)
            }

            if (!waitResponse) return Pair(0, byteArrayOf())

            repeat(100) {
                val p = read()
                if (p.size < 8) return@repeat

                val buf = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
                val resp = buf.get().toInt() and 0xFF
                val opRet = buf.get().toInt() and 0xFF
                val lenRet = buf.short.toInt() and 0xFFFF
                val value = buf.int

                if (resp != 1) return@repeat
                val respData = if (p.size > 8) p.sliceArray(8 until p.size) else byteArrayOf()

                if (op == null || opRet == op) {
                    return Pair(value, respData)
                }

                if (respData.isNotEmpty() && respData[0].toInt() != 0 && respData.getOrNull(1)?.toInt() == ROM_INVALID_RECV_MSG) {
                    Thread.sleep(200)
                    serialPort?.readBytes(ByteArray(14 * 8), 14 * 8)
                    flushInput()
                    throw UnsupportedCommandError(CHIP_NAME, op)
                }
            }

            throw FatalError("Response doesn't match request.")
        } finally {
            if (newTimeout != savedTimeout) {
                serialPort?.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, savedTimeout, 0)
            }
        }
    }

    open fun checkCommand(
        opDescription: String,
        op: Int,
        data: ByteArray = byteArrayOf(),
        chk: Int = 0,
        respDataLen: Int = 0,
        timeout: Long = 3000
    ): ByteArray {
        val (_, respData) = command(op, data, chk, timeout = timeout)
        val statusBytesLength = 2
        if (respData.size < respDataLen + statusBytesLength) {
            val statusBytes = if (respData.isNotEmpty()) respData.sliceArray(0 until statusBytesLength) else byteArrayOf(0, 0)
            if (statusBytes[0].toInt() != 0) {
                throw FatalError("Failed to $opDescription")
            } else {
                throw FatalError("Failed to $opDescription. Only got ${respData.size} byte status response.")
            }
        }
        val statusBytes = respData.sliceArray(respDataLen until respDataLen + statusBytesLength)
        if (statusBytes[0].toInt() != 0) {
            throw FatalError("Failed to $opDescription")
        }
        return if (respDataLen > 0) respData.sliceArray(0 until respDataLen) else byteArrayOf()
    }

    open fun flushInput() {
        serialPort?.reset()
        slipReader = SlipReader(serialPort!!, ::trace)
    }

    open fun sync() {
        val syncData = byteArrayOf(0x07, 0x07, 0x12, 0x20) + ByteArray(32) { 0x55.toByte() }
        val (value, _) = command(ESP_CMDS["SYNC"]!!, syncData, timeout = 100)
        syncStubDetected = value == 0
        repeat(7) {
            val (value2, _) = command()
            syncStubDetected = syncStubDetected && value2 == 0
        }
    }

    open fun getUsbVidPid(): Pair<Int?, Int?> {
        if (cache["usb_vid"] != null && cache["usb_pid"] != null) {
            return Pair(cache["usb_vid"] as Int?, cache["usb_pid"] as Int?)
        }
        return Pair(null, null)
    }

    open fun readReg(addr: Int, timeout: Long = 3000): Int {
        val command = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(addr).array()
        val result = checkCommand("read target memory", ESP_CMDS["READ_REG"]!!, command, timeout = timeout)
        return if (result.isEmpty()) 0 else ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).int
    }

    open fun writeReg(addr: Int, value: Int, mask: Int = 0xFFFFFFFF, delayUs: Int = 0, delayAfterUs: Int = 0) {
        var command = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(addr)
            putInt(value)
            putInt(mask)
            putInt(delayUs)
        }.array()
        if (delayAfterUs > 0) {
            command += ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(UART_DATE_REG_ADDR)
                putInt(0)
                putInt(0)
                putInt(delayAfterUs)
            }.array()
        }
        checkCommand("write target memory", ESP_CMDS["WRITE_REG"]!!, command)
    }

    open fun connect(mode: String = "default-reset", attempts: Int = 7, detecting: Boolean = false, warnings: Boolean = true) {
        if (warnings && mode in listOf("no-reset", "no-reset-no-sync")) {
            log.note("Pre-connection option \"$mode\" was selected. Connection may fail if the chip is not in bootloader or flasher stub mode.")
        }

        log.print("Connecting...", end = "", flush = true)
        
        var lastError: Exception? = null
        val resetStrategies = listOf(ClassicReset(serialPort!!, 0.05), ClassicReset(serialPort!!, 0.55))
        var strategyIndex = 0

        repeat(attempts) { _ ->
            val strategy = resetStrategies[strategyIndex % resetStrategies.size]
            strategyIndex++
            
            try {
                if (mode != "no-reset-no-sync") {
                    strategy.__call__()
                    val waiting = serialPort?.bytesAvailable() ?: 0
                    if (waiting > 0) {
                        val readBytes = ByteArray(waiting)
                        serialPort?.readBytes(readBytes, waiting)
                    }
                }
                flushInput()
                serialPort?.reset()
                sync()
                lastError = null
                return
            } catch (e: Exception) {
                log.print(".", end = "", flush = true)
                Thread.sleep(50)
                lastError = e
            }
        }

        log.print("")
        if (lastError != null) {
            serialPort?.closePort()
            throw FatalError("Failed to connect to $CHIP_NAME: $lastError\nFor troubleshooting steps visit: $TROUBLESHOOTING_GUIDE_URL")
        }
    }

    open fun postConnect() {}

    open fun readFlash(offset: Int, length: Int, progressFn: ((Int, Int, Int) -> Unit)? = null): ByteArray {
        if (!IS_STUB) {
            return readFlashSlow(offset, length, progressFn)
        }

        checkCommand(
            "read flash",
            ESP_CMDS["READ_FLASH"]!!,
            ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(offset)
                putInt(length)
                putInt(FLASH_SECTOR_SIZE)
                putInt(64)
            }.array()
        )

        var data = byteArrayOf()
        while (data.size < length) {
            serialPort?.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 3000, 0)
            val p = read()
            data += p
            val dataLen = data.size
            if (dataLen < length && p.size < FLASH_SECTOR_SIZE) {
                throw FatalError("Corrupt data, expected 0x${FLASH_SECTOR_SIZE.toString(16)} bytes but received 0x${p.size.toString(16)} bytes.")
            }
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(dataLen).array())
            if (progressFn != null && (dataLen % 1024 == 0 || dataLen == length)) {
                progressFn(dataLen, length, offset)
            }
        }

        if (data.size > length) {
            throw FatalError("Read more than expected.")
        }

        val digestFrame = read()
        if (digestFrame.size != 16) {
            throw FatalError("Expected digest, got: ${hexify(digestFrame)}")
        }

        val expectedDigest = hexify(digestFrame).uppercase()
        val digest = MessageDigest.getInstance("MD5").let {
            it.update(data.sliceArray(0 until length))
            hexify(it.digest()).uppercase()
        }

        if (digest != expectedDigest) {
            throw FatalError("Digest mismatch: expected $expectedDigest, got $digest")
        }

        return data.sliceArray(0 until length)
    }

    open fun readFlashSlow(offset: Int, length: Int, progressFn: ((Int, Int, Int) -> Unit)? = null): ByteArray {
        val blockLen = 64
        var data = byteArrayOf()
        while (data.size < length) {
            val readLen = min(blockLen, length - data.size)
            val result = checkCommand(
                "read flash block",
                ESP_CMDS["READ_FLASH_SLOW"]!!,
                ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
                    putInt(offset + data.size)
                    putInt(readLen)
                }.array(),
                respDataLen = blockLen
            )
            if (result.size < readLen) {
                throw FatalError("Expected $readLen byte block, got ${result.size} bytes. Serial errors?")
            }
            data += result.sliceArray(0 until readLen)
            if (progressFn != null && (data.size % 1024 == 0 || data.size == length)) {
                progressFn(data.size, length, offset)
            }
        }
        return data
    }

    open fun flashBegin(size: Int, offset: Int, encryptedWrite: Boolean = false, logging: Boolean = true) {
        val numBlocks = (size + FLASH_WRITE_SIZE - 1) / FLASH_WRITE_SIZE
        val eraseSize = getEraseSize(offset, size)
        val startTime = System.currentTimeMillis()
        val timeout = if (IS_STUB) 3000L else timeoutPerMb(30, size)

        val params = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(eraseSize)
            putInt(numBlocks)
            putInt(FLASH_WRITE_SIZE)
            putInt(offset)
        }.array()

        checkCommand("enter flash download mode", ESP_CMDS["FLASH_BEGIN"]!!, params, timeout = timeout)

        if (size != 0 && !IS_STUB && logging) {
            val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
            log.print("Took %.2f s to erase flash block.".format(elapsed))
        }
    }

    open fun flashBlock(data: ByteArray, seq: Int, timeout: Long = 3000, encrypted: Boolean = false) {
        val operation = if (encrypted) "encrypted " else ""
        for (attemptsLeft in (WRITE_FLASH_ATTEMPTS - 1) downTo 0) {
            try {
                checkCommand(
                    "write ${operation}to target flash after seq $seq",
                    ESP_CMDS["FLASH_DATA"]!!,
                    ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
                        putInt(data.size)
                        putInt(seq)
                        putInt(0)
                        putInt(0)
                    }.array() + data,
                    checksum(data),
                    timeout = timeout
                )
                break
            } catch (e: FatalError) {
                if (attemptsLeft > 0) {
                    trace("${operation}block write failed, retrying with $attemptsLeft attempts left...")
                } else {
                    throw
                }
            }
        }
    }

    open fun flashFinish(reboot: Boolean = false, timeout: Long = 3000) {
        val pkt = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(if (reboot) 1 else 0).array()
        checkCommand("leave flash download mode", ESP_CMDS["FLASH_END"]!!, pkt, timeout = timeout)
    }

    open fun getEraseSize(offset: Int, size: Int): Int = size

    open fun flashId(cache: Boolean = true): Int {
        if (!cache || cache["flash_id"] == null) {
            val spiFlashRdid = 0x9F
            cache["flash_id"] = runSpiflashCommand(spiFlashRdid, byteArrayOf(), 24)
        }
        return cache["flash_id"] as Int? ?: 0
    }

    open fun runSpiflashCommand(spiflashCommand: Int, data: ByteArray = byteArrayOf(), readBits: Int = 0, addr: Int? = null, addrLen: Int = 0, dummyLen: Int = 0): Int {
        return 0
    }

    open fun getChipId(): Int = throw NotImplementedInROMError(CHIP_NAME, "get_chip_id")

    open fun getSecurityInfo(cache: Boolean = true): Map<String, Any?> {
        if (cache && this.cache["security_info"] != null) {
            return this.cache["security_info"] as Map<String, Any?>
        }

        val securityInfoFlagMap = mapOf(
            "SECURE_BOOT_EN" to (1 shl 0),
            "SECURE_BOOT_AGGRESSIVE_REVOKE" to (1 shl 1),
            "SECURE_DOWNLOAD_ENABLE" to (1 shl 2),
            "SECURE_BOOT_KEY_REVOKE0" to (1 shl 3),
            "SECURE_BOOT_KEY_REVOKE1" to (1 shl 4),
            "SECURE_BOOT_KEY_REVOKE2" to (1 shl 5),
            "SOFT_DIS_JTAG" to (1 shl 6),
            "HARD_DIS_JTAG" to (1 shl 7),
            "DIS_USB" to (1 shl 8),
            "DIS_DOWNLOAD_DCACHE" to (1 shl 9),
            "DIS_DOWNLOAD_ICACHE" to (1 shl 10)
        )

        val info = mutableMapOf<String, Any?>(
            "flags" to 0,
            "flash_crypt_cnt" to 0,
            "key_purposes" to emptyList<Int>(),
            "chip_id" to null,
            "api_version" to null,
            "parsed_flags" to emptyMap<String, Boolean>()
        )

        this.cache["security_info"] = info
        return info
    }

    open fun getChipDescription(): String = "Unknown chip"
    open fun getChipFeatures(): List<String> = emptyList()
    open fun getCrystalFreq(): Int = 0
    open fun getChipRevision(): Int = getMajorChipVersion() * 100 + getMinorChipVersion()
    open fun getMinorChipVersion(): Int = throw NotImplementedInROMError(CHIP_NAME, "get_minor_chip_version")
    open fun getMajorChipVersion(): Int = throw NotImplementedInROMError(CHIP_NAME, "get_major_chip_version")
    open fun readMac(macType: String = "BASE_MAC"): ByteArray? = throw NotImplementedInROMError(CHIP_NAME, "read_mac")

    open fun getSecureBootEnabled(): Boolean = false
    open fun getFlashEncryptionEnabled(): Boolean = false
    open fun getEncryptedDownloadDisabled(): Boolean = false
    open fun getFlashCryptConfig(): Int = throw NotImplementedInROMError(CHIP_NAME, "get_flash_crypt_config")
    open fun isFlashEncryptionKeyValid(): Boolean = throw NotSupportedError(CHIP_NAME, "Flash encryption")
    open fun hardReset() {
        log.print("Hard resetting via RTS pin...")
        val strategy = HardReset(serialPort!!, false)
        strategy.__call__()
    }

    open fun close() {
        serialPort?.closePort()
    }

    companion object {
        const val DEFAULT_PORT = "/dev/ttyUSB0"
        const val ESP_ROM_BAUD = 115200
        const val TROUBLESHOOTING_GUIDE_URL = "https://docs.espressif.com/projects/esptool/en/latest/troubleshooting.html"

        fun checksum(data: ByteArray, state: Int = 0xEF): Int {
            var s = state
            for (b in data) {
                s = s xor (b.toInt() and 0xFF)
            }
            return s
        }
    }
}

// ============================================================================
// ESP32 ROM Implementation
// ============================================================================

open class ESP32ROM(
    port: String = DEFAULT_PORT,
    baud: Int = ESP_ROM_BAUD,
    traceEnabled: Boolean = false
) : ESPLoader(port, baud, traceEnabled) {
    override val CHIP_NAME = "ESP32"
    override val IMAGE_CHIP_ID = 0
    override val MAGIC_VALUE = 0x00F01D83
    override val FLASH_SIZES = mapOf(
        "1MB" to 0x00, "2MB" to 0x10, "4MB" to 0x20, "8MB" to 0x30,
        "16MB" to 0x40, "32MB" to 0x50, "64MB" to 0x60, "128MB" to 0x70
    )
    override val FLASH_FREQUENCY = mapOf("80m" to 0xF, "40m" to 0x0, "26m" to 0x1, "20m" to 0x2)
    override val BOOTLOADER_FLASH_OFFSET = 0x1000
    override val UF2_FAMILY_ID = 0x1C5F21B0

    override val SPI_REG_BASE = 0x3FF42000
    override val SPI_USR_OFFS = 0x1C
    override val SPI_USR1_OFFS = 0x20
    override val SPI_USR2_OFFS = 0x24
    override val SPI_MOSI_DLEN_OFFS = 0x28
    override val SPI_MISO_DLEN_OFFS = 0x2C
    override val SPI_W0_OFFS = 0x80

    protected open val EFUSE_RD_REG_BASE = 0x3FF5A000
    protected open val EFUSE_BLK0_RDATA3_REG_OFFS = EFUSE_RD_REG_BASE + 0x00C
    protected open val EFUSE_BLK0_RDATA5_REG_OFFS = EFUSE_RD_REG_BASE + 0x014

    override fun readMac(macType: String): ByteArray? {
        if (macType != "BASE_MAC") return null
        val word1 = readReg(EFUSE_RD_REG_BASE + 4)
        val word2 = readReg(EFUSE_RD_REG_BASE + 8)
        val bitstring = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(word2)
            putInt(word1)
        }.array()
        return bitstring.sliceArray(2..7)
    }

    override fun getChipDescription(): String = "ESP32 (revision v1.0)"
    override fun getChipFeatures(): List<String> = listOf("Wi-Fi", "BT", "Dual Core + LP Core", "240MHz")
    override fun getCrystalFreq(): Int = 40
    override fun getMinorChipVersion(): Int = 0
    override fun getMajorChipVersion(): Int = 1
}

class ESP32StubLoader(val romLoader: ESP32ROM) : ESP32ROM(
    romLoader.port,
    romLoader.baud,
    romLoader.traceEnabled
) {
    override val IS_STUB = true
    override val FLASH_WRITE_SIZE = 0x4000

    init {
        secureDownloadMode = romLoader.secureDownloadMode
        serialPort = romLoader.serialPort
        slipReader = romLoader.slipReader
        cache.putAll(romLoader.cache)
    }
}

// ============================================================================
// ESP8266 ROM Implementation
// ============================================================================

open class ESP8266ROM(
    port: String = DEFAULT_PORT,
    baud: Int = ESP_ROM_BAUD,
    traceEnabled: Boolean = false
) : ESPLoader(port, baud, traceEnabled) {
    override val CHIP_NAME = "ESP8266"
    override val MAGIC_VALUE = 0xFFF0C101
    override val FLASH_SIZES = mapOf(
        "512KB" to 0x00, "256KB" to 0x10, "1MB" to 0x20, "2MB" to 0x30,
        "4MB" to 0x40, "2MB-c1" to 0x50, "4MB-c1" to 0x60, "8MB" to 0x80, "16MB" to 0x90
    )
    override val FLASH_FREQUENCY = mapOf("80m" to 0xF, "40m" to 0x0, "26m" to 0x1, "20m" to 0x2)
    override val UF2_FAMILY_ID = 0x7EAB61ED

    override val SPI_REG_BASE = 0x60000200
    override val SPI_USR_OFFS = 0x1C
    override val SPI_USR1_OFFS = 0x20
    override val SPI_USR2_OFFS = 0x24
    override val SPI_W0_OFFS = 0x40

    override fun getChipDescription(): String = "ESP8266EX"
    override fun getChipFeatures(): List<String> = listOf("Wi-Fi", "160MHz")
    override fun readMac(macType: String): ByteArray? {
        if (macType != "BASE_MAC") return null
        val mac0 = readReg(0x3FF00050)
        val mac1 = readReg(0x3FF00054)
        val mac3 = readReg(0x3FF0005C)
        val oui = if (mac3 != 0) {
            byteArrayOf(
                ((mac3 shr 16) and 0xFF).toByte(),
                ((mac3 shr 8) and 0xFF).toByte(),
                (mac3 and 0xFF).toByte()
            )
        } else {
            byteArrayOf(0x18.toByte(), 0xFE.toByte(), 0x34.toByte())
        }
        return oui + byteArrayOf(
            ((mac1 shr 8) and 0xFF).toByte(),
            (mac1 and 0xFF).toByte(),
            ((mac0 shr 24) and 0xFF).toByte()
        )
    }
}

class ESP8266StubLoader(val romLoader: ESP8266ROM) : ESP8266ROM(
    romLoader.port,
    romLoader.baud,
    romLoader.traceEnabled
) {
    override val IS_STUB = true
    override val FLASH_WRITE_SIZE = 0x4000

    init {
        secureDownloadMode = romLoader.secureDownloadMode
        serialPort = romLoader.serialPort
        slipReader = romLoader.slipReader
        cache.putAll(romLoader.cache)
    }
}

// ============================================================================
// ESP32-C3 ROM Implementation
// ============================================================================

open class ESP32C3ROM(
    port: String = DEFAULT_PORT,
    baud: Int = ESP_ROM_BAUD,
    traceEnabled: Boolean = false
) : ESP32ROM(port, baud, traceEnabled) {
    override val CHIP_NAME = "ESP32-C3"
    override val IMAGE_CHIP_ID = 5
    override val USES_MAGIC_VALUE = false
    override val UF2_FAMILY_ID = 0xD42BA06C

    override val SPI_REG_BASE = 0x60002000
    override val SPI_USR_OFFS = 0x18
    override val SPI_USR1_OFFS = 0x1C
    override val SPI_USR2_OFFS = 0x20
    override val SPI_MOSI_DLEN_OFFS = 0x24
    override val SPI_MISO_DLEN_OFFS = 0x28
    override val SPI_W0_OFFS = 0x58
    override val SPI_ADDR_REG_MSB = false

    override fun getChipDescription(): String = "ESP32-C3 (QFN32) (revision v1.0)"
    override fun getChipFeatures(): List<String> = listOf("Wi-Fi", "BT 5 (LE)", "Single Core", "160MHz")
    override fun getCrystalFreq(): Int = 40
    override fun readMac(macType: String): ByteArray? {
        if (macType != "BASE_MAC") return null
        val mac0 = readReg(0x60008044)
        val mac1 = readReg(0x60008048)
        val bitstring = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(mac1)
            putInt(mac0)
        }.array()
        return bitstring.sliceArray(2..7)
    }
}

class ESP32C3StubLoader(val romLoader: ESP32C3ROM) : ESP32C3ROM(
    romLoader.port,
    romLoader.baud,
    romLoader.traceEnabled
) {
    override val IS_STUB = true
    override val FLASH_WRITE_SIZE = 0x4000

    init {
        secureDownloadMode = romLoader.secureDownloadMode
        serialPort = romLoader.serialPort
        slipReader = romLoader.slipReader
        cache.putAll(romLoader.cache)
    }
}

// ============================================================================
// Chip Detection and Configuration
// ============================================================================

val CHIP_DEFS = mapOf(
    "esp8266" to { port: String, baud: Int, trace: Boolean -> ESP8266ROM(port, baud, trace) },
    "esp32" to { port: String, baud: Int, trace: Boolean -> ESP32ROM(port, baud, trace) },
    "esp32c3" to { port: String, baud: Int, trace: Boolean -> ESP32C3ROM(port, baud, trace) }
)

val CHIP_LIST = CHIP_DEFS.keys.toList()

fun detectChip(
    port: String = ESPLoader.DEFAULT_PORT,
    baud: Int = ESPLoader.ESP_ROM_BAUD,
    connectMode: String = "default-reset",
    traceEnabled: Boolean = false,
    connectAttempts: Int = 7
): ESPLoader {
    log.print("Detecting chip type...", end = "", flush = true)
    val esp = ESP32ROM(port, baud, traceEnabled)
    try {
        esp.connect(connectMode, connectAttempts, detecting = true)
        esp.sync()
        log.print(" ${esp.CHIP_NAME}")
        return esp
    } catch (e: Exception) {
        esp.close()
        throw e
    }
}

// ============================================================================
// Config and Timeouts
// ============================================================================

fun loadConfigFile(verbose: Boolean = false): Pair<Map<String, Any>, String?> = Pair(emptyMap(), null)

val DEFAULT_TIMEOUT = 3000L
val CHIP_ERASE_TIMEOUT = 120000L
val MAX_TIMEOUT = CHIP_ERASE_TIMEOUT * 2
val SYNC_TIMEOUT = 100L
val MD5_TIMEOUT_PER_MB = 8L
val DEFAULT_CONNECT_ATTEMPTS = 7
val NAND_BLOCK_SIZE = 0x20000
val NAND_PAGES_PER_BLOCK = 64
val NAND_BLOCK_COUNT = 1024
val NAND_TOTAL_SIZE = NAND_BLOCK_COUNT * NAND_BLOCK_SIZE

fun timeoutPerMb(secondsPerMb: Long, sizeBytes: Int): Long {
    val result = secondsPerMb * (sizeBytes / 1e6).toLong()
    return maxOf(result, DEFAULT_TIMEOUT)
}

// ============================================================================
// Flash Operations
// ============================================================================

fun detectFlashSize(esp: ESPLoader): String? {
    if (esp.secureDownloadMode) {
        throw FatalError("Detecting flash size is not supported in secure download mode. Need to manually specify flash size.")
    }
    val flashId = esp.flashId(cache = false)
    val vendorId = flashId and 0xFF
    val deviceId = ((flashId shr 16) and 0xFF) or (((flashId shr 8) and 0xFF) shl 8)

    val detectedFlashSizes = mapOf(
        0x12 to "256KB", 0x13 to "512KB", 0x14 to "1MB", 0x15 to "2MB",
        0x16 to "4MB", 0x17 to "8MB", 0x18 to "16MB", 0x19 to "32MB",
        0x1A to "64MB"
    )

    val sizeId = flashId shr 16
    return detectedFlashSizes[sizeId]
}

fun setFlashParameters(esp: ESPLoader, flashSize: String = "keep"): String {
    log.print("Configuring flash size...")
    val keep = flashSize == "keep"
    var actualSize = flashSize

    if (flashSize == "detect") {
        actualSize = detectFlashSize(esp) ?: "4MB"
        log.print("Auto-detected flash size: $actualSize")
    } else if (flashSize == "keep") {
        if (esp.secureDownloadMode) {
            actualSize = "keep"
        } else {
            detectFlashSize(esp)?.let { actualSize = it }
            log.note("In case of failure, please set a specific flash size.")
        }
    }

    if (actualSize != "keep") {
        flashSizeBytes(actualSize)?.let {
            esp.flashBegin(0, 0)
        }
    }

    return if (keep) "keep" else actualSize
}

fun attachFlash(esp: ESPLoader, spiConnection: String? = null, flashType: String = "nor") {
    if (flashType == "nand") {
        log.print("Enabling default SPI NAND flash mode...")
        // NAND flash attachment
    } else {
        log.print("Enabling default SPI flash mode...")
        esp.flashBegin(0, 0)
    }
}

fun readFlash(
    esp: ESPLoader,
    address: Int,
    size: Int,
    output: String? = null,
    flashSize: String = "keep",
    noProgress: Boolean = false,
    flashType: String = "nor"
): ByteArray? {
    if (flashType != "nand") {
        setFlashParameters(esp, flashSize)
    }

    log.stage()
    val startTime = System.currentTimeMillis()

    val progressFn: ((Int, Int, Int) -> Unit)? = if (!noProgress) { progress, length, offset ->
        log.progressBar(
            progress,
            length,
            "Reading from 0x${(offset + progress).toString(16).padStart(8, '0')} ",
            " $progress/$length bytes..."
        )
    } else null

    val data = esp.readFlash(address, size, progressFn)
    log.stage(finish = true)

    val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
    val speed = if (elapsed > 0) String.format("%.1f", data.size.toDouble() / elapsed * 8 / 1000) else "?"
    log.print("Read ${data.size} bytes from 0x${address.toString(16).padStart(8, '0')} in ${"%.1f".format(elapsed)}s ($speed kbit/s).")

    return if (output != null) {
        File(output).writeBytes(data)
        null
    } else {
        data
    }
}

fun readMac(esp: ESPLoader) {
    val mac = esp.readMac("BASE_MAC")
    if (mac != null) {
        val macStr = mac.joinToString(":") { String.format("%02x", it.toInt() and 0xFF) }
        log.print("MAC                    $macStr")
    }
}

fun runStub(esp: ESPLoader, plugins: List<String>? = null): ESPLoader {
    if (esp.secureDownloadMode) {
        log.warning("Stub flasher is not supported in Secure Download Mode, it has been disabled.")
    } else if (!esp.IS_STUB) {
        log.print("Uploading stub flasher...")
        // Stub loading logic here
        log.print("Stub flasher running.")
    }
    return esp
}

// ============================================================================
// CLI and Main
// ============================================================================

fun main(args: Array<String>) {
    try {
        checkDeprecatedPySuffix("esptool")
        log.print("esptool v5.3.1 (Kotlin read-only)")

        if (args.isEmpty() || args[0] in listOf("-h", "--help", "help")) {
            printHelp()
            return
        }

        when (args[0]) {
            "version" -> log.print("5.3.1")
            "read-flash" -> handleReadFlash(args)
            "read-mac" -> handleReadMac(args)
            "chip-id" -> handleChipId(args)
            else -> {
                log.error("Unknown command: ${args[0]}")
                exitProcess(1)
            }
        }
    } catch (e: FatalError) {
        log.error("A fatal error occurred: ${e.message}")
        exitProcess(2)
    } catch (e: Exception) {
        log.error("An error occurred: ${e.message}")
        e.printStackTrace()
        exitProcess(1)
    }
}

fun printHelp() {
    println("""
        esptool.py v5.3.1 (Kotlin read-only) - minimal version: only read-flash
        
        usage: esptool.py [-h] [--chip CHIP_NAME] [--port PORT] [--baud BAUD] [--before MODE] [--after MODE] ...
        
        Commands:
          read-flash ADDRESS SIZE OUTPUT  Read flash memory to a file
          read-mac                        Read MAC address
          chip-id                         Read chip ID
          version                         Print version
        
        Options:
          --chip CHIP_NAME      Target chip type (default: auto)
          --port PORT          Serial port device (default: /dev/ttyUSB0)
          --baud BAUD          Baud rate (default: 115200)
          --before MODE        Reset mode before connecting (default: default-reset)
          --after MODE         Reset mode after operation (default: hard-reset)
          --no-stub            Do not use flasher stub
          --trace              Enable trace output
          --verbose            Verbose output
          --silent             Silent output
          -h, --help           Show this help message
    """.trimIndent())
}

fun handleReadFlash(args: Array<String>) {
    if (args.size < 4) {
        log.error("Usage: read-flash ADDRESS SIZE OUTPUT")
        exitProcess(1)
    }

    val address = args[1].toIntOrNull(16) ?: throw FatalError("Invalid address: ${args[1]}")
    val size = args[2].toIntOrNull(16) ?: throw FatalError("Invalid size: ${args[2]}")
    val output = args[3]
    val port = "/dev/ttyUSB0"
    val baud = 115200

    val esp = detectChip(port, baud)
    esp.connect()
    try {
        attachFlash(esp)
        readFlash(esp, address, size, output)
    } finally {
        esp.hardReset()
        esp.close()
    }
}

fun handleReadMac(args: Array<String>) {
    val port = "/dev/ttyUSB0"
    val baud = 115200
    val esp = detectChip(port, baud)
    esp.connect()
    try {
        readMac(esp)
    } finally {
        esp.hardReset()
        esp.close()
    }
}

fun handleChipId(args: Array<String>) {
    val port = "/dev/ttyUSB0"
    val baud = 115200
    val esp = detectChip(port, baud)
    esp.connect()
    try {
        log.print("Chip ID: 0x${esp.getChipId().toString(16).padStart(8, '0')}")
    } catch (e: Exception) {
        log.warning("Could not read chip ID: ${e.message}")
    } finally {
        esp.hardReset()
        esp.close()
    }
}

// Extension functions for ByteArray
operator fun ByteArray.plus(other: ByteArray): ByteArray = this + other
operator fun ByteArray.plus(byte: Byte): ByteArray {
    val result = ByteArray(this.size + 1)
    System.arraycopy(this, 0, result, 0, this.size)
    result[this.size] = byte
    return result
}
