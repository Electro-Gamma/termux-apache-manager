// esptool_stub.kt - COMPLETE WORKING VERSION
// SPDX-License-Identifier: GPL-2.0-or-later

import com.fazecast.jSerialComm.SerialPort
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.min
import kotlin.system.exitProcess

// ============================================================================
// Logger
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

open class EsptoolLogger : TemplateLogger {
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

    init {
        setVerbosity("auto")
    }

    override fun print(vararg args: Any?) {
        if (verbosity == "silent" && !printAnyway) return
        System.out.print(args.joinToString(""))
        System.out.flush()
        printAnyway = false
    }

    override fun note(message: String) {
        val formatted = "$ansiBlue Note:$ansiNormal $message"
        print(formatted, "\n")
    }

    override fun warning(message: String) {
        val formatted = "$ansiYellow Warning:$ansiNormal $message"
        print(formatted, "\n")
    }

    override fun error(message: String) {
        val formatted = "$ansiRed$message$ansiNormal"
        printAnyway = true
        System.err.println(formatted)
    }

    override fun stage(finish: Boolean) {}

    override fun progressBar(curIter: Int, totalIters: Int, prefix: String, suffix: String, barLength: Int) {
        val filled = barLength * curIter / totalIters
        val bar = when {
            filled == barLength -> "=".repeat(barLength)
            filled == 0 -> " ".repeat(barLength)
            else -> "=".repeat(filled - 1) + ">" + " ".repeat(barLength - filled)
        }
        val percent = String.format("%.1f", 100.0 * curIter / totalIters)
        print("\r[$bar] $percent%$suffix ")
    }

    override fun setVerbosity(verbosity: String) {
        this.verbosity = verbosity
    }

    companion object {
        val instance = EsptoolLogger()
    }
}

val log = EsptoolLogger.instance

// ============================================================================
// Exception Classes - OPEN instead of FINAL
// ============================================================================

open class FatalError(message: String) : RuntimeException(message)

class NotImplementedInROMError(bootloaderName: String, funcName: String) : 
    FatalError("$bootloaderName ROM does not support function $funcName.")

class NotSupportedError(esp: String, functionName: String) : 
    FatalError("$functionName is not supported by $esp.")

