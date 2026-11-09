package com.workbrain.app.tasks

import com.workbrain.app.data.TaskRow
import com.workbrain.app.data.usedNamesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** DEC-77 받는 사람: 할 일 줄 칩 글자와 고르기 칩 후보 순서 */
class RecipientsTest {
    private fun task(owner: String = "unknown", assignee: String? = null, recipients: List<String> = emptyList()) =
        TaskRow(id = "t", title = "x", status = "open", version = 1, ownerKind = owner, assignee = assignee, recipients = recipients)

    @Test fun `칩 글자 - 없으면 안 보이고 둘 이상이면 외 N`() {
        assertNull(recipientsLabel(emptyList()))
        assertEquals("→ 박서연", recipientsLabel(listOf("박서연")))
        assertEquals("→ 박서연 외 2", recipientsLabel(listOf("박서연", "협력사", "나")))
    }

    @Test fun `고르기 후보 - 지금 값, 나, 기록 사람 순으로 같은 이름은 한 번, 쓴 이름은 기록 사람이 없을 때만`() {
        assertEquals(
            listOf("박서연", "나", "이도윤"),
            recipientChoices(listOf("박서연"), listOf("이도윤", "박서연 님", " "), listOf("협력사", "이도윤")),
        )
        assertEquals(listOf("나", "협력사", "이도윤"), recipientChoices(emptyList(), emptyList(), listOf("협력사", "이도윤")))
    }

    @Test fun `쓴 이름 - 팔로업 담당과 받는 사람을 자주 쓴 순으로, 나는 뺀다`() {
        val tasks = listOf(
            task(owner = "other", assignee = "이도윤"),
            task(recipients = listOf("협력사", "나")),
            task(owner = "other", assignee = "협력사"),
            task(owner = "unknown", assignee = "무시"),
        )
        assertEquals(listOf("협력사", "이도윤"), usedNamesOf(tasks))
    }
}
