/**
 * HandshakeValidator.kt
 *
 * Checks whether a Wi-Fi capture (classic pcap or pcapng) contains a usable
 * WPA/WPA2 4-way handshake or PMKID — the same thing aircrack-ng reports as
 * "1 handshake" / airodump-ng shows as "WPA handshake: <bssid>" — and can:
 *
 *   - flag WPA3-SAE-only or Enterprise networks as not wordlist-crackable,
 *     by reading the AKM suites out of the AP's RSN information element
 *   - run an offline dictionary attack against a valid handshake, the same
 *     way `aircrack-ng -w wordlist.txt capture.pcap` does, optionally with
 *     common case/leetspeak/suffix rule mangling on top of the wordlist
 *   - export handshake material to hashcat's -m 22000 format, for when a
 *     GPU is available and CPU-side PBKDF2 is too slow
 *   - dissect any frame (802.11 or plain Ethernet) Wireshark-style: MACs,
 *     IP/TCP/UDP/HTTP where the payload isn't encrypted, EAPOL message
 *     numbers, beacon SSIDs, etc.
 *
 * The handshake-detection logic (message classification, replay-counter
 * bookkeeping, inter-frame/four-way timeouts, PMKID extraction) and the
 * cracking math (PMK/PTK derivation, MIC and PMKID verification) are both
 * direct ports of aircrack-ng's own C implementation (src/aircrack-ng/
 * aircrack-ng.c and lib/crypto/crypto.c, aircrack-ng 1.7), so results match
 * what aircrack-ng itself would report.
 *
 * Only use this against networks you own or are explicitly authorized to
 * test — cracking a passphrase you're not authorized to recover is illegal
 * in most jurisdictions.
 *
 * No third-party dependencies — plain JVM/Kotlin stdlib + javax.crypto only.
 *
 * Build & run:
 *   kotlinc HandshakeValidator.kt -include-runtime -d handshake-validator.jar
 *   java -jar handshake-validator.jar capture1.pcap [capture2.pcap ...]
 *   java -jar handshake-validator.jar --crack wordlist.txt --rules capture.pcap
 *   java -jar handshake-validator.jar --export-hc22000 out.hc22000 capture.pcap
 *   java -jar handshake-validator.jar --dissect capture.pcap
 */

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

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
    val isValidHandshake: Boolean,
    /** AKM suites from this AP's RSN IE (null if no beacon/probe response was captured for it). */
    val akms: Set<Akm>? = null,
    /** Human-readable security summary, e.g. flags WPA3-SAE-only networks as not wordlist-crackable. */
    val securityNote: String = describeSecurity(akms)
)

class InvalidPcapException(message: String) : Exception(message)

/** Which cryptographic material a password guess matched against. */
enum class CrackMethod { FOUR_WAY_MIC, PMKID }

/**
 * Raw cryptographic material for one (access point, station) pair, sufficient
 * to attempt an offline dictionary attack. Obtained via
 * [HandshakeValidator.extractCrackable] — only pairs whose [HandshakeFinding]
 * had `isValidHandshake == true` produce one of these.
 */
class CrackableHandshake internal constructor(
    val bssid: String,
    val essid: String?,
    val station: String,
    val keyVersion: Int,
    internal val bssidBytes: ByteArray,
    internal val staBytes: ByteArray,
    internal val essidBytes: ByteArray?,
    internal val anonce: ByteArray?,
    internal val snonce: ByteArray?,
    internal val eapolFrame: ByteArray?,
    internal val capturedMic: ByteArray?,
    internal val pmkid: ByteArray?,
    internal val anonceSource: Int = 0,
    internal val snonceSource: Int = 0,
    internal val eapolSource: Int = 0,
    val akms: Set<Akm>? = null
) {
    /** Enough for the full 4-way MIC check (needs ANonce, SNonce, the EAPOL body, and the MIC). */
    val hasFourWayMaterial: Boolean
        get() = anonce != null && snonce != null && eapolFrame != null && capturedMic != null

    /** Enough for the (faster, no-handshake-required) PMKID check. */
    val hasPmkidMaterial: Boolean get() = pmkid != null
}

/** Result of running [HandshakeCracker.crack] against one [CrackableHandshake]. */
data class CrackOutcome(
    val bssid: String,
    val essid: String?,
    val station: String,
    val found: Boolean,
    val password: String?,
    val method: CrackMethod?,
    val candidatesTried: Long,
    val elapsedMs: Long
)

/**
 * A Wireshark-style one-line summary of a single captured frame: timestamp,
 * addresses, and - where the payload isn't encrypted - the transport-layer
 * protocol/ports and a short human-readable info string (an HTTP request
 * line, TCP flags, a beacon's SSID, an EAPOL message number, ...). Obtained
 * via [HandshakeValidator.dissect]. Encrypted 802.11 data payloads (the
 * normal case for a WPA-protected network) can't be dissected past the
 * 802.11 header without the derived keys - those just report "Data
 * (encrypted)".
 */
data class PacketSummary(
    val index: Int,
    val timestampUs: Long,
    val srcMac: String?,
    val dstMac: String?,
    val srcIp: String?,
    val dstIp: String?,
    val srcPort: Int?,
    val dstPort: Int?,
    val protocol: String,
    val length: Int,
    val info: String
)

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
     * Throws [InvalidPcapException] for a corrupted/truncated header or a
     * link-layer type this tool doesn't understand. Both classic pcap and
     * pcapng captures are supported.
     */
    fun analyze(file: File): List<HandshakeFinding> {
        val aps = parseFile(file).aps
        val findings = mutableListOf<HandshakeFinding>()
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
                    isValidHandshake = st.confirmedFourWay || st.confirmedPmkid,
                    akms = ap.akms
                )
            }
        }
        return findings
    }

    /** Convenience: does this capture contain *any* crackable handshake material at all? */
    fun hasValidHandshake(file: File): Boolean = analyze(file).any { it.isValidHandshake }

    /**
     * Like [analyze], but returns the raw cryptographic material needed to
     * actually attempt cracking each valid handshake (see [HandshakeCracker])
     * or export it (see [Hc22000Exporter]). Only pairs that are crackable
     * (full 4-way MIC material or a PMKID) are included.
     */
    fun extractCrackable(file: File): List<CrackableHandshake> {
        val aps = parseFile(file).aps
        val result = mutableListOf<CrackableHandshake>()
        for ((bssid, ap) in aps) {
            val bssidBytes = macStringToBytes(bssid)
            for ((station, st) in ap.stations) {
                if (!st.confirmedFourWay && !st.confirmedPmkid) continue
                result += CrackableHandshake(
                    bssid = bssid,
                    essid = ap.essid,
                    station = station,
                    keyVersion = st.keyVersion,
                    bssidBytes = bssidBytes,
                    staBytes = macStringToBytes(station),
                    essidBytes = ap.essidBytes,
                    anonce = st.anonce,
                    snonce = st.snonce,
                    eapolFrame = st.eapolFrame,
                    capturedMic = st.capturedMic,
                    pmkid = st.pmkid,
                    anonceSource = st.anonceSource,
                    snonceSource = st.snonceSource,
                    eapolSource = st.eapolSource,
                    akms = ap.akms
                )
            }
        }
        return result
    }

    /**
     * Wireshark-style per-packet summary of every frame in [file]: index,
     * timestamp, addresses, and - where the payload isn't encrypted - the
     * IP/TCP/UDP/HTTP layers inside it. Works for 802.11 (WiFi, including
     * radiotap/prism/PPI-wrapped) and plain Ethernet captures alike.
     */
    fun dissect(file: File): List<PacketSummary> = parseFile(file, dissect = true).packets

    private class ParsedCapture(val aps: Map<String, ApState>, val packets: List<PacketSummary>)

    private fun parseFile(file: File, dissect: Boolean = false): ParsedCapture {
        val aps = LinkedHashMap<String, ApState>()
        val packets = if (dissect) mutableListOf<PacketSummary>() else null
        var packetIndex = 0

        fun handleRawPacket(linkType: Int, tsUs: Long, data: ByteArray) {
            packetIndex++
            val frame = stripLinkLayerHeader(data, linkType) ?: return
            if (isDot11LinkType(linkType)) {
                processFrame(frame, tsUs, aps)
            }
            if (packets != null) {
                packets += dissectPacket(packetIndex, tsUs, linkType, frame)
            }
        }

        BufferedInputStream(FileInputStream(file), 1 shl 16).use { input ->
            val magicBytes = readFullyOrThrow(input, 4, "File is too short to be a pcap/pcapng capture.")
            if (magicBytes.u32le(0) == 0x0a0d0d0aL) {
                parsePcapNgBody(input, ::handleRawPacket)
            } else {
                parseClassicPcapBody(input, magicBytes, ::handleRawPacket)
            }
        }
        return ParsedCapture(aps, packets ?: emptyList())
    }
}