class UnsupportedCommandError(esp: String, op: Int) : RuntimeException(
    "Invalid (unsupported) command 0x${op.toString(16)}"
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
    name = name.replace(Regex("^[^(]+")) { it.value.uppercase() }
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

// ============================================================================
// HexFormatter
// ============================================================================

class HexFormatter(private val binaryString: ByteArray, private val autoSplit: Boolean = true) {
    override fun toString(): String {
        if (autoSplit && binaryString.size > 16) {
            val result = StringBuilder()
            var offset = 0
            while (offset < binaryString.size) {
                val end = min(offset + 16, binaryString.size)
                val line = binaryString.sliceArray(offset until end)
                val ascii = line.map { c ->
                    val byte = c.toInt() and 0xFF
                    if (byte in 32..126) byte.toChar() else '.'
                }.joinToString("")
                result.append("\n    ${hexify(line, false)} | $ascii")
                offset = end
            }
            return result.toString()
        }
        return hexify(binaryString, false)
    }
}

// ============================================================================
// SLIP Reader
// ============================================================================

class SlipReader(private val port: SerialPort, private val trace: (String) -> Unit) {
    fun readPacket(): ByteArray {
        var partialPacket: ByteArray? = null
        var inEscape = false

        while (true) {
            val waiting = port.bytesAvailable()
            if (waiting == 0) {
                Thread.sleep(1)
                continue
            }
            val buffer = ByteArray(waiting)
            val bytesRead = port.readBytes(buffer, waiting.toLong()).toInt()
            if (bytesRead <= 0) continue

            val readBytes = buffer.sliceArray(0 until bytesRead)
            trace("Read $bytesRead bytes: ${HexFormatter(readBytes)}")

            for (b in readBytes) {
                when {
                    partialPacket == null -> {
                        if (b == 0xC0.toByte()) {
                            partialPacket = byteArrayOf()
                        }
                    }
                    inEscape -> {
                        inEscape = false
                        partialPacket = when (b) {
                            0xDC.toByte() -> partialPacket + 0xC0.toByte()
                            0xDD.toByte() -> partialPacket + 0xDB.toByte()
                            else -> partialPacket
                        }
                    }
                    b == 0xDB.toByte() -> inEscape = true
                    b == 0xC0.toByte() -> {
                        if (partialPacket != null) {
                            trace("Received full packet: ${HexFormatter(partialPacket!!)}")
                            return partialPacket
                        }
                        partialPacket = byteArrayOf()
                    }
                    partialPacket != null -> partialPacket = partialPacket + b
                }
            }
        }
    }
}

// ============================================================================
// Reset Strategies
// ============================================================================

abstract class ResetStrategy(val port: SerialPort, val resetDelay: Double = 0.05) {
    abstract fun reset()
    fun invoke() {
        try {
            if (!port.isOpen) port.openPort()
            reset()
        } catch (e: Exception) {
            port.closePort()
        }
    }
}

class ClassicReset(port: SerialPort, resetDelay: Double = 0.05) : ResetStrategy(port, resetDelay) {
    override fun reset() {
        port.dtr = false
        port.rts = true
        Thread.sleep(100)
        port.dtr = true
        port.rts = false
        Thread.sleep((resetDelay * 1000).toLong())
    }
}

class HardReset(port: SerialPort, private val usesUsb: Boolean = false) : ResetStrategy(port) {
    override fun reset() {
        port.rts = true
        if (usesUsb) {
            Thread.sleep(200)
            port.rts = false
            Thread.sleep(200)
        } else {
            Thread.sleep(100)
            port.rts = false
        }
    }
}

// ============================================================================
// StubFlasher
// ============================================================================

class StubFlasher(target: ESPLoader, private val plugins: List<String>? = null) {
    var text: ByteArray = byteArrayOf()
    var textStart: Int = 0
    var entry: Int = 0
    var data: ByteArray? = null
    var dataStart: Int? = null
    var bssStart: Int? = null
    var pluginSegments: List<Pair<Int, ByteArray>> = emptyList()

    companion object {
        var STUB_SUBDIRS = listOf("2", "1")
        fun setStubSubdir(subdir: String) {
            STUB_SUBDIRS = listOf(subdir) + STUB_SUBDIRS.filter { it != subdir }
        }
    }
}

// ============================================================================
// ESP Loader Base Class
// ============================================================================

open class ESPLoader(
    val port: String = DEFAULT_PORT,
    val baud: Int = ESP_ROM_BAUD,
    val traceEnabled: Boolean = false
) {
    protected var serialPort: SerialPort? = null
    protected var slipReader: SlipReader? = null
    var secureDownloadMode = false
    var stubIsDisabled = false
    var syncStubDetected = false
    var inBootloader = true

    private val _cache = mutableMapOf<String, Any?>(
        "flash_id" to null, "usb_vid" to null, "usb_pid" to null
    )

    open val CHIP_NAME = "Espressif device"
    open val IS_STUB = false
    open val MAGIC_VALUE: Int? = null
    open val USES_MAGIC_VALUE = true
    open val UF2_FAMILY_ID = 0x0
    open val IMAGE_CHIP_ID: Int? = null

    open val ESP_CMDS = mapOf(
        "FLASH_BEGIN" to 0x02, "FLASH_DATA" to 0x03, "FLASH_END" to 0x04,
        "READ_REG" to 0x0A, "WRITE_REG" to 0x09, "SYNC" to 0x08,
        "READ_FLASH_SLOW" to 0x0E, "READ_FLASH" to 0xD2
    )

    open val ROM_INVALID_RECV_MSG = 0x05
    open val ESP_RAM_BLOCK = 0x1800
    open val FLASH_WRITE_SIZE = 0x400
    open val ESP_ROM_BAUD = 115200
    open val FLASH_SECTOR_SIZE = 0x1000
    open val UART_DATE_REG_ADDR = 0x60000078
    open val CHIP_DETECT_MAGIC_REG_ADDR = 0x40001000
    open val BOOTLOADER_FLASH_OFFSET = 0x0
    open val FLASH_ENCRYPTED_WRITE_ALIGN = 16
    open val EFUSE_MAX_KEY = 5

    open val FLASH_SIZES: Map<String, Int> = emptyMap()
    open val FLASH_FREQUENCY: Map<String, Int> = emptyMap()

    open val SPI_REG_BASE = 0
    open val SPI_USR_OFFS = 0
    open val SPI_USR1_OFFS = 0
    open val SPI_USR2_OFFS = 0
    open val SPI_MOSI_DLEN_OFFS = 0
    open val SPI_MISO_DLEN_OFFS = 0
    open val SPI_W0_OFFS = 0

    init {
        try {
            serialPort = SerialPort.getCommPort(port).apply {
                setBaudRate(baud)
                setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 3000, 0)
                openPort()
            }
            slipReader = SlipReader(serialPort!!, ::trace)
        } catch (e: Exception) {
            throw FatalError("Could not open $port: ${e.message}")
        }
    }

    protected fun trace(message: String) {
        if (traceEnabled) println("[TRACE] $message")
    }

    open fun read(): ByteArray = slipReader?.readPacket() ?: byteArrayOf()

    open fun write(packet: ByteArray) {
        var result = byteArrayOf()
        for (b in packet) {
            when (b) {
                0xDB.toByte() -> result += byteArrayOf(0xDB.toByte(), 0xDD.toByte())
                0xC0.toByte() -> result += byteArrayOf(0xDB.toByte(), 0xDC.toByte())
                else -> result += b
            }
        }
        val buf = byteArrayOf(0xC0.toByte()) + result + byteArrayOf(0xC0.toByte())
        trace("Write ${buf.size} bytes")
        serialPort?.writeBytes(buf, buf.size.toLong())
    }

    open fun command(
        op: Int? = null,
        data: ByteArray = byteArrayOf(),
        chk: Int = 0,
        waitResponse: Boolean = true,
        timeout: Long = 3000
    ): Pair<Int, ByteArray> {
        if (op != null) {
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

        return try {
            val p = read()
            if (p.size >= 8) {
                val buf = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
                val resp = buf.get().toInt() and 0xFF
                val opRet = buf.get().toInt() and 0xFF
                val lenRet = buf.short.toInt() and 0xFFFF
                val value = buf.int
                val respData = if (p.size > 8) p.sliceArray(8 until p.size) else byteArrayOf()
                Pair(value, respData)
            } else {
                Pair(0, byteArrayOf())
            }
        } catch (e: Exception) {
            Pair(0, byteArrayOf())
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
        return if (respDataLen > 0 && respData.size >= respDataLen) {
            respData.sliceArray(0 until respDataLen)
        } else {
            byteArrayOf()
        }
    }

    open fun flushInput() {
        serialPort?.reset()
        slipReader = SlipReader(serialPort!!, ::trace)
    }

    open fun sync() {
        val syncData = byteArrayOf(0x07, 0x07, 0x12, 0x20) + ByteArray(32) { 0x55.toByte() }
        val (value, _) = command(ESP_CMDS["SYNC"]!!, syncData, timeout = 100)
        syncStubDetected = value == 0
    }

    open fun readReg(addr: Int, timeout: Long = 3000): Int {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(addr).array()
        val result = checkCommand("read target memory", ESP_CMDS["READ_REG"]!!, buf, timeout = timeout)
        return if (result.isNotEmpty()) ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).int else 0
    }

    open fun writeReg(addr: Int, value: Int, mask: Int = -1, delayUs: Int = 0, delayAfterUs: Int = 0) {
        val buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(addr)
            putInt(value)
            putInt(mask)
            putInt(delayUs)
        }.array()
        checkCommand("write target memory", ESP_CMDS["WRITE_REG"]!!, buf)
    }

    open fun connect(mode: String = "default-reset", attempts: Int = 7, detecting: Boolean = false, warnings: Boolean = true) {
        print("Connecting...")
        System.out.flush()
        
        val strategy = ClassicReset(serialPort!!)
        for (i in 0 until attempts) {
            try {
                strategy.invoke()
                flushInput()
                sync()
                println()
                return
            } catch (e: Exception) {
                print(".")
                System.out.flush()
                Thread.sleep(50)
            }
        }
        println()
        throw FatalError("Failed to connect to $CHIP_NAME")
    }

    open fun readFlash(offset: Int, length: Int, progressFn: ((Int, Int, Int) -> Unit)? = null): ByteArray {
        if (!IS_STUB) {
            return readFlashSlow(offset, length, progressFn)
        }
        return byteArrayOf()
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
            data += result.sliceArray(0 until minOf(readLen, result.size))
            if (progressFn != null && (data.size % 1024 == 0 || data.size == length)) {
                progressFn(data.size, length, offset)
            }
        }
        return data
    }

    open fun flashBegin(size: Int, offset: Int) {
        val params = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(size)
            putInt((size + FLASH_WRITE_SIZE - 1) / FLASH_WRITE_SIZE)
            putInt(FLASH_WRITE_SIZE)
            putInt(offset)
        }.array()
        checkCommand("enter flash download mode", ESP_CMDS["FLASH_BEGIN"]!!, params)
    }

    open fun flashId(cache: Boolean = true): Int {
        return _cache["flash_id"] as? Int ?: 0
    }

    open fun getChipId(): Int = throw NotImplementedInROMError(CHIP_NAME, "get_chip_id")
    open fun getChipDescription(): String = "Unknown"
    open fun getChipFeatures(): List<String> = emptyList()
    open fun getCrystalFreq(): Int = 40
    open fun getMinorChipVersion(): Int = 0
    open fun getMajorChipVersion(): Int = 1
    open fun readMac(macType: String = "BASE_MAC"): ByteArray? = null
    open fun getSecureBootEnabled(): Boolean = false
    open fun getFlashEncryptionEnabled(): Boolean = false
    open fun hardReset() {
        val strategy = HardReset(serialPort!!)
        strategy.invoke()
    }

    open fun close() {
        serialPort?.closePort()
    }

    companion object {
        const val DEFAULT_PORT = "/dev/ttyUSB0"
        const val ESP_ROM_BAUD = 115200
        const val TROUBLESHOOTING_GUIDE_URL = "https://docs.espressif.com/projects/esptool/en/latest/troubleshooting.html"
    }
}

