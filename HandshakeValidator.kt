/**
 * HandshakeValidator.kt
 *
 * Checks whether a Wi-Fi capture (.pcap, classic libpcap format) contains a
 * usable WPA/WPA2 4-way handshake or PMKID — the same thing aircrack-ng
 * reports as "1 handshake" / airodump-ng shows as "WPA handshake: <bssid>".
 *
 * The detection logic (message classification, replay-counter bookkeeping,
 * inter-frame/four-way timeouts, PMKID extraction) is a line-by-line port of
 * aircrack-ng's own C implementation in src/aircrack-ng/aircrack-ng.c
 * (function packet_reader_process_packet, aircrack-ng 1.7), so results match
 * what aircrack-ng itself would report. It only *detects* a handshake — it
 * does not attempt to crack, verify the MIC cryptographically, or export a
 * crackable file; add that separately if you need it.
 *
 * No third-party dependencies — plain JVM/Kotlin stdlib only.
 *
 * Build & run:
 *   kotlinc HandshakeValidator.kt -include-runtime -d handshake-validator.jar
 *   java -jar handshake-validator.jar capture1.pcap [capture2.pcap ...]
 */

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

// =====================================================================
// Public result types
// =====================================================================

/**
 * Handshake material observed for one (access point, station) pair.
 *
 * [isValidHandshake] is the yes/no answer: enough of the 4-way handshake
 * (or a PMKID) was captured to attempt an offline dictionary/brute-force
 * attack against the passphrase.
 */
data class HandshakeFinding(
    val bssid: String,
    val essid: String?,
    val station: String,
    /** Which of EAPOL messages 1-4 were observed for this pair, e.g. {1, 2, 3}. */
    val messagesSeen: Set<Int>,
    val hasAnonce: Boolean,
    val hasSnonce: Boolean,
    val hasMic: Boolean,
    val hasPmkid: Boolean,
    /** Key descriptor version (1 = WPA/TKIP+MD5, 2 = WPA2/AES+SHA1, ...), 0 if unknown. */
    val keyVersion: Int,
    val isFourWayComplete: Boolean,
    val isValidHandshake: Boolean
)

class InvalidPcapException(message: String) : Exception(message)

// =====================================================================
// Entry point / public API
// =====================================================================

object HandshakeValidator {

    /**
     * Parses [file] and returns one [HandshakeFinding] for every
     * (access point, station) pair that produced at least one EAPOL-Key
     * frame — including partial/incomplete attempts, so callers can see
     * *why* a capture was rejected, not just that it was.
     *
     * Throws [InvalidPcapException] if the file isn't a classic pcap
     * capture (e.g. pcapng, or a corrupted/truncated header) or uses a
     * link-layer type this tool doesn't understand.
     */
    fun analyze(file: File): List<HandshakeFinding> {
        val findings = mutableListOf<HandshakeFinding>()
        BufferedInputStream(FileInputStream(file)).use { input ->
            val (littleEndian, nanoSeconds, linkType) = readGlobalHeader(input)
            if (!isSupportedLinkType(linkType)) {
                throw InvalidPcapException(
                    "Unsupported link-layer type $linkType. This tool expects a raw or " +
                        "radiotap/prism/PPI-wrapped 802.11 capture, such as one produced " +
                        "by airodump-ng or a monitor-mode adapter."
                )
            }

            val aps = LinkedHashMap<String, ApState>()

            while (true) {
                val record = readPacketRecord(input, littleEndian) ?: break
                val nowUs = if (nanoSeconds) {
                    record.tsSec * 1_000_000L + record.tsSubSec / 1000L
                } else {
                    record.tsSec * 1_000_000L + record.tsSubSec
                }
                val frame = stripLinkLayerHeader(record.data, linkType) ?: continue
                processFrame(frame, nowUs, aps)
            }

            for ((bssid, ap) in aps) {
                for ((station, st) in ap.stations) {
                    if (st.found == 0) continue // no EAPOL-Key frame ever matched for this pair
                    val messages = buildSet {
                        if ((st.found and M1_BIT) != 0) add(1)
                        if ((st.found and M2_BIT) != 0) add(2)
                        if ((st.found and M3_BIT) != 0) add(3)
                        if ((st.found and M4_BIT) != 0) add(4)
                    }
                    findings += HandshakeFinding(
                        bssid = bssid,
                        essid = ap.essid,
                        station = station,
                        messagesSeen = messages,
                        hasAnonce = (st.state and 1) != 0,
                        hasSnonce = (st.state and 2) != 0,
                        hasMic = (st.state and 4) != 0,
                        hasPmkid = st.confirmedPmkid,
                        keyVersion = st.keyVersion,
                        isFourWayComplete = st.confirmedFourWay,
                        isValidHandshake = st.confirmedFourWay || st.confirmedPmkid
                    )
                }
            }
        }
        return findings
    }