// =====================================================================
// Offline dictionary attack (ports aircrack-ng's crypto.c: calc_pmk,
// calc_mic, and the PMKID formula from IEEE 802.11i-2004 8.5.1.2)
// =====================================================================

object HandshakeCracker {

    /**
     * Tries every line of [wordlistFile] as a candidate WPA/WPA2-PSK
     * passphrase against [handshake], using [handshake]'s captured PMKID
     * and/or full 4-way MIC material (whichever is available - PMKID is
     * checked first since it's cheaper and doesn't need the whole handshake).
     *
     * The network's SSID is required as the PBKDF2 salt: it's taken from
     * [handshake].essid if the capture contained a beacon/probe response for
     * that AP, otherwise pass it explicitly via [essidOverride].
     *
     * WPA passphrases are 8-63 ASCII characters per the spec; other lines in
     * the wordlist are skipped automatically. Stops at the first match.
     *
     * If [applyRules] is true, each wordlist word is also tried with common
     * case variants, leetspeak substitutions, and numeric/symbol suffixes
     * (see [ruleVariants]) - this multiplies the search space by roughly two
     * orders of magnitude, so expect a correspondingly longer run.
     *
     * Throws [IllegalArgumentException] up front (without touching the
     * wordlist) if [handshake].akms indicates this AP doesn't use a
     * passphrase-derived key at all (WPA3-SAE-only or Enterprise) - see
     * [isPasswordCrackable].
     *
     * Only use this against a handshake you own or are authorized to test.
     */
    fun crack(
        handshake: CrackableHandshake,
        wordlistFile: File,
        essidOverride: String? = null,
        threads: Int = Runtime.getRuntime().availableProcessors(),
        applyRules: Boolean = false,
        onProgress: ((candidatesTried: Long) -> Unit)? = null
    ): CrackOutcome {
        require(handshake.hasFourWayMaterial || handshake.hasPmkidMaterial) {
            "This handshake doesn't have enough captured material to attempt cracking."
        }
        if (!isPasswordCrackable(handshake.akms)) {
            throw IllegalArgumentException(
                "${handshake.bssid} (${handshake.essid ?: "unknown SSID"}) doesn't use a " +
                    "passphrase-derived key, so a wordlist attack won't work: ${describeSecurity(handshake.akms)}."
            )
        }
        val essidBytes = essidOverride?.toByteArray(Charsets.UTF_8) ?: handshake.essidBytes
            ?: throw IllegalArgumentException(
                "No SSID known for ${handshake.bssid}. The capture didn't contain a beacon " +
                    "or probe response for this AP - pass the network name explicitly via essidOverride."
            )

        val baseWords = wordlistFile.readLines(Charsets.UTF_8)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val candidates = (
            if (applyRules) baseWords.flatMap { ruleVariants(it) } else baseWords
            )
            .filter { it.length in 8..63 }
            .toList()

        val startNs = System.nanoTime()
        if (candidates.isEmpty()) {
            return CrackOutcome(handshake.bssid, handshake.essid, handshake.station, false, null, null, 0, 0)
        }

        val tried = AtomicLong(0)
        val result = AtomicReference<Pair<String, CrackMethod>?>(null)
        val effectiveThreads = threads.coerceAtLeast(1)
        val chunkSize = ((candidates.size + effectiveThreads - 1) / effectiveThreads).coerceAtLeast(1)
        val pool = Executors.newFixedThreadPool(effectiveThreads)

        val futures = candidates.chunked(chunkSize).map { chunk ->
            pool.submit {
                for (candidate in chunk) {
                    if (result.get() != null) break
                    tried.incrementAndGet()
                    onProgress?.invoke(tried.get())

                    val pmk = derivePmk(candidate, essidBytes)

                    if (handshake.hasPmkidMaterial) {
                        val pmkid = computePmkid(pmk, handshake.bssidBytes, handshake.staBytes)
                        if (pmkid.contentEquals(handshake.pmkid!!)) {
                            result.compareAndSet(null, candidate to CrackMethod.PMKID)
                            break
                        }
                    }
                    if (handshake.hasFourWayMaterial) {
                        val ptk = derivePtk(pmk, handshake.bssidBytes, handshake.staBytes, handshake.anonce!!, handshake.snonce!!)
                        val kck = ptk.copyOfRange(0, 16)
                        val mic = computeMic(kck, handshake.eapolFrame!!, handshake.keyVersion)
                        if (mic.contentEquals(handshake.capturedMic!!)) {
                            result.compareAndSet(null, candidate to CrackMethod.FOUR_WAY_MIC)
                            break
                        }
                    }
                }
            }
        }
        futures.forEach { it.get() }
        pool.shutdown()

        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
        val finalResult = result.get()
        return CrackOutcome(
            bssid = handshake.bssid,
            essid = handshake.essid,
            station = handshake.station,
            found = finalResult != null,
            password = finalResult?.first,
            method = finalResult?.second,
            candidatesTried = tried.get(),
            elapsedMs = elapsedMs
        )
    }