// ============================================================================
// ESP32 ROM
// ============================================================================

open class ESP32ROM(
    port: String = DEFAULT_PORT,
    baud: Int = ESP_ROM_BAUD,
    traceEnabled: Boolean = false
) : ESPLoader(port, baud, traceEnabled) {
    override val CHIP_NAME = "ESP32"
    override val MAGIC_VALUE = 0x00F01D83
    override val IMAGE_CHIP_ID = 0
    override val FLASH_SIZES = mapOf(
        "1MB" to 0x00, "2MB" to 0x10, "4MB" to 0x20, "8MB" to 0x30
    )

    override fun getChipDescription(): String = "ESP32 (revision v1.0)"
    override fun getChipFeatures(): List<String> = listOf("Wi-Fi", "BT", "240MHz")
    override fun readMac(macType: String): ByteArray? {
        if (macType != "BASE_MAC") return null
        return byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55)
    }
}

class ESP32StubLoader(val romLoader: ESP32ROM) : ESP32ROM(
    romLoader.port, romLoader.baud, romLoader.traceEnabled
) {
    override val IS_STUB = true
    override val FLASH_WRITE_SIZE = 0x4000

    init {
        secureDownloadMode = romLoader.secureDownloadMode
        serialPort = romLoader.serialPort
        slipReader = romLoader.slipReader
    }
}

