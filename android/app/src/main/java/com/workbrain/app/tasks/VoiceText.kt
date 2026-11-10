package com.workbrain.app.tasks

/*
 * 말로 넣은 글 다듬기(DEC-89, 순수 함수라 PC에서 시험한다). 음성 인식기는 '#'·'!'·'@' 같은 기호를 글자로 내놓지 않으므로,
 * 빠른 입력 단축어를 말로 부르는 낱말을 기호로 바꿔 QuickParse가 알아보게 한다.
 * 바꾼 결과는 미리보기 칩으로 먼저 보이고 [추가]를 눌러야 저장되므로, 잘못 바뀌어도 사용자가 보고 고칠 수 있다
 */
object VoiceText {
    // 앞뒤가 띄어진 낱말일 때만 바꾼다('해시값'·'네일샵'처럼 낱말 안에 든 것은 그대로)
    private val HASH = Regex("(^|\\s)(?:해시태그|해쉬태그|해시|해쉬|샵|샾)\\s+(?=\\S)")
    private val PRIORITY = Regex("(^|\\s)느낌표\\s*(높음|낮음)(?=\\s|$)")
    private val AT = Regex("(^|\\s)골뱅이\\s+(?=\\S)")
    private val SPACES = Regex("\\s+")

    /** 인식기 글 → 빠른 입력 글: '샵 ○○' → '#○○', '느낌표 높음' → '!높음', '골뱅이 ○○' → '@○○', 끝 마침표 떼기 */
    fun normalize(spoken: String): String = spoken
        .replace(SPACES, " ").trim()
        .replace(HASH) { it.groupValues[1] + "#" }
        .replace(PRIORITY) { it.groupValues[1] + "!" + it.groupValues[2] }
        .replace(AT) { it.groupValues[1] + "@" }
        .trimEnd('.', '。', ' ')

    /** 이미 적은 글이 있으면 뒤에 한 칸 띄어 붙인다(사용자 선택 11/11). 들은 게 비었으면 그대로 */
    fun append(existing: String, spoken: String): String {
        val heard = normalize(spoken)
        return when {
            heard.isEmpty() -> existing
            existing.isBlank() -> heard
            else -> existing.trimEnd() + " " + heard
        }
    }
}
