package com.niftysignal.ai

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.zip.ZipInputStream

data class OptionChainStrike(
    val strike: Double,
    val callOi: Double?,
    val callOiChange: Double?,
    val callVolume: Double?,
    val callLtp: Double?,
    val putOi: Double?,
    val putOiChange: Double?,
    val putVolume: Double?,
    val putLtp: Double?
)

data class OptionChainAnalysis(
    val strikes: List<OptionChainStrike>,
    val totalCallOi: Double,
    val totalPutOi: Double,
    val pcr: Double?,
    val maxCallOiStrike: Double?,
    val maxPutOiStrike: Double?,
    val maxCallAdditionStrike: Double?,
    val maxPutAdditionStrike: Double?,
    val totalRows: Int,
    val warnings: List<String>
) {
    fun asReport(): String = buildString {
        appendLine("OFFLINE OPTION CHAIN TEST • XLSX")
        appendLine("Valid strike rows: $totalRows")
        appendLine("Total CE OI: ${totalCallOi.toLong()}")
        appendLine("Total PE OI: ${totalPutOi.toLong()}")
        appendLine("PCR (PE OI / CE OI): ${pcr?.let { String.format(java.util.Locale.US, "%.3f", it) } ?: "N/A"}")
        appendLine("Highest CE OI strike: ${maxCallOiStrike?.let(::fmt) ?: "N/A"}")
        appendLine("Highest PE OI strike: ${maxPutOiStrike?.let(::fmt) ?: "N/A"}")
        appendLine("Highest positive CE OI change: ${maxCallAdditionStrike?.let(::fmt) ?: "N/A"}")
        appendLine("Highest positive PE OI change: ${maxPutAdditionStrike?.let(::fmt) ?: "N/A"}")
        appendLine()
        appendLine("Interpretation: OI concentrations are snapshot observations, not guaranteed support/resistance or trade signals.")
        appendLine("This workbook has no verified underlying spot price or snapshot timestamp; those fields are not inferred.")
        appendLine("One snapshot cannot measure AI signal accuracy or win rate. Repeated timestamped snapshots plus subsequent NIFTY prices are required.")
        if (warnings.isNotEmpty()) {
            appendLine()
            appendLine("DATA NOTES")
            warnings.forEach { appendLine("• $it") }
        }
    }

    private fun fmt(value: Double) = String.format(java.util.Locale.US, "%.0f", value)
}

