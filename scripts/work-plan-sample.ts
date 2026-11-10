// 업무계획 Word 견본 만들기(FR-33): 합성 자료(tests/functions/work_plan_fixture.ts)로 실제 렌더러를 돌려
// 회사 양식과 나란히 비교할 .docx를 만든다. 사용: npx -y deno run -A scripts/work-plan-sample.ts <나올 파일.docx> [weekly|monthly]
import { buildWorkPlan } from "../supabase/functions/_shared/work-plan.ts";
import { renderPlanDocx } from "../supabase/functions/_shared/plan-docx.ts";
import { SAMPLE_INPUT } from "../tests/functions/work_plan_fixture.ts";

const out = Deno.args[0];
if (!out) {
  console.error("나올 파일 경로를 주세요: scripts/work-plan-sample.ts <파일.docx> [weekly|monthly]");
  Deno.exit(1);
}
const monthly = Deno.args[1] === "monthly";
const plan = buildWorkPlan(monthly ? { ...SAMPLE_INPUT, kind: "monthly", baseDate: "2026-11-24" } : SAMPLE_INPUT);
await Deno.writeFile(out, await renderPlanDocx(plan));
console.log(`${plan.title} → ${out}`);
