package com.workbrain.app.more

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 용어집 파일 읽기·쓰기. 클로바노트 '단어 다운로드' 엑셀(A열, 첫 줄 "Word")과 주고받을 수 있게 같은 모양을 쓴다.
 * 엑셀 라이브러리(POI)는 앱을 수 MB 키우므로, xlsx(zip + XML)의 첫 시트 A열만 직접 읽는다.
 */
object VocabFile {
    const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    private val HEADERS = setOf("word", "단어")

    /** xlsx면 첫 시트 A열, 그 밖(csv·txt)이면 줄마다 첫 칸. 머리글·빈 칸은 뺀다. 형식 검사는 서버가 한다 */
    fun readTerms(input: InputStream, isXlsx: Boolean): List<String> {
        val raw = if (isXlsx) readXlsxColumnA(input) else input.bufferedReader().readLines().map { it.substringBefore(',') }
        return raw.map { it.trim().trim('"', '﻿') }.filter { it.isNotEmpty() && it.lowercase() !in HEADERS }
    }

    private fun readXlsxColumnA(input: InputStream): List<String> {
        var shared: List<String> = emptyList()
        var sheet: ByteArray? = null
        ZipInputStream(input).use { zip ->
            generateSequence { zip.nextEntry }.forEach { e ->
                when (e.name) {
                    "xl/sharedStrings.xml" -> shared = readSharedStrings(zip.readBytes())
                    "xl/worksheets/sheet1.xml" -> sheet = zip.readBytes()
                }
            }
        }
        val bytes = sheet ?: error("엑셀 첫 시트를 찾지 못했어요")
        val out = mutableListOf<String>()
        val p = parser(bytes)
        var inA = false
        var type: String? = null
        val text = StringBuilder()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> if (p.name == "c") {
                    // 셀 주소가 A1, A2…인 칸만 읽는다
                    inA = p.getAttributeValue(null, "r")?.let { it.startsWith("A") && it.drop(1).all(Char::isDigit) } ?: false
                    type = p.getAttributeValue(null, "t")
                    text.clear()
                }
                XmlPullParser.TEXT -> if (inA) text.append(p.text)
                XmlPullParser.END_TAG -> if (p.name == "c" && inA) {
                    val v = text.toString()
                    out += if (type == "s") shared.getOrNull(v.trim().toIntOrNull() ?: -1).orEmpty() else v
                    inA = false
                }
            }
        }
        return out
    }

    private fun readSharedStrings(bytes: ByteArray): List<String> {
        val out = mutableListOf<String>()
        val p = parser(bytes)
        val text = StringBuilder()
        var inSi = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> if (p.name == "si") { inSi = true; text.clear() }
                XmlPullParser.TEXT -> if (inSi) text.append(p.text)
                XmlPullParser.END_TAG -> if (p.name == "si") { out += text.toString(); inSi = false }
            }
        }
        return out
    }

    private fun parser(bytes: ByteArray): XmlPullParser =
        Xml.newPullParser().apply { setInput(bytes.inputStream(), "UTF-8") }

    /** 클로바노트에 다시 올릴 수 있는 최소 xlsx: 시트 하나, A1 "Word", 아래로 단어 */
    fun writeXlsx(out: OutputStream, terms: List<String>) {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val rows = (listOf("Word") + terms).mapIndexed { i, t ->
            "<row r=\"${i + 1}\"><c r=\"A${i + 1}\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(t)}</t></is></c></row>"
        }.joinToString("")
        val files = linkedMapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$rows</sheetData></worksheet>""",
        )
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
