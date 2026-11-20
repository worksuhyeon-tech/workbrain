// 비서와 결정(DEC-111) 규칙 시험. 실행: npx -y deno@2.1.4 test tests/functions/secretary_test.ts
import { assertEquals } from "jsr:@std/assert@1";
import { buildInput, cleanLine, cleanSuggestions, stepFromChildren, untouched } from "../../supabase/functions/secretary/rules.ts";

const tasks = [
  { id: "a", title: "판독 업체 견적 비교표 정리", place: "자동차 사고접수 개편", follow: null, quote: "업체 세 곳 견적 받아서 비교표로 정리해 주세요" },
  { id: "b", title: "화면 설계서 검토 결과 받기", place: null, follow: "개발 담당", quote: null },
];

Deno.test("AI 입력: 번호·제목·곳·팔로업·회의에서 들은 말만", () => {
  assertEquals(buildInput(tasks), [
    `1. 제목: 판독 업체 견적 비교표 정리 | 곳: 자동차 사고접수 개편 | 회의에서 들은 말: "업체 세 곳 견적 받아서 비교표로 정리해 주세요"`,
    "2. 제목: 화면 설계서 검토 결과 받기 | 팔로업(남이 맡은 일): 개발 담당",
  ].join("\n"));
});

Deno.test("한 줄 다듬기: 공백·따옴표 정리, 너무 길거나 비면 버림", () => {
  assertEquals(cleanLine("  “받은 견적 메일   3개 모으기”  "), "받은 견적 메일 3개 모으기");
  assertEquals(cleanLine(""), null);
  assertEquals(cleanLine(42), null);
  assertEquals(cleanLine("가".repeat(41)), null);
});

Deno.test("AI 응답 → 번호별 제안: 범위 밖·겹침·빈 동작은 빼고, 조각은 2~4개만", () => {
  const out = cleanSuggestions({
    items: [
      { n: 1, step: "받은 견적 메일 3개 모으기", pieces: ["메일 모으기", "표 틀 만들기", "칸 채우기", "칸 채우기", "보내기", "확인하기"] },
      { n: 1, step: "다른 답", pieces: [] },
      { n: 2, step: "", pieces: ["묻기", "받기"] },
      { n: 3, step: "없는 번호", pieces: [] },
    ],
  }, tasks);
  assertEquals(out.get("a"), { step: "받은 견적 메일 3개 모으기", pieces: ["메일 모으기", "표 틀 만들기", "칸 채우기", "보내기"] });
  assertEquals(out.has("b"), false);
  assertEquals(cleanSuggestions({ items: [{ n: 2, step: "개발 담당에게 진행 묻기", pieces: ["묻기"] }] }, tasks).get("b"),
    { step: "개발 담당에게 진행 묻기", pieces: [] });
  assertEquals(cleanSuggestions(null, tasks).size, 0);
});

Deno.test("남은 하위 할 일이 있으면 그 첫 조각이 첫 동작(AI 없이)", () => {
  assertEquals(stepFromChildren([
    { id: "k1", title: "정상 케이스 적기", done: true },
    { id: "k2", title: "예외 케이스 목록 적기", done: false },
    { id: "k3", title: "기대 결과 적기", done: false },
  ]), { step: "예외 케이스 목록 적기", stepTaskId: "k2", pieces: ["예외 케이스 목록 적기", "기대 결과 적기"] });
  assertEquals(stepFromChildren([{ id: "k1", title: "끝", done: true }]), null);
  assertEquals(stepFromChildren([]), null);
});

Deno.test("손대지 않음 = 만든 뒤 바뀐 적·하위·집중이 모두 없음", () => {
  assertEquals(untouched(1, 0, false), true);
  assertEquals(untouched(2, 0, false), false);
  assertEquals(untouched(1, 1, false), false);
  assertEquals(untouched(1, 0, true), false);
});
