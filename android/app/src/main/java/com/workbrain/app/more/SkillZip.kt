package com.workbrain.app.more

import com.workbrain.app.data.GuidelineRef
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

/**
 * Claude 스킬 폴더를 압축한 zip에서 회의록 지침(SKILL.md)과 참고 파일(references 폴더의 .md)을 꺼낸다(FR-30 2단계, DEC-56).
 * 디스크에 풀지 않고 메모리에서만 읽는다: 파일 경로 조작(zip slip)이 일어날 수 없고, 가져온 코드(assets/)가 실행될 일도 없다.
 */
object SkillZip {
    /** 서버 wb_guideline_set 상한과 같다. 글자 수는 서버(PostgreSQL length)처럼 코드 포인트로 센다 */
    const val SKILL_LIMIT = 8_000
    const val TOTAL_LIMIT = 12_000

    private const val MAX_ZIP_BYTES = 5 * 1024 * 1024
    private const val MAX_ENTRIES = 200
    private const val MAX_MD_BYTES = 200 * 1024
    // 압축을 풀었을 때의 전체 크기 상한: 작은 zip이 아주 크게 풀리는 경우(zip bomb) 방어
    private const val MAX_INFLATED_BYTES = 30L * 1024 * 1024

    /** 확인 시트에 보여 줄 가져오기 후보. 적용을 누르기 전에는 서버에 아무것도 쓰지 않는다 */
    data class Candidate(val sourceName: String, val skill: String, val refs: List<GuidelineRef>, val ignoredCount: Int)

    sealed interface Result {
        data class Ok(val candidate: Candidate) : Result
        data class Rejected(val reason: String) : Result
    }

    private class Rejection(val reason: String) : Exception(reason)

    private val EUC_KR: Charset = Charset.forName("EUC-KR")

    // 머리말(name/description)은 Claude가 스킬을 고르는 용도라 회의록 지침에 필요 없다
    private val FRONT_MATTER = Regex("""\A---[ \t]*\n(?:.*?\n)?---[ \t]*(?:\n|\z)""", RegexOption.DOT_MATCHES_ALL)

    fun charCount(text: String): Int = text.codePointCount(0, text.length)

    fun read(input: InputStream, sourceName: String): Result {
        val bytes = readCapped(input) ?: return Result.Rejected("zip 파일이 5MB를 넘어요.")
        // 윈도우 구버전 압축 프로그램은 한글 파일 이름을 UTF-8이 아닌 EUC-KR로 저장해, 그때만 한 번 더 시도한다
        for (nameCharset in listOf(Charsets.UTF_8, EUC_KR)) {
            try {
                return Result.Ok(parse(bytes, nameCharset, sourceName))
            } catch (e: Rejection) {
                return Result.Rejected(e.reason)
            } catch (e: IllegalArgumentException) {
                continue
            } catch (e: IOException) {
                return Result.Rejected("zip 파일을 읽지 못했어요. 파일이 손상됐을 수 있어요.")
            }
        }
        return Result.Rejected("zip 안의 파일 이름을 읽지 못했어요.")
    }

    private fun parse(bytes: ByteArray, nameCharset: Charset, sourceName: String): Candidate {
        val markdown = mutableMapOf<String, ByteArray>()
        var fileCount = 0
        var inflated = 0L
        ZipInputStream(ByteArrayInputStream(bytes), nameCharset).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = entry.name.replace('\\', '/')
                if (entry.isDirectory || isJunk(path)) continue
                if (++fileCount > MAX_ENTRIES) throw Rejection("zip 안의 파일이 ${MAX_ENTRIES}개를 넘어요. 스킬 폴더 하나만 압축해 주세요.")
                val isMarkdown = path.endsWith(".md", ignoreCase = true)
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    inflated += n
                    if (inflated > MAX_INFLATED_BYTES) throw Rejection("zip 안의 내용이 너무 커요.")
                    if (isMarkdown) {
                        if (out.size() + n > MAX_MD_BYTES) throw Rejection("${path.substringAfterLast('/')} 파일이 200KB를 넘어요.")
                        out.write(buffer, 0, n)
                    }
                }
                if (isMarkdown) markdown[path] = out.toByteArray()
            }
        }

        val skillPaths = markdown.keys.filter { it.substringAfterLast('/').equals("SKILL.md", ignoreCase = true) }
        if (skillPaths.isEmpty()) throw Rejection("zip 안에 SKILL.md가 없어요.")
        if (skillPaths.size > 1) throw Rejection("SKILL.md가 여러 개예요. 스킬 폴더 하나만 압축해 주세요.")
        val skillPath = skillPaths.single()

        // 참고 파일은 SKILL.md와 같은 폴더의 references/ 바로 아래 .md만. 나머지(assets/ 등)는 개수만 센다
        val folder = skillPath.substringBeforeLast('/', "")
        val refsPrefix = if (folder.isEmpty()) "references/" else "$folder/references/"
        val refPaths = markdown.keys
            .filter { it.startsWith(refsPrefix, ignoreCase = true) && '/' !in it.substring(refsPrefix.length) }
            .sorted()

        val skill = stripFrontMatter(decode(markdown.getValue(skillPath)))
        if (skill.isEmpty()) throw Rejection("SKILL.md 내용이 비어 있어요.")
        val refs = refPaths
            .map { GuidelineRef(it.substringAfterLast('/').takeLast(120), clean(decode(markdown.getValue(it)))) }
            .filter { it.content.isNotEmpty() }

        return Candidate(sourceName, skill, refs, ignoredCount = fileCount - 1 - refs.size)
    }

    private fun readCapped(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_ZIP_BYTES) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    // macOS 압축이 끼워 넣는 메타 파일은 스킬 내용이 아니다
    private fun isJunk(path: String): Boolean =
        path.startsWith("__MACOSX/") || path.substringAfterLast('/').startsWith("._")

    /** 엄격한 UTF-8: 깨진 글자를 조용히 바꿔 넣지 않고 거부한다(EUC-KR 등으로 저장된 .md) */
    private fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        throw Rejection("UTF-8 .md만 가져올 수 있어요.")
    }

    // 서버는 줄바꿈도 글자로 세므로 CRLF를 하나로 맞춰 앱·서버 글자 수가 어긋나지 않게 한다
    private fun clean(text: String): String =
        text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').trim()

    private fun stripFrontMatter(text: String): String = clean(text).replaceFirst(FRONT_MATTER, "").trim()
}