    /**
     * A small, fixed set of common password-mangling rules: case variants,
     * a handful of leetspeak substitutions, and common numeric/symbol
     * suffixes. Nowhere near as thorough as a real rule engine (e.g.
     * hashcat's best64.rule), but enough to catch "password" -> "Password1!"
     * style variations that a plain wordlist alone would miss. Multiplies
     * the candidate count by roughly two orders of magnitude.
     */
    private fun ruleVariants(word: String): Sequence<String> = sequence {
        val leet = word
            .replace('a', '4').replace('A', '4')
            .replace('e', '3').replace('E', '3')
            .replace('i', '1').replace('I', '1')
            .replace('o', '0').replace('O', '0')
            .replace('s', '5').replace('S', '5')
        val bases = listOf(word, word.replaceFirstChar { it.uppercase() }, word.uppercase(), leet).distinct()
        val suffixes = listOf("") + (0..99).map { it.toString() } + listOf("!", "@", "#", "$", "2024", "2025")
        for (base in bases) {
            for (suffix in suffixes) yield(base + suffix)
        }
    }

    /** PMK = PBKDF2-HMAC-SHA1(passphrase, salt=SSID, 4096 iterations, 256-bit output). */
    private fun derivePmk(passphrase: String, essidBytes: ByteArray): ByteArray {
        val spec = PBEKeySpec(passphrase.toCharArray(), essidBytes, 4096, 256)
        val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        return skf.generateSecret(spec).encoded
    }

    /**
     * PTK = PRF-640(PMK, "Pairwise key expansion",
     *               Min(AA,SPA) || Max(AA,SPA) || Min(ANonce,SNonce) || Max(ANonce,SNonce))
     * Only the first 16 bytes (the KCK) are used by [computeMic]; the rest of
     * the PTK (encryption keys) isn't needed just to verify a passphrase guess.
     */
    private fun derivePtk(
        pmk: ByteArray,
        bssidBytes: ByteArray,
        staBytes: ByteArray,
        anonce: ByteArray,
        snonce: ByteArray
    ): ByteArray {
        val label = "Pairwise key expansion".toByteArray(Charsets.US_ASCII) + byteArrayOf(0) // 23 bytes incl. NUL
        val pke = ByteArray(100)
        System.arraycopy(label, 0, pke, 0, 23)

        if (compareBytes(staBytes, bssidBytes) < 0) {
            System.arraycopy(staBytes, 0, pke, 23, 6)
            System.arraycopy(bssidBytes, 0, pke, 29, 6)
        } else {
            System.arraycopy(bssidBytes, 0, pke, 23, 6)
            System.arraycopy(staBytes, 0, pke, 29, 6)
        }

        if (compareBytes(snonce, anonce) < 0) {
            System.arraycopy(snonce, 0, pke, 35, 32)
            System.arraycopy(anonce, 0, pke, 67, 32)
        } else {
            System.arraycopy(anonce, 0, pke, 35, 32)
            System.arraycopy(snonce, 0, pke, 67, 32)
        }

        val mac = Mac.getInstance("HmacSHA1")
        val keySpec = SecretKeySpec(pmk, "HmacSHA1")
        val ptk = ByteArray(80)
        for (i in 0 until 4) {
            pke[99] = i.toByte()
            mac.init(keySpec)
            val out = mac.doFinal(pke)
            System.arraycopy(out, 0, ptk, i * 20, 20)
        }
        return ptk
    }

    /** MIC = HMAC-MD5(KCK, eapolFrame) for keyver 1 (WPA/TKIP), else HMAC-SHA1(KCK, eapolFrame). */
    private fun computeMic(kck: ByteArray, eapolFrame: ByteArray, keyVersion: Int): ByteArray {
        val algorithm = if (keyVersion == 1) "HmacMD5" else "HmacSHA1"
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(kck, algorithm))
        return mac.doFinal(eapolFrame).copyOfRange(0, 16)
    }

    /** PMKID = HMAC-SHA1-128(PMK, "PMK Name" || AA || SPA)  [IEEE 802.11i-2004 8.5.1.2] */
    private fun computePmkid(pmk: ByteArray, bssidBytes: ByteArray, staBytes: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(pmk, "HmacSHA1"))
        mac.update("PMK Name".toByteArray(Charsets.US_ASCII))
        mac.update(bssidBytes)
        mac.update(staBytes)
        return mac.doFinal().copyOfRange(0, 16)
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return 0
    }
}

// =====================================================================
// hashcat -m 22000 (WPA-PBKDF2-PMKID+EAPOL) export
//
// Format: WPA*<type>*<PMKID/MIC>*<MAC_AP>*<MAC_STA>*<ESSID>*<ANONCE>*<EAPOL>*<MESSAGE_PAIR>
// type 01 = PMKID line, 02 = EAPOL line. All binary fields are lowercase hex.
// Reference: https://hashcat.net/wiki/doku.php?id=hashcat -m 22000, and
// hcxtools' message_pair table for which two of the four EAPOL messages a
// given hash line was built from.
// =====================================================================

object Hc22000Exporter {

    /**
     * Builds every hashcat-22000 hash line [handshake] has material for: a
     * PMKID line if a PMKID was captured, an EAPOL line if a full 4-way MIC
     * was captured (both, if both are available - hashcat reuses the same
     * PBKDF2 pass for either). Returns an empty list if [handshake] has an
     * SSID but neither kind of material, and throws if it has no SSID at all
     * (pass [essidOverride] in that case).
     */
    fun toHashLines(handshake: CrackableHandshake, essidOverride: String? = null): List<String> {
        val essidBytes = essidOverride?.toByteArray(Charsets.UTF_8) ?: handshake.essidBytes
            ?: throw IllegalArgumentException(
                "No SSID known for ${handshake.bssid} - pass the network name explicitly via essidOverride."
            )
        val lines = mutableListOf<String>()
        val macAp = handshake.bssidBytes.toHex()
        val macSta = handshake.staBytes.toHex()
        val essidHex = essidBytes.toHex()

        val pmkid = handshake.pmkid
        if (pmkid != null) {
            lines += "WPA*01*${pmkid.toHex()}*$macAp*$macSta*$essidHex***"
        }
        if (handshake.hasFourWayMaterial) {
            val messagePair = messagePairByte(handshake.anonceSource, handshake.snonceSource, handshake.eapolSource)
            lines += "WPA*02*${handshake.capturedMic!!.toHex()}*$macAp*$macSta*$essidHex*" +
                "${handshake.anonce!!.toHex()}*${handshake.eapolFrame!!.toHex()}*" +
                "%02x".format(messagePair)
        }
        return lines
    }

    /**
     * hcxtools' message_pair encoding. Which second field matters depends on
     * which message actually carries the MIC (eapolSource): if it's M2 or M4
     * (both carry an SNonce already), the *other* needed piece is which
     * message the ANonce came from; if it's M3 (which carries an ANonce
     * already, since M3's nonce is the same ANonce as M1's), the other
     * needed piece is which message the SNonce came from instead.
     */
    private fun messagePairByte(anonceSource: Int, snonceSource: Int, eapolSource: Int): Int = when (eapolSource) {
        2 -> if (anonceSource == 3) 0x02 else 0x00 // M1+M2 (0x00) or M2+M3 (0x02)
        4 -> if (anonceSource == 3) 0x05 else 0x01 // M1+M4 (0x01) or M3+M4 (0x05)
        3 -> if (snonceSource == 4) 0x04 else 0x03 // M2+M3 (0x03) or M3+M4 (0x04)
        else -> 0x00 // shouldn't happen if hasFourWayMaterial is true; default to the common case
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append("%02x".format(b.toInt() and 0xFF))
        return sb.toString()
    }
}

