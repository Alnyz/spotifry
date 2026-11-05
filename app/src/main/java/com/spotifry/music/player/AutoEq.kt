// Index reading, search, percent decoding, the measurement file reader and the result label are adapted from Mochy Music
// (GPL-3.0), see licenses/Mochy-Music-NOTICE.txt.
// The measurements are from the AutoEq project (MIT, https://github.com/jaakkopasanen/AutoEq).

package com.spotifry.music.player

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The name starts the saved equalizer name of a headphone correction, so the page can tell it from a preset or the user's own curve. */
const val AUTOEQ_PREFIX = "AutoEq: "

/** A download or file that can't give a measurement: not there, too big, or not readable. Not a connection problem. */
class AutoEqBadFile(message: String) : java.io.IOException(message)

/** One measured result: a headphone as one reviewer measured it, [path] being its folder in AutoEq's results. */
data class AutoEqEntry(val name: String, val source: String, val rig: String?, val path: String) {
    val sourceLabel get() = if (rig != null) "$source · $rig" else source
    /** The name as typed for a search, worked out once so searching a long list stays quick. */
    internal val key = normalizeName(name)
    internal val compactKey = key.replace(" ", "")
}

/** A headphone's measured response ([raw]) and the curve it should have ([target]), in dB at [hz]. */
class AutoEqCurve(val hz: DoubleArray, val raw: DoubleArray, val target: DoubleArray)

/**
 * The ten bands that bend a measurement toward its target. The error is the typical gap in dB between target and
 * corrected sound over 20 Hz to 16 kHz, with the level of the middle (250 Hz to 2.5 kHz) set aside because the volume is
 * the preamp's job. Ten bands an octave apart can't follow a narrow dip, so this stays above zero. [peakBoostDb] is how
 * much the sound is turned down overall so the boost can't clip.
 */
class AutoEqFit(val levelsMb: List<Int>, val errorBeforeDb: Double, val errorAfterDb: Double, val peakBoostDb: Double)

private const val FIT_FROM_HZ = 20.0
private const val FIT_TO_HZ = 16_000.0
/** Past this the measuring rig is as much of the reading as the headphone, so it counts half. */
private const val RELIABLE_TO_HZ = 10_000.0
/** More boost than this turns the whole song down too much, so the fit stops here; cuts can go further. */
private const val MAX_BOOST_DB = 8.0
private const val MAX_CUT_DB = EQ_MAX_MB / 100.0
private const val FIT_ROUNDS = 40
private const val FIT_STEP = .5

/** Fits [curve] onto the ten bands of [EqDesign], so the error is measured on the real filters, not a model of them. */
fun fitAutoEq(curve: AutoEqCurve): AutoEqFit {
    val used = curve.hz.indices.filter { curve.hz[it] in FIT_FROM_HZ..FIT_TO_HZ && curve.raw[it].isFinite() && curve.target[it].isFinite() }
    require(used.size >= 20) { "too few points to fit" }
    val hz = DoubleArray(used.size) { curve.hz[used[it]] }
    val weight = DoubleArray(hz.size) { if (hz[it] <= RELIABLE_TO_HZ) 1.0 else .5 }
    val mids = hz.indices.filter { hz[it] in 250.0..2500.0 }
    val wanted = DoubleArray(hz.size) { curve.target[used[it]] - curve.raw[used[it]] }
    wanted.mean(mids).let { mid -> for (i in wanted.indices) wanted[i] -= mid }

    /** The weighted average of [v] within an octave of a band's centre, nearest counting most. */
    fun around(v: DoubleArray, band: Int): Double {
        var sum = 0.0; var total = 0.0
        for (j in hz.indices) {
            val w = (1 - abs(log2(hz[j] / EQ_CENTERS_HZ[band]))) * weight[j]
            if (w > 0) { sum += w * v[j]; total += w }
        }
        return if (total > 0) sum / total else 0.0
    }

    /** What these levels leave of the wanted curve, mid level set aside. */
    fun gap(levelsMb: List<Int>): DoubleArray {
        val design = EqDesign(levelsMb)
        val g = DoubleArray(hz.size) { wanted[it] - design.responseDb(hz[it]) }
        val mid = g.mean(mids)
        return DoubleArray(g.size) { g[it] - mid }
    }
    fun rms(g: DoubleArray): Double {
        var sum = 0.0; var total = 0.0
        for (j in g.indices) { sum += weight[j] * g[j] * g[j]; total += weight[j] }
        return sqrt(sum / total)
    }
    fun rounded(x: DoubleArray) = x.map { ((it * 100) / 50).roundToInt() * 50 }

    val x = DoubleArray(EQ_BANDS) { around(wanted, it).coerceIn(-MAX_CUT_DB, MAX_BOOST_DB) }
    var best = rounded(x)
    var bestGap = gap(best)
    var bestRms = rms(bestGap)
    val before = rms(wanted)
    var g = bestGap
    repeat(FIT_ROUNDS) {
        for (b in 0 until EQ_BANDS) x[b] = (x[b] + FIT_STEP * around(g, b)).coerceIn(-MAX_CUT_DB, MAX_BOOST_DB)
        val levels = rounded(x)
        g = gap(levels)
        val r = rms(g)
        if (r < bestRms) { best = levels; bestRms = r }
    }
    return AutoEqFit(best, before, bestRms, -20 * log10(EqDesign(best).preamp))
}