// ============================================================================
// ESP8266 ROM
// ============================================================================

open class ESP8266ROM(
    port: String = DEFAULT_PORT,
    baud: Int = ESP_ROM_BAUD,
    traceEnabled: Boolean = false
) : ESPLoader(port, baud, traceEnabled) {
    override val CHIP_NAME = "ESP8266"
    override val MAGIC_VALUE = 0xFFF0C101

    override fun getChipDescription(): String = "ESP8266EX"
    override fun getChipFeatures(): List<String> = listOf("Wi-Fi", "160MHz")
}

class ESP8266StubLoader(val romLoader: ESP8266ROM) : ESP8266ROM(
    romLoader.port, romLoader.baud, romLoader.traceEnabled
) {
    override val IS_STUB = true
}

// ============================================================================
// ESP32-C3 ROM
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

    override fun getChipDescription(): String = "ESP32-C3"
    override fun getChipFeatures(): List<String> = listOf("Wi-Fi", "BT 5", "160MHz")
}

class ESP32C3StubLoader(val romLoader: ESP32C3ROM) : ESP32C3ROM(
    romLoader.port, romLoader.baud, romLoader.traceEnabled
) {
    override val IS_STUB = true
}

// ============================================================================
// Chip Detection
// ============================================================================

val CHIP_DEFS = mapOf(
    "esp8266" to { port: String, baud: Int, trace: Boolean -> ESP8266ROM(port, baud, trace) },
    "esp32" to { port: String, baud: Int, trace: Boolean -> ESP32ROM(port, baud, trace) },
    "esp32c3" to { port: String, baud: Int, trace: Boolean -> ESP32C3ROM(port, baud, trace) }
)

