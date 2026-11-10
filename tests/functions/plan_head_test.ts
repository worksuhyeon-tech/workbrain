// 업무계획 □ 줄 AI 다듬기 검증(FR-33, DEC-88) + 앱이 돌려보낸 계획 모양 검사 시험
import { assertEquals } from "jsr:@std/assert@1";
import { buildHeadInput, cleanHeads } from "../../supabase/functions/process-record/plan-head.ts";
import { buildWorkPlan, checkPlan, headInputs } from "../../supabase/functions/_shared/work-plan.ts";
import { SAMPLE_INPUT } from "./work_plan_fixture.ts";

const inputs = headInputs(buildWorkPlan(SAMPLE_INPUT), SAMPLE_INPUT.places);

Deno.test("입력: 기간 + 곳마다 [id] 이름·현재 상태(없으면 없음)·그 기간 줄", () => {
  const text = buildHeadInput("전주 (11.9 ~ 11.13)", inputs);
  assertEquals(text.startsWith("기간: 전주 (11.9 ~ 11.13)\n\n[p-mobile] 이름: 모바일 보상 접수 개편\n현재 상태: 없음\n그 기간 줄:\n- 접수 화면 1차 리뷰 (11/12 회의)"), true);
  assertEquals(text.includes("[p-crm] 이름: CRM 이관 12건\n현재 상태: 12건 중 8건 이관 끝."), true);
});

Deno.test("검증: 받은 id만·한 번만, 띄어쓰기 모음·끝 마침표 뺌", () => {
  const heads = cleanHeads({
    heads: [
      { id: "p-crm", text: "  CRM 이관 12건 중   8건 이관 완료. " },
      { id: "p-crm", text: "두 번째는 버림" },
      { id: "없는-id", text: "버림" },
      { id: "a-ops", text: "보상 운영 주간 지급 현황 정리 완료" },
    ],
  }, inputs);
  assertEquals([...heads.entries()], [["p-crm", "CRM 이관 12건 중 8건 이관 완료"], ["a-ops", "보상 운영 주간 지급 현황 정리 완료"]]);
});

Deno.test("검증: 입력에 없는 숫자(계산한 %·지어낸 건수)는 버려 규칙 줄이 남는다", () => {
  const heads = cleanHeads({
    heads: [
      { id: "p-crm", text: "CRM 이관 67% 완료" },                   // 8/12를 계산한 숫자
      { id: "p-mobile", text: "모바일 보상 접수 개편 3건 리뷰 완료" },   // 지어낸 건수
      { id: "p-old", text: "콜센터 연동 11/9 운영 이관 완료" },         // 입력에 있는 날짜는 됨
    ],
  }, inputs);
  assertEquals([...heads.keys()], ["p-old"]);
});

Deno.test("검증: 60자 넘으면·빈 글·모양이 다르면 버림", () => {
  assertEquals(cleanHeads({ heads: [{ id: "p-crm", text: "가".repeat(61) }, { id: "a-ops", text: "  " }] }, inputs).size, 0);
  assertEquals(cleanHeads(null, inputs).size, 0);
  assertEquals(cleanHeads({ heads: "x" }, inputs).size, 0);
});

Deno.test("앱이 돌려보낸 계획: 맞는 모양은 통과(placeId 같은 덧붙은 칸은 버림), 어긋나면 null", () => {
  const plan = buildWorkPlan(SAMPLE_INPUT);
  const ok = checkPlan(JSON.parse(JSON.stringify(plan)))!;
  assertEquals(ok.previous.lines[0], { level: "head", text: plan.previous.lines[0].text });
  assertEquals(checkPlan({ ...plan, kind: "daily" }), null);
  assertEquals(checkPlan({ ...plan, previous: { ...plan.previous, lines: [{ level: "bold", text: "x" }] } }), null);
  assertEquals(checkPlan({ ...plan, next: { ...plan.next, lines: Array(501).fill({ level: "item", text: "x" }) } }), null);
  assertEquals(checkPlan({ ...plan, ownerName: "가".repeat(41) }), null);
  assertEquals(checkPlan(null), null);
});