object OptionChainXlsxParser {
    fun parse(input: InputStream): OptionChainAnalysis {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && (entry.name == "xl/sharedStrings.xml" || entry.name == "xl/worksheets/sheet1.xml")) {
                    entries[entry.name] = zip.readBytes()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val sheet = entries["xl/worksheets/sheet1.xml"]
            ?: throw IllegalArgumentException("First worksheet was not found in this XLSX file.")
        val shared = entries["xl/sharedStrings.xml"]?.let(::readSharedStrings) ?: emptyList()
        val rows = readSheet(sheet, shared)
        val header = rows[2] ?: throw IllegalArgumentException("Option-chain header row is missing.")
        if (header[12]?.equals("STRIKE", true) != true ||
            header[2]?.contains("CHNG IN OI", true) != true ||
            header[22]?.equals("OI", true) != true
        ) {
            throw IllegalArgumentException("This worksheet does not match the supported NIFTY option-chain column layout.")
        }

        val warnings = mutableListOf<String>()
        val data = rows.filterKeys { it >= 3 }.mapNotNull { (_, cells) ->
            val strike = cells[12]?.numberOrNull() ?: return@mapNotNull null
            OptionChainStrike(
                strike = strike,
                callOi = cells[2]?.numberOrNull(),
                callOiChange = cells[3]?.numberOrNull(),
                callVolume = cells[4]?.numberOrNull(),
                callLtp = cells[6]?.numberOrNull(),
                putOi = cells[22]?.numberOrNull(),
                putOiChange = cells[21]?.numberOrNull(),
                putVolume = cells[20]?.numberOrNull(),
                putLtp = cells[18]?.numberOrNull()
            )
        }.sortedBy { it.strike }

        if (data.isEmpty()) throw IllegalArgumentException("No valid strike rows were found.")
        if (data.any { it.callOi == null || it.putOi == null }) warnings += "Some CE/PE OI cells are blank or marked '-'; totals include available numeric values only."
        val callTotal = data.sumOf { it.callOi ?: 0.0 }
        val putTotal = data.sumOf { it.putOi ?: 0.0 }
        if (data.any { it.callOiChange == null || it.putOiChange == null }) warnings += "Some OI-change cells are unavailable; addition leaders use available numeric values only."
        return OptionChainAnalysis(
            strikes = data,
            totalCallOi = callTotal,
            totalPutOi = putTotal,
            pcr = if (callTotal > 0.0) putTotal / callTotal else null,
            maxCallOiStrike = data.filter { it.callOi != null }.maxByOrNull { it.callOi!! }?.strike,
            maxPutOiStrike = data.filter { it.putOi != null }.maxByOrNull { it.putOi!! }?.strike,
            maxCallAdditionStrike = data.filter { (it.callOiChange ?: 0.0) > 0.0 }.maxByOrNull { it.callOiChange!! }?.strike,
            maxPutAdditionStrike = data.filter { (it.putOiChange ?: 0.0) > 0.0 }.maxByOrNull { it.putOiChange!! }?.strike,
            totalRows = data.size,
            warnings = warnings
        )
    }

    private fun String.numberOrNull(): Double? = trim().replace(",", "").takeIf { it.isNotEmpty() && it != "-" && it != "—" }?.toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun readSharedStrings(bytes: ByteArray): List<String> {
        val parser = newParser(bytes)
        val values = mutableListOf<String>()
        var inSi = false
        var current = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> if (parser.name == "si") { inSi = true; current = StringBuilder() }
                XmlPullParser.TEXT -> if (inSi) current.append(parser.text)
                XmlPullParser.END_TAG -> if (parser.name == "si") { values += current.toString(); inSi = false }
            }
            event = parser.next()
        }
        return values
    }

    private fun readSheet(bytes: ByteArray, shared: List<String>): Map<Int, Map<Int, String>> {
        val parser = newParser(bytes)
        val rows = linkedMapOf<Int, Map<Int, String>>()
        var rowIndex = -1
        var cells = linkedMapOf<Int, String>()
        var column = -1
        var type: String? = null
        var value = StringBuilder()
        var inValue = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> { rowIndex = (parser.getAttributeValue(null, "r")?.toIntOrNull() ?: 0) - 1; cells = linkedMapOf() }
                    "c" -> {
                        column = columnIndex(parser.getAttributeValue(null, "r") ?: "")
                        type = parser.getAttributeValue(null, "t")
                    }
                    "v", "t" -> { value = StringBuilder(); inValue = true }
                }
                XmlPullParser.TEXT -> if (inValue) value.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v", "t" -> {
                        val raw = value.toString()
                        if (column >= 0) cells[column] = if (type == "s") shared.getOrNull(raw.toIntOrNull() ?: -1) ?: "" else raw
                        inValue = false
                    }
                    "c" -> { column = -1; type = null }
                    "row" -> if (rowIndex >= 0) rows[rowIndex] = cells
                }
            }
            event = parser.next()
        }
        return rows
    }

    private fun columnIndex(reference: String): Int {
        val letters = reference.takeWhile { it.isLetter() }.uppercase()
        var result = 0
        letters.forEach { result = result * 26 + (it - 'A' + 1) }
        return result - 1
    }

    private fun newParser(bytes: ByteArray): XmlPullParser =
        XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(bytes.inputStream(), "UTF-8")
        }
}
