// 업무계획 규칙(FR-33, Design §3.13) 시험: npx -y deno test -A tests/functions/
import { assertEquals } from "jsr:@std/assert@1";
import {
  applyHeads, buildWorkPlan, fallbackHead, firstSentence, headInputs, periods, type PlanInput,
} from "../../supabase/functions/_shared/work-plan.ts";
import { renderPlanDocx } from "../../supabase/functions/_shared/plan-docx.ts";
import { SAMPLE_INPUT } from "./work_plan_fixture.ts";

// 괄호 안 줄바꿈 없는 공백(NBSP)은 읽기 쉽게 보통 띄어쓰기로 바꿔 비교한다(따로 시험함)
const texts = (lines: { level: string; text: string }[]) =>
  lines.map((l) => `${l.level === "head" ? "□" : l.level === "item" ? "-" : "*"} ${l.text.replace(/\u00A0/g, " ")}`);

Deno.test("주간 기간: 화요일 기준 전주 = 이번 주, 금주 = 다음 주(머리는 월~금, 포함은 일요일까지)", () => {
  const p = periods("weekly", "2026-11-10");
  assertEquals([p.previous.label, p.previous.start, p.previous.end], ["전주 (11.9 ~ 11.13)", "2026-11-09", "2026-11-15"]);
  assertEquals([p.next.label, p.next.start, p.next.end], ["금주 (11.16 ~ 11.20)", "2026-11-16", "2026-11-22"]);
  // 일요일·월요일도 그 주로(일요일은 끝나 가는 주)
  assertEquals(periods("weekly", "2026-11-15").previous.start, "2026-11-09");
  assertEquals(periods("weekly", "2026-11-09").previous.start, "2026-11-09");
  // 달·해 넘김
  assertEquals(periods("weekly", "2026-12-30").next.label, "금주 (1.4 ~ 1.8)");
});

Deno.test("월간 기간: 전월 = 이번 달, 금월 = 다음 달(말일·해 넘김)", () => {
  const p = periods("monthly", "2026-11-24");
  assertEquals([p.previous.label, p.previous.end, p.next.label], ["전월 (11.1 ~ 11.30)", "2026-11-30", "금월 (12.1 ~ 12.31)"]);
  assertEquals(periods("monthly", "2026-12-29").next.label, "금월 (1.1 ~ 1.31)");
  assertEquals(periods("monthly", "2027-01-26").next.label, "금월 (2.1 ~ 2.28)");
});

Deno.test("합성 주간 견본: 줄 모양 전체(AI 전 □ 줄은 '이름 : 현재 상태 첫 문장')", () => {
  const plan = buildWorkPlan(SAMPLE_INPUT);
  assertEquals(plan.title, "주간 업무계획 (11.9 ~ 11.20)");
  assertEquals(plan.ownerName, "김하늘");
  // 순서: 프로젝트(이름 한국어 순 — 앱 트리처럼 한글 다음 영문) → 반복실행
  assertEquals(texts(plan.previous.lines), [
    "□ 모바일 보상 접수 개편",
    "- 접수 화면 1차 리뷰 (11/12 회의)",
    "- IT 개발팀 통화 (11/13)",
    "□ 콜센터 연동 : 연동 끝, 운영 이관함",
    "- 운영 이관 확인서 제출 (완료 11/9)",
    "□ CRM 이관 12건 : 12건 중 8건 이관 끝",
    "- 정산 규칙 초안 공유 (완료 11/10)",
    "- 이관 대상 목록 확정 (완료 11/11)",
    "* 11/9 이관 점검 회의",
    // 전주 칸의 안 끝난 일은 예정일에 끝낸다고 보고 날짜만(11/11 사용자 결정)
    "- 이관 일정 회신 (w.박지훈, 11/12)",
    // 같은 회의에서 나온 둘째 할 일에는 출처 '*'를 다시 적지 않는다
    "- 정산 규칙 검토 (11/13)",
    "□ 보상 운영",
    "- 주간 지급 현황 정리 (완료 11/10)",
  ]);
  // 금주 칸 □는 이름만
  assertEquals(texts(plan.next.lines), [
    "□ 모바일 보상 접수 개편",
    "- 화면 설계서 검토 회의 (11/17 예정)",
    "- 접수 항목 정의서 받기 (w.이도윤, 다음 주 중 예정)",
    "□ CRM 이관 12건",
    "- 남은 4건 이관 테스트 (11/18 예정)",
    "□ 보상 운영",
    "- 주간 지급 현황 정리 (11/17 예정)",
  ]);
});