    /** Convenience: does this capture contain *any* crackable handshake material at all? */
    fun hasValidHandshake(file: File): Boolean = analyze(file).any { it.isValidHandshake }
}

// =====================================================================
// Internal mutable state (mirrors aircrack-ng's struct WPA_hdsk)
// =====================================================================

private class ApState {
    var essid: String? = null
    val stations = LinkedHashMap<String, StationState>()
}

private class StationState {
    /** bit0 = ANonce captured, bit1 = SNonce captured, bit2 = MIC/EAPOL body captured. */
    var state: Int = 0
    /** bit1..bit4 = message 1..4 observed. */
    var found: Int = 0
    var replayCounter: Long = 0
    var pmkid: ByteArray? = null
    var keyVersion: Int = 0
    var handshakeStartUs: Long = 0
    var lastFrameUs: Long = 0

    // Latched results: once a valid handshake condition is met, it stays true
    // even if a later, unrelated EAPOL frame for the same pair resets `state`
    // (e.g. a fresh, incomplete reassociation attempt hours later). This
    // mirrors aircrack-ng copying st_cur->wpa into ap_cur->wpa on success.
    var confirmedFourWay: Boolean = false
    var confirmedPmkid: Boolean = false

    fun reset() {
        state = 0
        replayCounter = 0
        handshakeStartUs = 0
        lastFrameUs = 0
        // `found`, pmkid and the confirmed_* latches are deliberately left alone.
    }
}

private const val FOURWAY_TIMEOUT_US = 5_000_000L  // 5s, aircrack-ng's eapol_max_fourway_timeout
private const val INTERFRAME_TIMEOUT_US = 1_000_000L // 1s, aircrack-ng's eapol_interframe_timeout

// NOTE: written as plain literals (not `1 shl n`) because Kotlin's `const val`
// requires a compile-time-constant initializer, and infix functions like
// `shl`/`and`/`or` don't qualify even when both operands are literals.
private const val M1_BIT = 0x02 // bit 1
private const val M2_BIT = 0x04 // bit 2
private const val M3_BIT = 0x08 // bit 3
private const val M4_BIT = 0x10 // bit 4

// =====================================================================
// 802.11 / EAPOL constants (from include/aircrack-ng/third-party/ieee80211.h)
// =====================================================================

private const val FC0_TYPE_MASK = 0x0c
private const val FC0_TYPE_CTL = 0x04
private const val FC0_TYPE_DATA = 0x08
private const val FC0_SUBTYPE_ASSOC_REQ = 0x00
private const val FC0_SUBTYPE_PROBE_RESP = 0x50
private const val FC0_SUBTYPE_BEACON = 0x80

private const val FC1_DIR_MASK = 0x03
private const val FC1_DIR_NODS = 0x00
private const val FC1_DIR_TODS = 0x01
private const val FC1_DIR_FROMDS = 0x02
private const val FC1_DIR_DSTODS = 0x03

private const val IEEE80211_ELEMID_VENDOR = 221

// pcap DLT_* link-layer type numbers
private const val LINKTYPE_IEEE802_11 = 105
private const val LINKTYPE_PRISM_HEADER = 119
private const val LINKTYPE_RADIOTAP_HDR = 127
private const val LINKTYPE_PPI_HDR = 192