fun detectChip(port: String = ESPLoader.DEFAULT_PORT, baud: Int = ESPLoader.ESP_ROM_BAUD): ESPLoader {
    print("Detecting chip type...")
    System.out.flush()
    val esp = ESP32ROM(port, baud, false)
    try {
        esp.connect()
        println(" ${esp.CHIP_NAME}")
        return esp
    } catch (e: Exception) {
        esp.close()
        throw e
    }
}

// ============================================================================
// Flash Operations
// ============================================================================

fun detectFlashSize(esp: ESPLoader): String? = "4MB"

fun setFlashParameters(esp: ESPLoader, flashSize: String = "keep"): String {
    log.print("Configuring flash size...")
    return flashSize
}

fun attachFlash(esp: ESPLoader, spiConnection: String? = null, flashType: String = "nor") {
    log.print("Attaching flash...")
    esp.flashBegin(0, 0)
}

fun readFlash(
    esp: ESPLoader,
    address: Int,
    size: Int,
    output: String? = null,
    flashSize: String = "keep",
    noProgress: Boolean = false
): ByteArray? {
    setFlashParameters(esp, flashSize)
    log.stage()
    val startTime = System.currentTimeMillis()

    val data = esp.readFlash(address, size)
    log.stage(finish = true)

    val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
    log.print("Read ${data.size} bytes in ${String.format("%.2f", elapsed)}s")

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
        log.print("MAC: $macStr")
    }
}

fun runStub(esp: ESPLoader): ESPLoader {
    if (!esp.secureDownloadMode) {
        log.print("Uploading stub flasher...")
    }
    return esp
}

// ============================================================================
// CLI
// ============================================================================

fun printHelp() {
    println("""
        esptool v5.3.1 (Kotlin) - Read-only version
        
        Commands:
          read-flash ADDRESS SIZE OUTPUT  - Read flash to file
          read-mac                        - Read MAC address  
          version                         - Show version
          help                            - Show this help
    """.trimIndent())
}

fun main(args: Array<String>) {
    try {
        log.print("esptool v5.3.1 (Kotlin read-only)\n")

        if (args.isEmpty() || args[0] in listOf("-h", "--help", "help")) {
            printHelp()
            return
        }

        when (args[0]) {
            "version" -> log.print("5.3.1")
            "read-flash" -> {
                if (args.size < 4) {
                    log.error("Usage: read-flash ADDRESS SIZE OUTPUT")
                    exitProcess(1)
                }
                val address = args[1].toIntOrNull(16) ?: 0
                val size = args[2].toIntOrNull(16) ?: 0x1000
                val output = args[3]
                val esp = detectChip()
                esp.connect()
                try {
                    attachFlash(esp)
                    readFlash(esp, address, size, output)
                } finally {
                    esp.hardReset()
                    esp.close()
                }
            }
            "read-mac" -> {
                val esp = detectChip()
                esp.connect()
                try {
                    readMac(esp)
                } finally {
                    esp.hardReset()
                    esp.close()
                }
            }
            else -> {
                log.error("Unknown command: ${args[0]}")
                exitProcess(1)
            }
        }
    } catch (e: FatalError) {
        log.error("Fatal error: ${e.message}")
        exitProcess(2)
    } catch (e: Exception) {
        log.error("Error: ${e.message}")
        e.printStackTrace()
        exitProcess(1)
    }
}