// =====================================================================
// Wireshark-style packet dissection (HTTP/TCP/UDP/ICMP/IP/MAC/time)
//
// A deliberately lightweight dissector, not a Wireshark replacement: no TCP
// stream reassembly, no IPv6 extension-header walking, no protocols beyond
// the common ones below. It exists to answer "what's actually in this
// capture" at a glance - full packet analysis belongs in Wireshark itself.
// =====================================================================

private class L3Info(
    val protocol: String,
    val srcIp: String? = null,
    val dstIp: String? = null,
    val srcPort: Int? = null,
    val dstPort: Int? = null,
    val info: String
)

private fun dissectPacket(index: Int, tsUs: Long, linkType: Int, frame: ByteArray): PacketSummary {
    return if (linkType == LINKTYPE_ETHERNET) dissectEthernet(index, tsUs, frame) else dissect80211(index, tsUs, frame)
}

private fun dissectEthernet(index: Int, tsUs: Long, h: ByteArray): PacketSummary {
    if (h.size < 14) {
        return PacketSummary(index, tsUs, null, null, null, null, null, null, "Ethernet", h.size, "Malformed/short frame")
    }
    val dst = h.macString(0)
    val src = h.macString(6)
    val etherType = (h.u8(12) shl 8) or h.u8(13)
    val l3 = dissectEtherType(etherType, h, 14)
    return PacketSummary(index, tsUs, src, dst, l3.srcIp, l3.dstIp, l3.srcPort, l3.dstPort, l3.protocol, h.size, l3.info)
}

private fun dissect80211(index: Int, tsUs: Long, h: ByteArray): PacketSummary {
    if (h.size < 2) {
        return PacketSummary(index, tsUs, null, null, null, null, null, null, "802.11", h.size, "Malformed/short frame")
    }
    val fc0 = h.u8(0)
    val fc1 = h.u8(1)
    val frameType = fc0 and FC0_TYPE_MASK

    if (frameType == FC0_TYPE_CTL) {
        // Control frames are short and variably sized (CTS/ACK=10 bytes, RTS=16, ...);
        // only Address1 (RA) is reliably present, and only for the longer ones.
        val addr1 = if (h.size >= 10) h.macString(4) else null
        val addr2 = if (h.size >= 16) h.macString(10) else null
        return PacketSummary(index, tsUs, addr2, addr1, null, null, null, null, "802.11", h.size, controlFrameInfo(fc0))
    }

    if (h.size < 24) {
        return PacketSummary(index, tsUs, null, null, null, null, null, null, "802.11", h.size, "Malformed/short frame")
    }
    val dir = fc1 and FC1_DIR_MASK
    val addr1 = h.macString(4)  // receiver
    val addr2 = h.macString(10) // transmitter

    if (frameType != FC0_TYPE_DATA) {
        return PacketSummary(index, tsUs, addr2, addr1, null, null, null, null, "802.11", h.size, managementFrameInfo(fc0, h))
    }

    // Data frame: walk the same header layout processFrame() uses, purely for display this time.
    var z = if (dir != FC1_DIR_DSTODS) 24 else 30
    if ((fc0 and 0x80) == 0x80) z += 2 // QoS Control field
    if (z + 16 > h.size || !(h.u8(z) == h.u8(z + 1) && h.u8(z + 2) == 0x03)) {
        return PacketSummary(index, tsUs, addr2, addr1, null, null, null, null, "802.11", h.size, "Data (encrypted)")
    }
    z += 6 // past DSAP+SSAP+Control+OUI, now at the 2-byte EtherType
    if (z + 2 > h.size) {
        return PacketSummary(index, tsUs, addr2, addr1, null, null, null, null, "802.11", h.size, "Data")
    }
    val etherType = (h.u8(z) shl 8) or h.u8(z + 1)
    val l3 = dissectEtherType(etherType, h, z + 2)
    return PacketSummary(index, tsUs, addr2, addr1, l3.srcIp, l3.dstIp, l3.srcPort, l3.dstPort, l3.protocol, h.size, l3.info)
}

private fun managementFrameInfo(fc0: Int, h: ByteArray): String {
    fun ssidSuffix(start: Int): String = parseEssidForDisplay(h, start)?.let { ", SSID=$it" } ?: ""
    return when (fc0) {
        0x80 -> "Beacon" + ssidSuffix(36)
        0x50 -> "Probe Response" + ssidSuffix(36)
        0x40 -> "Probe Request" + ssidSuffix(24)
        0x00 -> "Association Request" + ssidSuffix(28)
        0x10 -> "Association Response"
        0x20 -> "Reassociation Request"
        0x30 -> "Reassociation Response"
        0xA0 -> "Disassociation"
        0xB0 -> "Authentication"
        0xC0 -> "Deauthentication"
        0xD0 -> "Action"
        else -> "Management (subtype 0x%02x)".format((fc0 shr 4) and 0x0F)
    }
}

private fun controlFrameInfo(fc0: Int): String = when ((fc0 shr 4) and 0x0F) {
    7 -> "Control Wrapper"
    8 -> "Block Ack Request"
    9 -> "Block Ack"
    10 -> "PS-Poll"
    11 -> "RTS"
    12 -> "CTS"
    13 -> "ACK"
    14 -> "CF-End"
    15 -> "CF-End + CF-Ack"
    else -> "Control (subtype ${(fc0 shr 4) and 0x0F})"
}

/** Standalone SSID-element scan for display purposes (doesn't touch ApState, unlike extractEssid). */
private fun parseEssidForDisplay(h: ByteArray, start: Int): String? {
    var p = start
    while (p + 2 <= h.size) {
        val elemLen = h.u8(p + 1)
        if (p + 2 + elemLen > h.size) break
        if (h.u8(p) == 0 && elemLen > 0 && h.u8(p + 2) != 0) {
            return String(h, p + 2, minOf(elemLen, 32), Charsets.UTF_8)
        }
        p += 2 + elemLen
    }
    return null
}

/**
 * Best-effort EAPOL message-number label for one frame in isolation - just
 * for display. Unlike [handleEapolKey], this doesn't track replay counters
 * or state across frames, since it's only describing this single packet.
 */
private fun describeEapolFrame(h: ByteArray, z: Int): String {
    if (z + 7 > h.size) return "EAPOL (truncated)"
    if (h.u8(z + 1) != 0x03) return "EAPOL (type ${h.u8(z + 1)})"
    val infoLow = h.u8(z + 5)
    val infoHigh = h.u8(z + 6)
    val pairwise = (infoHigh and 0x08) != 0
    val install = (infoHigh and 0x40) != 0
    val ack = (infoHigh and 0x80) != 0
    val mic = (infoLow and 0x01) != 0
    val msg = when {
        pairwise && !install && ack && !mic -> 1
        pairwise && !install && !ack && mic ->
            if (z + 99 <= h.size && ((h.u8(z + 97) shl 8) or h.u8(z + 98)) == 0) 4 else 2
        pairwise && install && ack && mic -> 3
        else -> 0
    }
    return if (msg > 0) "Key (Message $msg of 4)" else "EAPOL-Key"
}