private fun isSupportedLinkType(t: Int) =
    t == LINKTYPE_IEEE802_11 || t == LINKTYPE_PRISM_HEADER ||
        t == LINKTYPE_RADIOTAP_HDR || t == LINKTYPE_PPI_HDR

// =====================================================================
// Byte-level helpers
// =====================================================================

private fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

private fun ByteArray.u16le(off: Int): Int = u8(off) or (u8(off + 1) shl 8)

private fun ByteArray.u32le(off: Int): Long =
    (u8(off).toLong()) or (u8(off + 1).toLong() shl 8) or
        (u8(off + 2).toLong() shl 16) or (u8(off + 3).toLong() shl 24)

private fun ByteArray.u64be(off: Int): Long {
    var v = 0L
    for (i in 0 until 8) v = (v shl 8) or u8(off + i).toLong()
    return v
}

private fun ByteArray.isAllZero(off: Int, len: Int): Boolean {
    for (i in 0 until len) if (this[off + i].toInt() != 0) return false
    return true
}

private fun ByteArray.macString(off: Int): String {
    val sb = StringBuilder(17)
    for (i in 0 until 6) {
        if (i > 0) sb.append(':')
        val h = Integer.toHexString(u8(off + i))
        if (h.length == 1) sb.append('0')
        sb.append(h)
    }
    return sb.toString()
}

private const val BROADCAST_MAC = "ff:ff:ff:ff:ff:ff"

// =====================================================================
// pcap file reading
// =====================================================================

private data class GlobalHeaderInfo(val littleEndian: Boolean, val nanoSeconds: Boolean, val linkType: Int)

private fun readGlobalHeader(input: InputStream): GlobalHeaderInfo {
    val hdr = readFullyOrThrow(input, 24, "File is too short to be a pcap capture.")
    // Read the first 4 bytes as little-endian regardless of the file's actual
    // byte order: for a genuinely little-endian file this reproduces the
    // magic constant directly; for a big-endian file it reproduces the
    // well-known *byte-swapped* constant instead. Either way we can identify
    // the file (and its true endianness) from a fixed set of four expected
    // values, with no chicken-and-egg problem.
    val magic = hdr.u32le(0)
    if (magic == 0x0a0d0d0aL) {
        // pcapng's Section Header Block type is the literal byte sequence
        // 0A 0D 0D 0A on disk, which reads back as this same value regardless
        // of which endianness u32le() assumes - so this check is unambiguous.
        throw InvalidPcapException(
            "This is a pcapng capture, not classic pcap. Convert it first, e.g. " +
                "`tshark -F pcap -r input.pcapng -w output.pcap` or " +
                "`editcap -F pcap input.pcapng output.pcap`."
        )
    }
    val (littleEndian, nanoSeconds) = when (magic) {
        0xa1b2c3d4L -> true to false
        0xa1b23c4dL -> true to true
        0xd4c3b2a1L -> false to false
        0x4d3cb2a1L -> false to true
        else -> throw InvalidPcapException("Not a recognized pcap file (unexpected magic number).")
    }
    val linkType = if (littleEndian) hdr.u32le(20).toInt() else beU32(hdr, 20).toInt()
    return GlobalHeaderInfo(littleEndian, nanoSeconds, linkType)
}

private class PacketRecord(val tsSec: Long, val tsSubSec: Long, val data: ByteArray)

private const val MAX_REASONABLE_CAPLEN = 4_000_000 // guards against a corrupted length field

private fun readPacketRecord(input: InputStream, littleEndian: Boolean): PacketRecord? {
    val hdr = readFully(input, 16) ?: return null
    val tsSec: Long
    val tsSubSec: Long
    val capLen: Long
    if (littleEndian) {
        tsSec = hdr.u32le(0)
        tsSubSec = hdr.u32le(4)
        capLen = hdr.u32le(8)
    } else {
        tsSec = beU32(hdr, 0)
        tsSubSec = beU32(hdr, 4)
        capLen = beU32(hdr, 8)
    }
    if (capLen <= 0 || capLen > MAX_REASONABLE_CAPLEN) {
        // Corrupted length field, or (more likely) end of a truncated capture.
        return null
    }
    val data = readFully(input, capLen.toInt()) ?: return null
    return PacketRecord(tsSec, tsSubSec, data)
}