private fun DoubleArray.mean(at: List<Int>) = if (at.isEmpty()) 0.0 else at.sumOf { this[it] } / at.size
private fun log2(x: Double) = ln(x) / ln(2.0)

/**
 * Every result in AutoEq's INDEX.md: one line each, `- [name](./source/rig/name) by source on rig`. The name can hold
 * brackets. Results by crinacle are left out: their folders have no `<name>.csv`, only the filter files.
 */
fun parseAutoEqIndex(text: String): List<AutoEqEntry> = text.lineSequence().mapNotNull { line ->
    if (!line.startsWith("- [")) return@mapNotNull null
    val nameEnd = line.indexOf("](./").takeIf { it >= 0 } ?: return@mapNotNull null
    val pathEnd = line.indexOf(") by ", nameEnd).takeIf { it >= 0 } ?: return@mapNotNull null
    val by = line.substring(pathEnd + 5).trim()
    val on = by.indexOf(" on ")
    AutoEqEntry(
        line.substring(3, nameEnd), if (on >= 0) by.substring(0, on) else by, if (on >= 0) by.substring(on + 4) else null,
        percentDecode(line.substring(nameEnd + 4, pathEnd)),
    )
}.filter { it.source != "crinacle" }.toList()

/** A measurement file: a header naming `frequency`, `raw` and `target` columns, then one row per frequency. */
fun parseAutoEqCsv(csv: String): AutoEqCurve {
    val lines = csv.lineSequence().filter { it.isNotBlank() }.toList()
    val head = lines.firstOrNull()?.split(',')?.map { it.trim().lowercase() } ?: emptyList()
    val f = head.indexOf("frequency"); val r = head.indexOf("raw"); val t = head.indexOf("target")
    if (f < 0 || r < 0 || t < 0) throw AutoEqBadFile("This measurement can't be read")
    val rows = lines.drop(1).mapNotNull { line ->
        val c = line.split(',')
        listOf(f, r, t).map { c.getOrNull(it)?.toDoubleOrNull() ?: return@mapNotNull null }
    }
    return AutoEqCurve(DoubleArray(rows.size) { rows[it][0] }, DoubleArray(rows.size) { rows[it][1] }, DoubleArray(rows.size) { rows[it][2] })
}

/** Entries whose name has every word typed. Punctuation and spaces don't matter: "wh1000" finds "WH-1000XM5". Names that start with it come first. */
fun searchAutoEq(all: List<AutoEqEntry>, query: String): List<AutoEqEntry> {
    val q = normalizeName(query)
    if (q.isEmpty()) return emptyList()
    val words = q.split(' ')
    val compact = q.replace(" ", "")
    // a name where one of the words begins what was typed ("hd600" in "Sennheiser HD 600") comes before one that only contains it
    fun startsAtWord(n: String) = n.indices.any { (it == 0 || n[it - 1] == ' ') && n.regionMatchesCompact(it, compact) }
    return all.filter { e -> words.all { e.key.contains(it) } || e.compactKey.contains(compact) }
        .sortedBy { if (startsAtWord(it.key)) 0 else 1 }
}

/** Whether [compact] is what the letters and digits of this text read from [from], spaces left out. */
private fun String.regionMatchesCompact(from: Int, compact: String): Boolean {
    var k = 0
    var i = from
    while (k < compact.length && i < length) {
        if (this[i] != ' ') { if (this[i] != compact[k]) return false; k++ }
        i++
    }
    return k == compact.length
}

/** Lowercase letters and digits; any run of anything else becomes one space. */
internal fun normalizeName(text: String): String {
    val out = StringBuilder(text.length)
    var space = true
    for (ch in text) {
        if (ch.isLetterOrDigit()) { out.append(ch.lowercaseChar()); space = false }
        else if (!space) { out.append(' '); space = true }
    }
    return out.trimEnd().toString()
}

/** Percent escapes back to text. A plus stays a plus, unlike `URLDecoder`. */
internal fun percentDecode(text: String): String {
    if ('%' !in text) return text
    val bytes = java.io.ByteArrayOutputStream(text.length)
    var i = 0
    while (i < text.length) {
        val hex = if (text[i] == '%' && i + 2 < text.length) text.substring(i + 1, i + 3).toIntOrNull(16) else null
        if (hex != null) { bytes.write(hex); i += 3 } else { bytes.write(text[i].toString().toByteArray()); i++ }
    }
    return bytes.toString(Charsets.UTF_8.name())
}