Deno.test("서울 자정 경계: 일요일 23시 완료는 지난주, 월요일 0시 30분 완료는 이번 주", () => {
  const base = SAMPLE_INPUT.tasks[1];
  const input: PlanInput = {
    ...SAMPLE_INPUT,
    meetings: [],
    tasks: [
      { ...base, id: "a", title: "일요일 밤", doneAt: "2026-11-08T14:00:00Z" },
      { ...base, id: "b", title: "월요일 새벽", doneAt: "2026-11-08T15:30:00Z" },
    ],
  };
  assertEquals(texts(buildWorkPlan(input).previous.lines), ["□ CRM 이관 12건 : 12건 중 8건 이관 끝", "- 월요일 새벽 (완료 11/9)"]);
});

Deno.test("할 일·회의가 없는 프로젝트는 현재 상태가 있어도 넣지 않는다", () => {
  const plan = buildWorkPlan(SAMPLE_INPUT);
  assertEquals(plan.previous.lines.some((l) => l.text.startsWith("약관 정비")), false);
});

Deno.test("현재 상태 첫 문장: 마침표까지, 끝 마침표는 뺌, 마침표 없으면 전체", () => {
  assertEquals(firstSentence("12건 중 8건 끝. 남은 4건 대기."), "12건 중 8건 끝");
  assertEquals(firstSentence("  검토 순서만 정함  "), "검토 순서만 정함");
  assertEquals(firstSentence("버전 1.2 반영함. 다음은 배포."), "버전 1.2 반영함");
  assertEquals(firstSentence(null), null);
  assertEquals(firstSentence("   "), null);
});

Deno.test("규칙 □ 줄: 현재 상태가 없거나 반복실행이면 이름만", () => {
  const [crm, mobile, , , ops] = SAMPLE_INPUT.places;
  assertEquals(fallbackHead(crm), "CRM 이관 12건 : 12건 중 8건 이관 끝");
  assertEquals(fallbackHead(mobile), "모바일 보상 접수 개편");
  assertEquals(fallbackHead({ ...ops, stateSummary: "무시됨" }), "보상 운영");
});

Deno.test("AI 입력: 전주 칸에 나온 곳만, 이름·현재 상태 전체·그 주 줄(보통 띄어쓰기로)", () => {
  const inputs = headInputs(buildWorkPlan(SAMPLE_INPUT), SAMPLE_INPUT.places);
  assertEquals(inputs.map((h) => h.id), ["p-mobile", "p-old", "p-crm", "a-ops"]);
  const crm = inputs.find((h) => h.id === "p-crm")!;
  assertEquals(crm.state, "12건 중 8건 이관 끝. 남은 4건은 정산 규칙 확정을 기다리는 중.");
  assertEquals(crm.lines, [
    "- 정산 규칙 초안 공유 (완료 11/10)",
    "- 이관 대상 목록 확정 (완료 11/11)",
    "* 11/9 이관 점검 회의",
    "- 이관 일정 회신 (w.박지훈, 11/12)",
    "- 정산 규칙 검토 (11/13)",
  ]);
});

Deno.test("AI 한 줄 끼우기: 전주 칸 □만 바뀌고 금주 칸·없는 곳은 그대로", () => {
  const plan = applyHeads(buildWorkPlan(SAMPLE_INPUT), new Map([["p-crm", "CRM 이관 12건 중 8건 이관 완료"]]));
  const prevHeads = plan.previous.lines.filter((l) => l.level === "head").map((l) => l.text);
  assertEquals(prevHeads, ["모바일 보상 접수 개편", "콜센터 연동 : 연동 끝, 운영 이관함", "CRM 이관 12건 중 8건 이관 완료", "보상 운영"]);
  assertEquals(plan.next.lines.filter((l) => l.level === "head").map((l) => l.text), ["모바일 보상 접수 개편", "CRM 이관 12건", "보상 운영"]);
});