private fun beU32(b: ByteArray, off: Int): Long =
    (b.u8(off).toLong() shl 24) or (b.u8(off + 1).toLong() shl 16) or
        (b.u8(off + 2).toLong() shl 8) or b.u8(off + 3).toLong()

/** Reads exactly [n] bytes, or returns null on a clean EOF (no bytes read yet). */
private fun readFully(input: InputStream, n: Int): ByteArray? {
    val buf = ByteArray(n)
    var got = 0
    while (got < n) {
        val r = input.read(buf, got, n - got)
        if (r < 0) return null // clean EOF, or a truncated final record: stop parsing either way
        got += r
    }
    return buf
}

private fun readFullyOrThrow(input: InputStream, n: Int, errorMessage: String): ByteArray {
    return readFully(input, n) ?: throw InvalidPcapException(errorMessage)
}

// =====================================================================
// Link-layer header stripping (radiotap/prism/PPI length fields are
// always little-endian on the wire, independent of the pcap file's
// own byte order)
// =====================================================================

private fun stripLinkLayerHeader(frame: ByteArray, linkType: Int): ByteArray? {
    return when (linkType) {
        LINKTYPE_IEEE802_11 -> frame
        LINKTYPE_RADIOTAP_HDR -> {
            if (frame.size < 4) return null
            val n = frame.u16le(2)
            if (n <= 0 || n >= frame.size) return null
            frame.copyOfRange(n, frame.size)
        }
        LINKTYPE_PRISM_HEADER -> {
            if (frame.size < 8) return null
            val n = if (frame.u8(7) == 0x40) 64 else frame.u32le(4).toInt()
            if (n < 8 || n >= frame.size) return null
            frame.copyOfRange(n, frame.size)
        }
        LINKTYPE_PPI_HDR -> {
            if (frame.size < 10) return null
            var n = frame.u16le(2)
            if (n <= 0 || n >= frame.size) return null
            if (n == 24 && frame.u16le(8) == 2) n = 32
            if (n >= frame.size) return null
            frame.copyOfRange(n, frame.size)
        }
        else -> null
    }
}

// =====================================================================
// Core 802.11 frame processing
// =====================================================================

private fun processFrame(h: ByteArray, nowUs: Long, aps: MutableMap<String, ApState>) {
    if (h.size < 24) return

    val fc0 = h.u8(0)
    val fc1 = h.u8(1)
    val frameType = fc0 and FC0_TYPE_MASK
    if (frameType == FC0_TYPE_CTL) return

    val dir = fc1 and FC1_DIR_MASK
    val bssid = when (dir) {
        FC1_DIR_NODS -> h.macString(16)
        FC1_DIR_TODS -> h.macString(4)
        else -> h.macString(10) // FROMDS or DSTODS
    }
    if (bssid == BROADCAST_MAC) return

    // Beacon / probe response / association request -> opportunistically grab the ESSID.
    if (fc0 == FC0_SUBTYPE_BEACON || fc0 == FC0_SUBTYPE_PROBE_RESP) {
        extractEssid(h, 36, aps.getOrPut(bssid) { ApState() })
    } else if (fc0 == FC0_SUBTYPE_ASSOC_REQ) {
        extractEssid(h, 28, aps.getOrPut(bssid) { ApState() })
    }

    if (frameType != FC0_TYPE_DATA) return

    val stmac: String = when (dir) {
        FC1_DIR_NODS, FC1_DIR_TODS -> h.macString(10)
        FC1_DIR_FROMDS -> {
            if (h.u8(4) % 2 != 0) return // multicast/broadcast transmitter -> not a real station
            h.macString(4)
        }
        else -> return // DSTODS (WDS link) - no station to track
    }

    var z = if (dir != FC1_DIR_DSTODS) 24 else 30
    if ((fc0 and 0x80) == 0x80) z += 2 // QoS data variant: extra 2-byte QoS Control field

    if (z + 16 > h.size) return

    // LLC/SNAP header check. If this isn't a standard SNAP header the frame body
    // is encrypted (WEP or an already-encrypted WPA data frame) - not part of
    // the (unencrypted) 4-way handshake we're looking for.
    if (!(h.u8(z) == h.u8(z + 1) && h.u8(z + 2) == 0x03)) return
    z += 6 // DSAP + SSAP + Control + 3-byte OUI -> now pointing at the 2-byte EtherType

    if (z + 20 >= h.size) return
    if (h.u8(z) != 0x88 || h.u8(z + 1) != 0x8e) return // EtherType != EAPOL (0x888E)
    z += 2 // now pointing at the start of the EAPOL frame (version byte)

    // EAPOL type must be Key (3); descriptor must be WPA (0xFE) or RSN (0x02).
    if (h.u8(z + 1) != 0x03 || (h.u8(z + 4) != 0xFE && h.u8(z + 4) != 0x02)) return

    if (z + 99 >= h.size) return // not enough bytes for the fixed EAPOL-Key header

    val ap = aps.getOrPut(bssid) { ApState() }
    val st = ap.stations.getOrPut(stmac) { StationState() }
    handleEapolKey(h, z, nowUs, st)

    if (st.state == 7) st.confirmedFourWay = true
    if (st.state > 0 && st.pmkid != null) st.confirmedPmkid = true
}

