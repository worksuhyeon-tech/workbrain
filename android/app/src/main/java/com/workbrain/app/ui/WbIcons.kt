package com.workbrain.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 앱 글리프(24×24 선 그림). 글래스 아이콘 앞판과 버튼 안에 쓴다.
 * 아이콘 라이브러리(수 MB)를 넣지 않으려고 필요한 모양만 직접 그렸다.
 */
object WbIcons {
    private fun line(name: String, vararg paths: String, fill: Boolean = false): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = addPathNodes(d),
                    fill = if (fill) SolidColor(Color.Black) else null,
                    stroke = if (fill) null else SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val Home = line("home", "M3 10.5 12 3l9 7.5", "M5 9v11h14V9", "M10 20v-5h4v5")
    // 전달(공유): 클로바 노트 맨 위 줄처럼 오른쪽으로 꺾이는 화살표
    val Share = line("share", "M14 5l6 6-6 6", "M20 11H11a6 6 0 0 0-6 6v2")
    // ⋮ 더보기 메뉴: 점 세 개(채움)
    val More = line(
        "more",
        "M12 3.5a1.75 1.75 0 1 1 0 3.5a1.75 1.75 0 1 1 0-3.5z",
        "M12 10.25a1.75 1.75 0 1 1 0 3.5a1.75 1.75 0 1 1 0-3.5z",
        "M12 17a1.75 1.75 0 1 1 0 3.5a1.75 1.75 0 1 1 0-3.5z",
        fill = true,
    )
    val Folder = line("folder", "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
    val Mic = line("mic", "M9 6a3 3 0 0 1 6 0v5a3 3 0 0 1-6 0z", "M5 11a7 7 0 0 0 14 0", "M12 18v3")
    val Review = line("review", "M6 4h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M8 12l3 3 5-6")
    val Menu = line("menu", "M4 7h16", "M4 12h16", "M4 17h16")
    val Person = line("person", "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8z", "M4 21a8 8 0 0 1 16 0")
    val Flask = line("flask", "M9 3h6", "M10 3v6l-5 9a2 2 0 0 0 1.7 3h10.6a2 2 0 0 0 1.7-3l-5-9V3", "M7.5 15h9")
    val Building = line("building", "M5 21V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v16", "M3 21h18", "M9 7h2", "M13 7h2", "M9 11h2", "M13 11h2", "M10 21v-4h4v4")
    val Ban = line("ban", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z", "M5.6 5.6l12.8 12.8")
    val Sparkle = line("sparkle", "M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z", "M18.5 16.5l0.7 1.8 1.8 0.7-1.8 0.7-0.7 1.8-0.7-1.8-1.8-0.7 1.8-0.7z")
    val Document = line("document", "M7 3h7l5 5v11a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M14 3v5h5", "M9 13h6", "M9 17h6")
    val Coin = line("coin", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z", "M9 9.5l1.5 5 1.5-4 1.5 4 1.5-5", "M8 12h8")
    val Pause = line("pause", "M9 6v12", "M15 6v12")
    val Bookmark = line("bookmark", "M7 4h10a1 1 0 0 1 1 1v15l-6-4-6 4V5a1 1 0 0 1 1-1z")
    val Play = line("play", "M8 5.5v13l10-6.5z", fill = true)
    val Pencil = line("pencil", "M4 20h4L19 9a2.8 2.8 0 0 0-4-4L4 16z", "M13.5 6.5l4 4")
    val Check = line("check", "M5 12.5l4.5 4.5L19 7.5")
    val Trash = line("trash", "M4 7h16", "M9 7V4h6v3", "M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12", "M10 11v6", "M14 11v6")
    // 🔍 검색(FR-13, 목업 S08 돋보기)
    val Search = line("search", "M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z", "M20 20l-4.3-4.3")
    val Close = line("close", "M6 6l12 12", "M18 6L6 18")

    // PARA 트리(FR-02): 칸 펼침, (+) 추가, 반복실행·링크 자료·아카이브 표시
    val Plus = line("plus", "M12 5v14", "M5 12h14")
    val ChevronRight = line("chevron-right", "M9 6l6 6-6 6")
    val ChevronDown = line("chevron-down", "M6 9l6 6 6-6")
    val Repeat = line("repeat", "M17 3l3 3-3 3", "M4 11V9a3 3 0 0 1 3-3h13", "M7 21l-3-3 3-3", "M20 13v2a3 3 0 0 1-3 3H4")
    val Link = line("link", "M10 14a4.5 4.5 0 0 0 6.4 0l3-3a4.5 4.5 0 0 0-6.4-6.4l-1.2 1.2", "M14 10a4.5 4.5 0 0 0-6.4 0l-3 3a4.5 4.5 0 0 0 6.4 6.4l1.2-1.2")
    val Archive = line("archive", "M3 4h18v4H3z", "M5 8v11a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8", "M10 12h4")
    // 목업 🎯 목적 · 🧭 반복실행 설명 · 🗂 할 일 묶음 자리(이모지 대신 선 글리프, Design §6.5)
    val Target = line("target", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z", "M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8z", "M12 12.5a.5.5 0 1 0 0-1 .5.5 0 0 0 0 1z")
    val Compass = line("compass", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z", "M15.5 8.5l-2 5-5 2 2-5z")

    // 빠른 입력 미리보기 칩(목업 📅 기한 · ❗ 우선순위 · ⚠ 경고)
    val Calendar = line("calendar", "M5 5h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M3 10h18", "M8 3v4", "M16 3v4")
    val Flag = line("flag", "M5 21V4", "M5 4h12l-2.5 4.5L17 13H5")
    val Alert = line("alert", "M10.3 4.3 2.6 18a2 2 0 0 0 1.7 3h15.4a2 2 0 0 0 1.7-3L13.7 4.3a2 2 0 0 0-3.4 0z", "M12 9.5v4", "M12 17h.01")
}