private fun dissectEtherType(etherType: Int, h: ByteArray, off: Int): L3Info = when (etherType) {
    0x0800 -> dissectIpv4(h, off)
    0x86DD -> dissectIpv6(h, off)
    0x0806 -> L3Info("ARP", info = "Address Resolution Protocol")
    0x888E -> L3Info("EAPOL", info = describeEapolFrame(h, off))
    else -> L3Info("0x%04x".format(etherType), info = "EtherType 0x%04x".format(etherType))
}

private fun ipv4String(h: ByteArray, off: Int): String = "${h.u8(off)}.${h.u8(off + 1)}.${h.u8(off + 2)}.${h.u8(off + 3)}"

private fun ipv6String(h: ByteArray, off: Int): String =
    (0 until 8).joinToString(":") { i -> Integer.toHexString((h.u8(off + i * 2) shl 8) or h.u8(off + i * 2 + 1)) }

private fun dissectIpv4(h: ByteArray, off: Int): L3Info {
    if (off + 20 > h.size) return L3Info("IPv4", info = "Truncated IPv4 header")
    val ihl = (h.u8(off) and 0x0F) * 4
    if (ihl < 20 || off + ihl > h.size) return L3Info("IPv4", info = "Malformed IPv4 header")
    val protocolNum = h.u8(off + 9)
    val srcIp = ipv4String(h, off + 12)
    val dstIp = ipv4String(h, off + 16)
    val payloadOff = off + ihl
    return when (protocolNum) {
        6 -> dissectTcp(h, payloadOff, srcIp, dstIp)
        17 -> dissectUdp(h, payloadOff, srcIp, dstIp)
        1 -> L3Info("ICMP", srcIp, dstIp, info = dissectIcmp(h, payloadOff))
        2 -> L3Info("IGMP", srcIp, dstIp, info = "IGMP")
        else -> L3Info("IPv4", srcIp, dstIp, info = "IPv4 protocol $protocolNum")
    }
}

private fun dissectIpv6(h: ByteArray, off: Int): L3Info {
    if (off + 40 > h.size) return L3Info("IPv6", info = "Truncated IPv6 header")
    val nextHeader = h.u8(off + 6)
    val srcIp = ipv6String(h, off + 8)
    val dstIp = ipv6String(h, off + 24)
    val payloadOff = off + 40 // extension headers aren't walked - a documented simplification
    return when (nextHeader) {
        6 -> dissectTcp(h, payloadOff, srcIp, dstIp)
        17 -> dissectUdp(h, payloadOff, srcIp, dstIp)
        58 -> L3Info("ICMPv6", srcIp, dstIp, info = dissectIcmp(h, payloadOff))
        else -> L3Info("IPv6", srcIp, dstIp, info = "IPv6 next-header $nextHeader")
    }
}

private fun dissectTcp(h: ByteArray, off: Int, srcIp: String, dstIp: String): L3Info {
    if (off + 20 > h.size) return L3Info("TCP", srcIp, dstIp, info = "Truncated TCP header")
    val srcPort = (h.u8(off) shl 8) or h.u8(off + 1)
    val dstPort = (h.u8(off + 2) shl 8) or h.u8(off + 3)
    val seq = beU32(h, off + 4)
    val ack = beU32(h, off + 8)
    val dataOffset = (h.u8(off + 12) shr 4) * 4
    val flagsByte = h.u8(off + 13)
    val flags = buildList {
        if ((flagsByte and 0x02) != 0) add("SYN")
        if ((flagsByte and 0x10) != 0) add("ACK")
        if ((flagsByte and 0x01) != 0) add("FIN")
        if ((flagsByte and 0x04) != 0) add("RST")
        if ((flagsByte and 0x08) != 0) add("PSH")
        if ((flagsByte and 0x20) != 0) add("URG")
    }
    val flagsPrefix = if (flags.isEmpty()) "" else "[${flags.joinToString(", ")}] "
    var info = "$srcPort -> $dstPort ${flagsPrefix}Seq=$seq Ack=$ack"
    val payloadOff = off + dataOffset
    if (dataOffset in 20..(h.size - off)) {
        tryParseHttp(h, payloadOff)?.let { info = it }
    }
    return L3Info("TCP", srcIp, dstIp, srcPort, dstPort, info)
}

private fun dissectUdp(h: ByteArray, off: Int, srcIp: String, dstIp: String): L3Info {
    if (off + 8 > h.size) return L3Info("UDP", srcIp, dstIp, info = "Truncated UDP header")
    val srcPort = (h.u8(off) shl 8) or h.u8(off + 1)
    val dstPort = (h.u8(off + 2) shl 8) or h.u8(off + 3)
    val len = (h.u8(off + 4) shl 8) or h.u8(off + 5)
    return L3Info("UDP", srcIp, dstIp, srcPort, dstPort, "$srcPort -> $dstPort Len=$len")
}

private fun dissectIcmp(h: ByteArray, off: Int): String {
    if (off + 2 > h.size) return "Truncated ICMP"
    return "Type=${h.u8(off)} Code=${h.u8(off + 1)}"
}

private val HTTP_METHODS = listOf("GET ", "POST ", "HEAD ", "PUT ", "DELETE ", "OPTIONS ", "PATCH ", "CONNECT ", "TRACE ")
private val HTTP_HOST_HEADER = Regex("(?i)\r\nHost: *([^\r\n]+)")

/** Sniffs cleartext HTTP request/response lines out of a TCP payload; returns null if it doesn't look like HTTP. */
private fun tryParseHttp(h: ByteArray, off: Int): String? {
    val maxLen = minOf(2048, h.size - off)
    if (maxLen <= 0) return null
    val text = String(h, off, maxLen, Charsets.US_ASCII)
    val firstLineEnd = text.indexOf("\r\n").let { if (it < 0) text.length else it }
    val firstLine = text.substring(0, firstLineEnd)
    val isRequest = HTTP_METHODS.any { firstLine.startsWith(it) }
    val isResponse = firstLine.startsWith("HTTP/1.")
    if (!isRequest && !isResponse) return null
    if (isRequest) {
        val host = HTTP_HOST_HEADER.find(text)?.groupValues?.get(1)
        return if (host != null) "$firstLine (Host: $host)" else firstLine
    }
    return firstLine
}

// =====================================================================
// Internal mutable state (mirrors aircrack-ng's struct WPA_hdsk)
// =====================================================================

private class ApState {
    var essid: String? = null
    var essidBytes: ByteArray? = null // raw bytes as captured; used for PBKDF2 salt (must not be re-encoded)
    var akms: Set<Akm>? = null // from the RSN IE in a beacon/probe response; null if none seen yet
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