private fun extractEssid(h: ByteArray, start: Int, ap: ApState) {
    var p = start
    while (p + 2 <= h.size) {
        val elemLen = h.u8(p + 1)
        if (p + 2 + elemLen > h.size) break
        if (h.u8(p) == 0 && elemLen > 0 && h.u8(p + 2) != 0) {
            val n = minOf(elemLen, 32)
            ap.essid = String(h, p + 2, n, Charsets.UTF_8)
        }
        p += 2 + elemLen
    }
}

/**
 * Classifies one EAPOL-Key frame as message 1/2/3/4 of the 4-way handshake
 * (or a PMKID candidate riding in message 1) and updates [st] accordingly.
 * `z` points at the first byte of the EAPOL frame (the version byte).
 *
 * This is a direct translation of the four `if` blocks in aircrack-ng's
 * packet_reader_process_packet(), including its early-return behaviour on
 * stale/mismatched frames.
 */
private fun handleEapolKey(h: ByteArray, z: Int, nowUs: Long, st: StationState) {
    val replayCounter = h.u64be(z + 9)
    val infoLow = h.u8(z + 5)   // holds the Key MIC bit (0x01)
    val infoHigh = h.u8(z + 6)  // holds Pairwise(0x08)/Install(0x40)/Ack(0x80); low 3 bits = key descriptor version
    val pairwise = (infoHigh and 0x08) != 0
    val install = (infoHigh and 0x40) != 0
    val ack = (infoHigh and 0x80) != 0
    val mic = (infoLow and 0x01) != 0

    if (st.handshakeStartUs != 0L && (nowUs - st.handshakeStartUs) > FOURWAY_TIMEOUT_US) {
        st.reset()
    }

    // ---- Message 1: Pairwise, Ack, not Install, no MIC yet (carries ANonce) ----
    if (pairwise && !install && ack && !mic) {
        if (st.handshakeStartUs == 0L) {
            st.handshakeStartUs = nowUs
            st.lastFrameUs = nowUs
        }
        if (nowUs - st.lastFrameUs > INTERFRAME_TIMEOUT_US) {
            st.reset()
            st.handshakeStartUs = nowUs
        }
        st.lastFrameUs = nowUs
        st.state = 1
        st.found = st.found or M1_BIT
        st.replayCounter = replayCounter

        // Optional PMKID KDE inside message 1 (vendor element 221, OUI 00:0f:ac, type 4).
        if (z + 121 <= h.size && h.u8(z + 99) == IEEE80211_ELEMID_VENDOR &&
            h.u8(z + 101) == 0x00 && h.u8(z + 102) == 0x0f && h.u8(z + 103) == 0xac &&
            h.u8(z + 104) == 0x04
        ) {
            if (!h.isAllZero(z + 105, 16)) {
                st.pmkid = h.copyOfRange(z + 105, z + 121)
                st.keyVersion = infoHigh and 7
            }
        }
        return
    }

    // ---- Message 2 or 4: Pairwise, not Install, no Ack, MIC present ----
    if (pairwise && !install && !ack && mic) {
        if (st.handshakeStartUs == 0L) {
            st.handshakeStartUs = nowUs
            st.lastFrameUs = nowUs
        }
        if (nowUs - st.lastFrameUs > INTERFRAME_TIMEOUT_US) {
            st.found = st.found and (M2_BIT or M4_BIT).inv()
            return
        }
        st.lastFrameUs = nowUs

        if (st.state == 0) {
            st.replayCounter = replayCounter
        } else if (st.replayCounter != replayCounter) {
            return // mismatched replay counter: unrelated/stray frame, ignore it
        }

        if (!h.isAllZero(z + 17, 32)) {
            st.state = st.state or 2 // SNonce set
        }

        val keyDataLen = (h.u8(z + 97) shl 8) or h.u8(z + 98)
        st.found = st.found or if (keyDataLen == 0) M4_BIT else M2_BIT

        if ((st.state and 4) != 4) {
            st.state = st.state or 4
            st.keyVersion = infoHigh and 7
        }
        return
    }

    // ---- Message 3: Pairwise, Install, Ack, MIC present, fresh replay counter ----
    if (pairwise && install && ack && mic && st.replayCounter < replayCounter) {
        if (st.handshakeStartUs == 0L) {
            st.handshakeStartUs = nowUs
            st.lastFrameUs = nowUs
        }
        if (nowUs - st.lastFrameUs > INTERFRAME_TIMEOUT_US) {
            st.found = st.found and M3_BIT.inv()
            return
        }
        st.lastFrameUs = nowUs
        st.found = st.found or M3_BIT
        st.replayCounter = replayCounter

        if (!h.isAllZero(z + 17, 32)) {
            st.state = st.state or 1 // ANonce set (from M3, in case M1 was missed)
        }
        if ((st.state and 4) != 4) {
            st.state = st.state or 4
            st.keyVersion = infoHigh and 7
        }
    }
}

