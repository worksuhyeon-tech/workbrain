// 할 일 정리 입력·출력 검증(DEC-76 화자 이름, DEC-77 받는 사람) 시험. 실행: npx -y deno@2.1.4 test tests/functions/extract_test.ts
import { assertEquals } from "jsr:@std/assert@1";
import { buildExtractInput, cleanRecipients, resolveOwner, speakerLabel, transcriptLine } from "../../supabase/functions/process-record/extract.ts";

const me = new Set(["김하늘"]);
const line = (text: string, speaker?: string) => ({ idx: 0, text, speaker });

Deno.test("화자 이름: 번호 화자는 없음, '(나)'·내 이름은 나, '님'은 뗀다", () => {
  assertEquals(speakerLabel("참석자 3", me), null);
  assertEquals(speakerLabel("발화자 1", me), null);
  assertEquals(speakerLabel("하늘 김 (나)", me), "나");
  assertEquals(speakerLabel("김하늘", me), "나");
  assertEquals(speakerLabel("이도윤프로님", me), "이도윤프로");
  assertEquals(speakerLabel("협력사 NW담당", me), "협력사 NW담당");
  assertEquals(speakerLabel("  ", me), null);
  assertEquals(speakerLabel(undefined, me), null);
});

Deno.test("전사 줄: 이름을 알면 (이름)을 붙이고 번호 화자·화자 없음은 문장만", () => {
  assertEquals(transcriptLine({ idx: 3, text: "제가 할게요", speaker: "박지훈프로" }, me), "[3] (박지훈프로) 제가 할게요");
  assertEquals(transcriptLine({ idx: 4, text: "네", speaker: "참석자 2" }, me), "[4] 네");
  assertEquals(transcriptLine({ idx: 5, text: "공유할게요", speaker: "하늘 김 (나)" }, me), "[5] (나) 공유할게요");
  assertEquals(transcriptLine({ idx: 6, text: "좋아요" }, me), "[6] 좋아요");
});

Deno.test("이름 붙은 화자의 '제가 할게요'는 그 사람, 말한 사람 기준 표시", () => {
  const ev = [line("운영계 IP는 확정되면 제가 메일로 보내 드릴게요", "협력사 NW담당")];
  assertEquals(resolveOwner({ owner_kind: "other", assignee: "협력사 NW담당" }, "meeting", ev, me),
    { owner_kind: "other", assignee: "협력사 NW담당", bySpeaker: true });
});

Deno.test("이름이 불려 맡은 일은 말한 사람 기준이 아니다", () => {
  const ev = [line("박서연 님이 단가표 정리해 주세요", "박지훈프로")];
  assertEquals(resolveOwner({ owner_kind: "other", assignee: "박서연 님" }, "meeting", ev, me),
    { owner_kind: "other", assignee: "박서연", bySpeaker: false });
});

Deno.test("회의에서 '나'는 (나) 줄일 때만, 아니면 확인 필요(DEC-27)", () => {
  const mine = [line("회의록은 제가 공유할게요", "하늘 김 (나)")];
  const nobody = [line("회의록은 제가 공유할게요")];
  assertEquals(resolveOwner({ owner_kind: "me", assignee: null }, "meeting", mine, me), { owner_kind: "me", assignee: null, bySpeaker: true });
  assertEquals(resolveOwner({ owner_kind: "me", assignee: null }, "meeting", nobody, me).owner_kind, "unknown");
  assertEquals(resolveOwner({ owner_kind: "other", assignee: "나" }, "call", nobody, me).owner_kind, "unknown");
  assertEquals(resolveOwner({ owner_kind: "me", assignee: null }, "memo", nobody, me).owner_kind, "me");
});

Deno.test("내 이름으로 불러 맡긴 일은 화자와 상관없이 나(DEC-49)", () => {
  const ev = [line("김하늘 님이 회의록 정리해 주세요", "박지훈프로")];
  assertEquals(resolveOwner({ owner_kind: "other", assignee: "김하늘" }, "meeting", ev, me), { owner_kind: "me", assignee: null, bySpeaker: false });
});

Deno.test("담당 이름이 비었거나 알 수 없는 종류면 확인 필요", () => {
  assertEquals(resolveOwner({ owner_kind: "other", assignee: "  " }, "meeting", [line("x")], me).owner_kind, "unknown");
  assertEquals(resolveOwner({ owner_kind: "boss", assignee: "x" }, "meeting", [line("x")], me).owner_kind, "unknown");
});

Deno.test("받는 사람: 글자만·'님' 떼기·내 이름은 나·중복 없이 최대 5명", () => {
  assertEquals(cleanRecipients(["박서연 님", "박서연", " ", 3, "김하늘", "참석자들", "A", "B", "C", "D"], me), ["박서연", "나", "참석자들", "A", "B"]);
  assertEquals(cleanRecipients(null, me), []);
  assertEquals(cleanRecipients("박서연", me), []);
});

Deno.test("입력: 녹음 시각은 서울 날짜·요일, 화자 이름 줄", () => {
  const text = buildExtractInput({
    recordKind: "meeting", occurredAt: new Date("2026-11-09T01:30:00Z"), places: [{ line: "- p1: ERP (프로젝트)" }],
    attendees: [], vocab: ["지급심사"], segments: [{ idx: 0, text: "제가 할게요", speaker: "박서연" }], myNames: me,
  });
  assertEquals(text.split("\n")[1], "녹음 시각: 2026-11-09 (월) 10:30 Asia/Seoul");
  assertEquals(text.includes("참석자("), false);
  assertEquals(text.endsWith("전사:\n[0] (박서연) 제가 할게요"), true);
});
