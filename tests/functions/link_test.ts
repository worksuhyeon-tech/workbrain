// FR-06 연결 제안 서버 검증(cleanLinks·linkQuote) 시험. 실행: npx -y deno@2.1.4 test tests/functions/link_test.ts
import { assertEquals } from "jsr:@std/assert@1";
import { cleanLinks, linkQuote } from "../../supabase/functions/process-record/link.ts";

const valid = new Set(["erp", "chat", "rent", "ai"]);
const link = (project_id: string, extra: Record<string, unknown> = {}) =>
  ({ project_id, reason: "보상 ERP 일정을 논의함", segments: [0], confidence: "high", reasons: [], ...extra });

Deno.test("목록에 없는 ID·이미 연결/거절한 곳·중복은 버린다", () => {
  const out = cleanLinks({
    links: [link("unknown"), link("rent"), link("erp", { segments: [1] }), link("erp", { segments: [2], confidence: "low" })],
  }, valid, 5, new Set(["rent"]));
  assertEquals(out.map((l) => l.projectId), ["erp"]);
});

Deno.test("제안은 최대 3개, 근거 구간은 범위 안 최대 5개(모델이 준 중요 순서 그대로)", () => {
  const out = cleanLinks({
    links: ["erp", "chat", "rent", "ai"].map((id) => link(id, { segments: [9, 4, -1, 0, 1, 2, 3, 2], confidence: "medium", reasons: ["brief_mention"] })),
  }, valid, 5, new Set());
  assertEquals(out.length, 3);
  assertEquals(out[0].segments, [4, 0, 1, 2, 3]);
});

Deno.test("근거가 없으면 low + no_evidence", () => {
  const [l] = cleanLinks({ links: [link("erp", { segments: [7] })] }, valid, 5, new Set());
  assertEquals(l.confidence, "low");
  assertEquals(l.reasons, ["no_evidence"]);
});

Deno.test("high면 이유 코드를 비우고, 모르는 코드는 버린다", () => {
  const [a, b] = cleanLinks({
    links: [link("erp", { reasons: ["brief_mention"] }), link("chat", { segments: [1], confidence: "medium", reasons: ["name_not_mentioned", "made_up"] })],
  }, valid, 5, new Set());
  assertEquals(a.reasons, []);
  assertEquals(b.reasons, ["name_not_mentioned"]);
});

Deno.test("확신 값이 이상하면 unrated, 형식이 틀리면 빈 결과", () => {
  const [l] = cleanLinks({ links: [link("erp", { confidence: "certain" })] }, valid, 5, new Set());
  assertEquals(l.confidence, "unrated");
  assertEquals(cleanLinks({ nope: true }, valid, 5, new Set()), []);
  assertEquals(cleanLinks(null, valid, 5, new Set()), []);
});

Deno.test("이유는 한 줄 80자까지, 비면 null", () => {
  const [a, b, c] = cleanLinks({
    links: [
      link("erp", { reason: "  청구독려콜을\n AI상담사로  돌리는 요건을 정함 " }),
      link("chat", { reason: "가".repeat(120) }),
      link("rent", { reason: "   " }),
    ],
  }, valid, 5, new Set());
  assertEquals(a.reason, "청구독려콜을 AI상담사로 돌리는 요건을 정함");
  assertEquals(b.reason?.length, 80);
  assertEquals(c.reason, null);
});

Deno.test("근거 문장은 가장 분명한 첫 구간 하나만 200자까지", () => {
  const segs = [{ idx: 0, text: "예를 들면 열 건이라고 생각해 볼게요." }, { idx: 1, text: " 청구독려콜을 AI상담사가 하도록 요건을 정해요. " }];
  assertEquals(linkQuote(segs, [1, 0]), "청구독려콜을 AI상담사가 하도록 요건을 정해요.");
  assertEquals(linkQuote([{ idx: 0, text: "가".repeat(400) }], [0])?.length, 200);
  assertEquals(linkQuote(segs, []), null);
});