// =====================================================================
// CLI
// =====================================================================

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: HandshakeValidator <capture1.pcap> [capture2.pcap ...]")
        return
    }

    for (path in args) {
        val file = File(path)
        println("=".repeat(70))
        println(file.name)
        println("=".repeat(70))

        if (!file.exists()) {
            println("  File not found.\n")
            continue
        }

        val findings = try {
            HandshakeValidator.analyze(file)
        } catch (e: InvalidPcapException) {
            println("  ${e.message}\n")
            continue
        } catch (e: Exception) {
            println("  Failed to parse file: ${e.message}\n")
            continue
        }

        if (findings.isEmpty()) {
            println("  No EAPOL key frames found - no handshake in this capture.\n")
            continue
        }

        val anyValid = findings.any { it.isValidHandshake }
        println(if (anyValid) "  VALID HANDSHAKE FOUND" else "  No complete/crackable handshake found")
        println()

        for (f in findings) {
            val essidPart = f.essid?.let { " (\"$it\")" } ?: ""
            println("  AP ${f.bssid}$essidPart  <->  Station ${f.station}")
            println("    Messages seen : ${f.messagesSeen.sorted().joinToString(", ") { "M$it" }.ifEmpty { "none" }}")
            println("    ANonce/SNonce : ${if (f.hasAnonce) "yes" else "no"} / ${if (f.hasSnonce) "yes" else "no"}   MIC: ${if (f.hasMic) "yes" else "no"}")
            println("    PMKID         : ${if (f.hasPmkid) "present" else "not present"}")
            if (f.keyVersion > 0) println("    Key version   : ${f.keyVersion} (${if (f.keyVersion == 1) "WPA/TKIP" else "WPA2/AES or later"})")
            println("    Result        : ${if (f.isValidHandshake) "USABLE" else "incomplete"}")
            println()
        }
    }
}
