// FR-09 현재 상태 제안 서버 검증(cleanState·basisOf·buildStateInput) 시험. 실행: npx -y deno@2.1.4 test tests/functions/state_test.ts
import { assert, assertEquals } from "jsr:@std/assert@1";
import { basisOf, buildStateInput, cleanState } from "../../supabase/functions/process-record/state.ts";

const now = { summary: "이관 범위를 정하는 중.", status: "active" as const };
const out = (extra: Record<string, unknown> = {}) =>
  ({ changed: true, summary: "12건 중 8건 이관 끝. 남은 4건은 정산 규칙 확정 대기 중.", status: "paused", reason: "남은 4건이 대기하게 됨", segments: [1, 0], ...extra });

Deno.test("바뀐 게 없으면(changed=false·같은 요약과 상태) 제안을 만들지 않는다", () => {
  assertEquals(cleanState(out({ changed: false }), 3, now, false), null);
  assertEquals(cleanState(out({ summary: " 이관 범위를  정하는 중. ", status: "active" }), 3, now, false), null);
  assertEquals(cleanState(null, 3, now, false), null);
});

Deno.test("요약은 띄어쓰기를 모으고 400자까지, 근거는 범위 안 5개까지 모델 순서대로", () => {
  const c = cleanState(out({ summary: "  가 \n 나  " + "다".repeat(500), segments: [2, 9, -1, 2, 0, 1, 1.5] }), 3, now, false)!;
  assert(c.summary.startsWith("가 나 다"));
  assertEquals(c.summary.length, 400);
  assertEquals(c.segments, [2, 0, 1]);
  assertEquals(c.status, "paused");
});

Deno.test("근거가 하나도 없거나 요약이 비면 제안을 만들지 않는다", () => {
  assertEquals(cleanState(out({ segments: [7] }), 3, now, false), null);
  assertEquals(cleanState(out({ summary: "   " }), 3, now, false), null);
});

Deno.test("모르는 상태는 지금 상태(없으면 진행 중), 과거 회의면 상태를 바꾸지 않는다", () => {
  assertEquals(cleanState(out({ status: "waiting" }), 3, now, false)!.status, "active");
  assertEquals(cleanState(out({ status: "waiting" }), 3, null, false)!.status, "active");
  const past = cleanState(out({ summary: "이관 범위를 정하는 중. 협력사 정산 화면은 검토 끝.", status: "done" }), 3, now, true)!;
  assertEquals(past.status, "active");
});

Deno.test("이유가 비면 기본 문구, 80자까지", () => {
  assertEquals(cleanState(out({ reason: " " }), 3, now, false)!.reason, "회의 내용으로 현재 상태를 고침");
  assertEquals(cleanState(out({ reason: "가".repeat(100) }), 3, now, false)!.reason.length, 80);
});

Deno.test("과거 회의: 근거 시각보다 예전이면 past, 근거 시각은 뒤로 가지 않는다", () => {
  assertEquals(basisOf("2026-11-05T10:00:00+09:00", null), { past: false, basis: "2026-11-05T01:00:00.000Z" });
  assertEquals(basisOf("2026-11-10T10:00:00+09:00", "2026-11-05T01:00:00Z"), { past: false, basis: "2026-11-10T01:00:00.000Z" });
  assertEquals(basisOf("2026-10-20T10:00:00+09:00", "2026-11-05T01:00:00Z"), { past: true, basis: "2026-11-05T01:00:00.000Z" });
});

Deno.test("입력에 지금 상태·대기 제안·과거 표시·화자가 들어간다", () => {
  const input = buildStateInput({
    project: { name: "CRM) 이관 12건", purpose: "옛 고객관리 자료를 새 시스템으로 옮김" },
    current: now, pending: { summary: "8건 이관 끝.", status: "active" },
    record: { title: "합성 이관 점검 회의", at: "2026-10-20T01:00:00Z", kind: "meeting" }, past: true,
    segments: [{ idx: 0, text: "8건은 끝났어요.", speaker: "박지훈" }, { idx: 1, text: "남은 건 멈춰요." }],
  });
  assert(input.includes("지금 상태: 이관 범위를 정하는 중. (상태: 진행 중)"));
  assert(input.includes("검토 대기 중인 제안: 8건 이관 끝. (상태: 진행 중)"));
  assert(input.includes("'과거 회의'"));
  assert(input.includes("[0] (박지훈) 8건은 끝났어요."));
  assert(input.includes("[1] 남은 건 멈춰요."));
  const fresh = buildStateInput({
    project: { name: "새 프로젝트" }, current: null, pending: null,
    record: { title: null, at: "2026-11-10T01:00:00Z", kind: "call" }, past: false, segments: [{ idx: 0, text: "시작합니다." }],
  });
  assert(fresh.includes("지금 상태: 없음"));
  assert(!fresh.includes("과거 회의"));
});