    // Raw material needed for cracking (see calc_pmk/calc_mic in aircrack-ng's crypto.c).
    var anonce: ByteArray? = null
    var snonce: ByteArray? = null
    var capturedMic: ByteArray? = null // the MIC as transmitted, before zeroing it in eapolFrame
    var eapolFrame: ByteArray? = null  // the EAPOL-Key frame bytes with the MIC field zeroed
    var anonceSource: Int = 0          // which message the ANonce came from: 1 or 3 (0 = not yet set)
    var snonceSource: Int = 0          // which message the SNonce came from: 2 or 4 (0 = not yet set)
    var eapolSource: Int = 0           // which message eapolFrame/capturedMic came from: 2, 3, or 4

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
        // `found`, pmkid, captured crypto material, and the confirmed_* latches
        // are deliberately left alone (a latched success must survive later,
        // unrelated frames resetting the live handshake state).
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
private const val IEEE80211_ELEMID_RSN = 48

// pcap DLT_* link-layer type numbers
private const val LINKTYPE_ETHERNET = 1
private const val LINKTYPE_IEEE802_11 = 105
private const val LINKTYPE_PRISM_HEADER = 119
private const val LINKTYPE_RADIOTAP_HDR = 127
private const val LINKTYPE_PPI_HDR = 192

private fun isDot11LinkType(t: Int) =
    t == LINKTYPE_IEEE802_11 || t == LINKTYPE_PRISM_HEADER ||
        t == LINKTYPE_RADIOTAP_HDR || t == LINKTYPE_PPI_HDR

private fun isKnownLinkType(t: Int) = isDot11LinkType(t) || t == LINKTYPE_ETHERNET

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

private fun macStringToBytes(mac: String): ByteArray =
    mac.split(':').map { it.toInt(16).toByte() }.toByteArray()

private const val BROADCAST_MAC = "ff:ff:ff:ff:ff:ff"

// =====================================================================
// pcap / pcapng file reading
// =====================================================================

private fun parseClassicPcapBody(input: InputStream, magicBytes: ByteArray, onPacket: (linkType: Int, tsUs: Long, data: ByteArray) -> Unit) {
    val hdrRest = readFullyOrThrow(input, 20, "File is too short to be a pcap capture.")
    // Read the magic as little-endian regardless of the file's actual byte
    // order: for a genuinely little-endian file this reproduces the magic
    // constant directly; for a big-endian file it reproduces the well-known
    // *byte-swapped* constant instead. Either way we can identify the file
    // (and its true endianness) from a fixed set of four expected values,
    // with no chicken-and-egg problem.
    val magic = magicBytes.u32le(0)
    val (littleEndian, nanoSeconds) = when (magic) {
        0xa1b2c3d4L -> true to false
        0xa1b23c4dL -> true to true
        0xd4c3b2a1L -> false to false
        0x4d3cb2a1L -> false to true
        else -> throw InvalidPcapException("Not a recognized pcap file (unexpected magic number).")
    }
    val linkType = if (littleEndian) hdrRest.u32le(16).toInt() else beU32(hdrRest, 16).toInt()
    if (!isKnownLinkType(linkType)) {
        throw InvalidPcapException(
            "Unsupported link-layer type $linkType. This tool expects a raw or " +
                "radiotap/prism/PPI-wrapped 802.11 capture (e.g. from airodump-ng or a " +
                "monitor-mode adapter) or a plain Ethernet capture."
        )
    }

    while (true) {
        val record = readPacketRecord(input, littleEndian) ?: break
        val tsUs = if (nanoSeconds) {
            record.tsSec * 1_000_000L + record.tsSubSec / 1000L
        } else {
            record.tsSec * 1_000_000L + record.tsSubSec
        }
        onPacket(linkType, tsUs, record.data)
    }
}

/**
 * Minimal pcapng reader covering the block types virtually every real-world
 * writer (Wireshark, dumpcap, tshark, hcxdumptool) actually produces: the
 * Section Header Block, Interface Description Blocks (one per capture
 * interface, each with its own link type and optional timestamp
 * resolution), and Enhanced/Simple Packet Blocks. Other block types (name
 * resolution, interface statistics, decryption secrets, custom blocks, ...)
 * are skipped generically using their declared length. A new Section Header
 * Block mid-file (a new "section") is treated as continuing the same byte
 * order as the first - genuinely mixed-endianness multi-section files are
 * vanishingly rare in practice and unsupported here.
 */
private fun parsePcapNgBody(input: InputStream, onPacket: (linkType: Int, tsUs: Long, data: ByteArray) -> Unit) {
    // The 4-byte Section Header Block type (the pcapng magic, 0x0A0D0D0A)
    // was already consumed by the caller to detect the format. What follows
    // is: Block Total Length(4), Byte-Order Magic(4), then the rest of the
    // block body, then the Block Total Length repeated(4).
    val shbLenRaw = readFullyOrThrow(input, 4, "Truncated pcapng Section Header Block.")
    val bomRaw = readFullyOrThrow(input, 4, "Truncated pcapng Section Header Block.")
    val bom = bomRaw.u32le(0)
    val littleEndian = when (bom) {
        0x1a2b3c4dL -> true
        0x4d3c2b1aL -> false
        else -> throw InvalidPcapException("Unrecognized pcapng byte-order magic.")
    }
    fun u32(b: ByteArray, off: Int): Long = if (littleEndian) b.u32le(off) else beU32(b, off)
    fun u16(b: ByteArray, off: Int): Int = if (littleEndian) b.u16le(off) else ((b.u8(off) shl 8) or b.u8(off + 1))

    val shbLen = u32(shbLenRaw, 0)
    if (shbLen < 16) throw InvalidPcapException("Malformed pcapng Section Header Block.")
    // Bytes of the SHB body left to skip after the byte-order magic, then its trailing length repeat.
    val remaining = (shbLen - 16).toInt()
    if (remaining > 0) readFully(input, remaining)
    readFully(input, 4) // trailing length repeat, discarded

    val interfaceLinkTypes = mutableListOf<Int>()
    val interfaceTsResolutionUs = mutableListOf<Double>() // microseconds per timestamp tick

    while (true) {
        val header = readFully(input, 8) ?: break // clean EOF between blocks
        val blockType = u32(header, 0)
        val blockLen = u32(header, 4)
        if (blockLen < 12 || blockLen > MAX_REASONABLE_CAPLEN) {
            break // corrupted or truncated trailing block: stop here, keep what we've parsed so far
        }
        val body = readFully(input, (blockLen - 12).toInt()) ?: break // truncated: stop parsing
        readFully(input, 4) ?: break // trailing length repeat, discarded

        when (blockType) {
            0x00000001L -> { // Interface Description Block
                if (body.size >= 8) {
                    interfaceLinkTypes += u16(body, 0)
                    var tsResUs = 1.0 // default: microsecond resolution (if_tsresol absent)
                    var p = 8
                    while (p + 4 <= body.size) {
                        val optCode = u16(body, p)
                        val optLen = u16(body, p + 2)
                        if (optCode == 0 && optLen == 0) break // opt_endofopt
                        val valueStart = p + 4
                        if (valueStart + optLen > body.size) break
                        if (optCode == 9 && optLen >= 1) { // if_tsresol
                            val raw = body.u8(valueStart)
                            tsResUs = if ((raw and 0x80) != 0) {
                                1_000_000.0 / Math.pow(2.0, (raw and 0x7F).toDouble()) // power of 2
                            } else {
                                1_000_000.0 / Math.pow(10.0, raw.toDouble()) // power of 10
                            }
                        }
                        val padded = (optLen + 3) / 4 * 4
                        p = valueStart + padded
                    }
                    interfaceTsResolutionUs += tsResUs
                }
            }
            0x00000006L -> { // Enhanced Packet Block
                if (body.size >= 20) {
                    val ifId = u32(body, 0).toInt()
                    val tsHigh = u32(body, 4)
                    val tsLow = u32(body, 8)
                    val capLen = u32(body, 12).toInt()
                    if (capLen in 0..(body.size - 20)) {
                        val data = body.copyOfRange(20, 20 + capLen)
                        val resUs = interfaceTsResolutionUs.getOrElse(ifId) { 1.0 }
                        val ticks = (tsHigh shl 32) or (tsLow and 0xFFFFFFFFL)
                        val tsUs = (ticks.toDouble() * resUs).toLong()
                        val linkType = interfaceLinkTypes.getOrElse(ifId) { -1 }
                        if (isKnownLinkType(linkType)) onPacket(linkType, tsUs, data)
                    }
                }
            }
            0x00000003L -> { // Simple Packet Block (rare; no interface id or timestamp)
                if (body.size >= 4) {
                    val origLen = u32(body, 0).toInt()
                    val capLen = minOf(origLen, body.size - 4).coerceAtLeast(0)
                    if (capLen > 0) {
                        val data = body.copyOfRange(4, 4 + capLen)
                        val linkType = interfaceLinkTypes.getOrElse(0) { -1 }
                        if (isKnownLinkType(linkType)) onPacket(linkType, 0L, data)
                    }
                }
            }
            else -> { /* Section Header (new section), Name Resolution, Interface Statistics,
                         Decryption Secrets, custom blocks, etc. - already skipped above by length. */ }
        }
    }
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
        LINKTYPE_IEEE802_11, LINKTYPE_ETHERNET -> frame // nothing to strip
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

    // Beacon / probe response / association request -> opportunistically grab the ESSID
    // and (beacon/probe response only, since that's where the AP itself advertises it) RSN security info.
    if (fc0 == FC0_SUBTYPE_BEACON || fc0 == FC0_SUBTYPE_PROBE_RESP) {
        val ap = aps.getOrPut(bssid) { ApState() }
        extractEssid(h, 36, ap)
        parseAkms(h, 36)?.let { ap.akms = it }
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
            ap.essidBytes = h.copyOfRange(p + 2, p + 2 + n)
            ap.essid = String(h, p + 2, n, Charsets.UTF_8)
        }
        p += 2 + elemLen
    }
}