Deno.test("월간: 이번 달 완료·예정과 다음 달 예정", () => {
  const plan = buildWorkPlan({ ...SAMPLE_INPUT, kind: "monthly", baseDate: "2026-11-24" });
  assertEquals(plan.title, "월간 업무계획 (11.1 ~ 12.31)");
  // 11월 안의 일은 모두 전월 칸(지난주 끝낸 일·다다음 주 기한 포함), 기한 없는 일은 어느 칸에도 없음
  const prev = texts(plan.previous.lines);
  assertEquals(prev.includes("- 지난주 끝낸 일 (완료 11/8)"), true);
  assertEquals(prev.includes("- 다다음 주 일 (11/24)"), true);
  assertEquals(prev.some((t) => t.includes("기한 없는 일")), false);
  assertEquals(plan.next.lines.length, 0);
});

Deno.test("괄호 안은 줄바꿈 없는 공백이라 좁은 칸에서도 괄호째 넘어간다", () => {
  const line = buildWorkPlan(SAMPLE_INPUT).previous.lines.find((l) => l.text.startsWith("이관 일정 회신"))!;
  assertEquals(line.text, "이관 일정 회신 (w.박지훈,\u00A011/12)");
});

Deno.test("Word 파일이 만들어진다(zip 머리 PK, 주간·월간)", async () => {
  for (const input of [SAMPLE_INPUT, { ...SAMPLE_INPUT, kind: "monthly" as const, baseDate: "2026-11-24" }]) {
    const bytes = await renderPlanDocx(buildWorkPlan(input));
    assertEquals([bytes[0], bytes[1]], [0x50, 0x4b]);
  }
});

Deno.test("하위 할 일(FR-23a): 하위는 따로 줄 없이 상위 괄호에 '하위 N건 중 M건 완료', 하위만 맞아도 상위 줄", () => {
  const base = SAMPLE_INPUT.tasks[2]; // CRM의 안 끝난 일(11/13 기한) 모양을 빌린다
  const open = { ...base, source: null, dueDate: null, dueTime: null, doneAt: null, status: "open" };
  const input: PlanInput = {
    ...SAMPLE_INPUT,
    meetings: [],
    tasks: [
      // 상위 A: 이번 주 기한, 하위 3개(1개 끝)
      { ...open, id: "pa", title: "결제 화면 개편", dueDate: "2026-11-12", subtasks: { total: 3, done: 1 } },
      { ...open, id: "pa1", title: "오류 문구 수정", parentId: "pa", status: "done", doneAt: "2026-11-10T02:00:00Z" },
      { ...open, id: "pa2", title: "버튼 색 확인", parentId: "pa", dueDate: "2026-11-13" },
      { ...open, id: "pa3", title: "다다음 주 하위", parentId: "pa", dueDate: "2026-11-25" },
      // 상위 B: 자기는 기한 없음, 하위 하나가 이번 주에 끝남 · 다른 하위는 다음 주 기한
      { ...open, id: "pb", title: "정산 규칙 정리", subtasks: { total: 2, done: 1 } },
      { ...open, id: "pb1", title: "초안 공유", parentId: "pb", status: "done", doneAt: "2026-11-11T01:00:00Z" },
      { ...open, id: "pb2", title: "검토 회신", parentId: "pb", dueDate: "2026-11-18" },
      // 상위 C: 하위가 없는 보통 할 일(괄호 그대로)
      { ...open, id: "pc", title: "이관 일정 회신", dueDate: "2026-11-12" },
    ],
  };
  const plan = buildWorkPlan(input);
  assertEquals(texts(plan.previous.lines), [
    "□ CRM 이관 12건 : 12건 중 8건 이관 끝",
    "- 결제 화면 개편 (11/12 · 하위 3건 중 1건 완료)",
    "- 이관 일정 회신 (11/12)",
    "- 정산 규칙 정리 (하위 2건 중 1건 완료)",
  ]);
  assertEquals(texts(plan.next.lines), [
    "□ CRM 이관 12건",
    "- 정산 규칙 정리 (하위 2건 중 1건 완료)",
  ]);
});