/**
 * AKM (Authentication and Key Management) suite, as advertised in an AP's
 * RSN information element. This is what actually determines whether a
 * dictionary attack against a captured handshake is even meaningful:
 * only [PSK], [PSK_SHA256], and [FT_PSK] derive their key from a passphrase
 * via PBKDF2. [SAE]/[FT_SAE] (WPA3-Personal) use the Dragonfly key exchange
 * instead, which is designed to resist offline dictionary attacks entirely,
 * and the enterprise/OWE suites have no shared passphrase at all.
 */
enum class Akm(val suiteType: Int, val label: String) {
    ENTERPRISE_8021X(1, "802.1X (Enterprise)"),
    PSK(2, "PSK"),
    FT_8021X(3, "FT/802.1X (Enterprise)"),
    FT_PSK(4, "FT/PSK"),
    ENTERPRISE_SHA256(5, "802.1X-SHA256 (Enterprise)"),
    PSK_SHA256(6, "PSK-SHA256"),
    SAE(8, "SAE"),
    FT_SAE(9, "FT/SAE"),
    OWE(18, "OWE"),
    OTHER(-1, "other");

    companion object {
        private val byType = values().filter { it.suiteType >= 0 }.associateBy { it.suiteType }
        fun fromSuiteType(type: Int): Akm = byType[type] ?: OTHER
    }
}

private val PASSWORD_CRACKABLE_AKMS = setOf(Akm.PSK, Akm.PSK_SHA256, Akm.FT_PSK)

/** True if any AKM in [akms] derives its key from a passphrase (or if unknown - i.e. don't block). */
fun isPasswordCrackable(akms: Set<Akm>?): Boolean = akms == null || akms.any { it in PASSWORD_CRACKABLE_AKMS }

/** Human-readable summary of an AP's security, e.g. for display or for a crack-time warning. */
fun describeSecurity(akms: Set<Akm>?): String {
    if (akms == null) return "Unknown (no beacon/probe response seen for this AP)"
    val hasPsk = akms.any { it == Akm.PSK || it == Akm.FT_PSK }
    val hasPskSha256 = Akm.PSK_SHA256 in akms
    val hasSae = akms.any { it == Akm.SAE || it == Akm.FT_SAE }
    val hasEnterprise = akms.any { it == Akm.ENTERPRISE_8021X || it == Akm.FT_8021X || it == Akm.ENTERPRISE_SHA256 }
    val hasOwe = Akm.OWE in akms
    return when {
        hasSae && (hasPsk || hasPskSha256) -> "WPA3 transition mode (SAE+PSK) - legacy clients may still be crackable via wordlist"
        hasSae -> "WPA3-Personal (SAE only) - NOT crackable via wordlist; SAE resists offline dictionary attacks by design"
        hasOwe && !hasPsk && !hasPskSha256 -> "OWE (Enhanced Open) - no shared passphrase, nothing to crack"
        hasEnterprise && !hasPsk && !hasPskSha256 -> "Enterprise (802.1X) - no shared passphrase, nothing to crack"
        hasPskSha256 && !hasPsk -> "WPA2-Personal (PSK-SHA256) - crackable in principle, but this tool's MIC/PTK math assumes SHA1 and won't verify a SHA256-based handshake correctly"
        hasPsk -> "WPA2/WPA-Personal (PSK)"
        else -> "Unrecognized AKM set: ${akms.joinToString { it.label }}"
    }
}

/** Parses the AKM suite list out of an RSN information element (tag 48), if present. */
private fun parseAkms(h: ByteArray, start: Int): Set<Akm>? {
    var p = start
    while (p + 2 <= h.size) {
        val elemId = h.u8(p)
        val elemLen = h.u8(p + 1)
        if (p + 2 + elemLen > h.size) break
        if (elemId == IEEE80211_ELEMID_RSN && elemLen >= 8) {
            val body = h.copyOfRange(p + 2, p + 2 + elemLen)
            var off = 2 // skip version(2)
            if (off + 4 > body.size) return null
            off += 4 // skip group cipher suite(4)
            if (off + 2 > body.size) return null
            val pairwiseCount = body.u16le(off); off += 2
            off += pairwiseCount * 4 // skip pairwise cipher suite list
            if (off + 2 > body.size) return null
            val akmCount = body.u16le(off); off += 2
            val akms = mutableSetOf<Akm>()
            for (i in 0 until akmCount) {
                if (off + 4 > body.size) break
                val isStandardOui = body.u8(off) == 0x00 && body.u8(off + 1) == 0x0f && body.u8(off + 2) == 0xac
                akms += if (isStandardOui) Akm.fromSuiteType(body.u8(off + 3)) else Akm.OTHER
                off += 4
            }
            return akms
        }
        p += 2 + elemLen
    }
    return null
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
        st.anonce = h.copyOfRange(z + 17, z + 49)
        st.anonceSource = 1

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

        val keyDataLen = (h.u8(z + 97) shl 8) or h.u8(z + 98)
        val thisMessage = if (keyDataLen == 0) 4 else 2

        if (!h.isAllZero(z + 17, 32)) {
            st.state = st.state or 2 // SNonce set
            st.snonce = h.copyOfRange(z + 17, z + 49)
            st.snonceSource = thisMessage
        }

        st.found = st.found or if (thisMessage == 4) M4_BIT else M2_BIT

        if ((st.state and 4) != 4 && captureEapolBody(h, z, st, thisMessage)) {
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
            st.anonce = h.copyOfRange(z + 17, z + 49)
            st.anonceSource = 3
        }
        if ((st.state and 4) != 4 && captureEapolBody(h, z, st, 3)) {
            st.state = st.state or 4
            st.keyVersion = infoHigh and 7
        }
    }
}

/**
 * Captures the raw EAPOL-Key frame (with the MIC field zeroed) and the
 * transmitted MIC itself, mirroring the input aircrack-ng's crypto.c
 * calc_mic() expects. Returns false (and captures nothing) if the frame's
 * own declared length is inconsistent with the captured data - matches
 * aircrack-ng's defensive bounds check against malformed frames.
 * [sourceMessage] (2, 3, or 4) records which EAPOL message provided this
 * material, needed later to compute hashcat's message_pair field.
 */
private fun captureEapolBody(h: ByteArray, z: Int, st: StationState, sourceMessage: Int): Boolean {
    val eapolSize = ((h.u8(z + 2) shl 8) or h.u8(z + 3)) + 4
    if (eapolSize <= 0 || z + eapolSize > h.size) return false
    st.capturedMic = h.copyOfRange(z + 81, z + 97)
    val eapol = h.copyOfRange(z, z + eapolSize)
    for (i in 81 until minOf(97, eapol.size)) eapol[i] = 0
    st.eapolFrame = eapol
    st.eapolSource = sourceMessage
    return true
}

// =====================================================================
// CLI
// =====================================================================

fun main(args: Array<String>) {
    var wordlistPath: String? = null
    var essidOverride: String? = null
    var applyRules = false
    var hc22000OutPath: String? = null
    var dissectMode = false
    val pcapPaths = mutableListOf<String>()

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--crack" -> {
                i++
                if (i >= args.size) { println("--crack requires a wordlist file path."); return }
                wordlistPath = args[i]
            }
            "--essid" -> {
                i++
                if (i >= args.size) { println("--essid requires a network name."); return }
                essidOverride = args[i]
            }
            "--rules" -> applyRules = true
            "--export-hc22000" -> {
                i++
                if (i >= args.size) { println("--export-hc22000 requires an output file path."); return }
                hc22000OutPath = args[i]
            }
            "--dissect" -> dissectMode = true
            else -> pcapPaths += args[i]
        }
        i++
    }

    if (pcapPaths.isEmpty()) {
        println(
            "Usage: HandshakeValidator [--crack wordlist.txt [--rules]] [--essid \"Network Name\"]\n" +
                "                           [--export-hc22000 out.hc22000] [--dissect]\n" +
                "                           <capture1.pcap> [capture2.pcap ...]"
        )
        return
    }

    val wordlistFile = wordlistPath?.let { File(it) }
    if (wordlistFile != null && !wordlistFile.exists()) {
        println("Wordlist not found: $wordlistPath")
        return
    }

    val hc22000Lines = if (hc22000OutPath != null) mutableListOf<String>() else null

    for (path in pcapPaths) {
        val file = File(path)
        println("=".repeat(70))
        println(file.name)
        println("=".repeat(70))

        if (!file.exists()) {
            println("  File not found.\n")
            continue
        }

        if (dissectMode) {
            val packets = try {
                HandshakeValidator.dissect(file)
            } catch (e: InvalidPcapException) {
                println("  ${e.message}\n")
                continue
            } catch (e: Exception) {
                println("  Failed to parse file: ${e.message}\n")
                continue
            }
            println("  ${packets.size} packets\n")
            println("  %-6s %-14s %-22s %-22s %-8s %-6s %s".format("No.", "Time", "Source", "Destination", "Protocol", "Length", "Info"))
            for (p in packets) {
                val time = "%.6f".format(p.timestampUs / 1_000_000.0)
                val src = p.srcIp ?: p.srcMac ?: ""
                val dst = p.dstIp ?: p.dstMac ?: ""
                println("  %-6d %-14s %-22s %-22s %-8s %-6d %s".format(p.index, time, src, dst, p.protocol, p.length, p.info))
            }
            println()
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
            println("    Security      : ${f.securityNote}")
            println("    Result        : ${if (f.isValidHandshake) "USABLE" else "incomplete"}")
            println()
        }

        if ((wordlistFile != null || hc22000Lines != null) && anyValid) {
            val crackables = try {
                HandshakeValidator.extractCrackable(file)
            } catch (e: Exception) {
                println("  Failed to prepare crackable material: ${e.message}\n")
                continue
            }

            if (hc22000Lines != null) {
                for (c in crackables) {
                    try {
                        hc22000Lines += Hc22000Exporter.toHashLines(c, essidOverride)
                    } catch (e: IllegalArgumentException) {
                        println("  Skipped hc22000 export for ${c.bssid}: ${e.message}")
                    }
                }
            }

            if (wordlistFile != null) {
                for (c in crackables) {
                    print("  Cracking ${c.bssid} <-> ${c.station} against $wordlistPath${if (applyRules) " (+rules)" else ""} ... ")
                    System.out.flush()
                    val outcome = try {
                        HandshakeCracker.crack(c, wordlistFile, essidOverride, applyRules = applyRules)
                    } catch (e: IllegalArgumentException) {
                        println("skipped (${e.message})")
                        continue
                    }
                    if (outcome.found) {
                        println("FOUND: \"${outcome.password}\" (via ${outcome.method}, ${outcome.candidatesTried} candidates, ${outcome.elapsedMs} ms)")
                    } else {
                        println("not found (${outcome.candidatesTried} candidates tried, ${outcome.elapsedMs} ms)")
                    }
                }
            }
            println()
        }
    }

    if (hc22000Lines != null) {
        File(hc22000OutPath!!).writeText(hc22000Lines.joinToString("\n") + if (hc22000Lines.isEmpty()) "" else "\n")
        println("Wrote ${hc22000Lines.size} hash line(s) to $hc22000OutPath")
    }
}
