// 할 일·결정 후보 뽑기 모델(DEC-113) — Anthropic Messages API 요청 본문과 응답 읽기.
// 소넷 5.5는 도구 강제 호출(tool_choice tool)을 400으로 막아, 응답 형식(output_config.format, JSON 스키마)으로
// 구조를 강제한다(eval/llm/run.mjs와 같은 방식). 부르는 쪽(index.ts)이 다시 부를 일이면 luna로 한 번 더 부른다.
import { EXTRACT_MODEL } from "../_shared/ai-cost.ts";
import { AiAccountError, anthropicAccountKind } from "./ai-error.ts";

// 시간: Supabase 무료 플랜은 함수 한 번을 150초에 끊고, 앱도 150초까지 기다린다.
// 실제 2시간 회의(1,566구간)에서 소넷이 약 105초 걸렸다(11/20 실측, luna는 약 56초)
/** 한 번의 처리 호출 안에서 쓰는 시간 */
export const REQUEST_BUDGET_MS = 140_000;
/** 소넷 뒤에 남겨 두는 시간: 기존 할 일과 비교(luna)·저장 */
export const AFTER_EXTRACT_MS = 25_000;
/** 소넷이 답하지 못했을 때 이 호출 안에서 luna로 다시 뽑으려면 남아 있어야 하는 시간 */
export const LUNA_EXTRACT_MS = 60_000;
/** 소넷이 이번 호출에서 끝내지 못했다는 표시(processing_job.last_error). 앱은 '다시 시도 중'으로 보이고 3초 뒤 다시 부르며, 그때는 luna로 뽑는다 */
export const SONNET_LATE = "소넷이 시간 안에 답하지 않아 luna로 다시 정리해요";

export type Usage = { input_tokens: number; output_tokens: number };
export const NO_USAGE: Usage = { input_tokens: 0, output_tokens: 0 };

/** 한 번 부른 결과: 답(text), 또는 다른 모델로 다시 부를 까닭(retry: 거절·몰림·서버 문제·길이 초과) */
export type ClaudeReply =
  | { text: string; usage: Usage; id: string | null }
  | { retry: string; usage: Usage };

type ClaudeBody = {
  id?: string;
  stop_reason?: string;
  usage?: { input_tokens?: number; output_tokens?: number };
  content?: { type?: string; text?: string }[];
  error?: { message?: string };
} | null;

/** 요청 본문. 지시문·입력·출력 형식은 luna와 같다 */
export function claudeExtractBody(system: string, user: string, schema: unknown) {
  return {
    model: EXTRACT_MODEL,
    max_tokens: 16000,   // 긴 회의는 생각+답이 수천 토큰(평가 최대 약 3천)
    system,
    messages: [{ role: "user", content: user }],
    output_config: { format: { type: "json_schema", schema } },
  };
}

/** 응답 상태·본문 → 답. 키·크레딧 문제는 AiAccountError로 던진다(기록 잘못이 아니라 키를 고친 뒤 이어서 처리) */
export function readClaudeReply(status: number, body: unknown): ClaudeReply {
  const d = body as ClaudeBody;
  const usage = { input_tokens: d?.usage?.input_tokens ?? 0, output_tokens: d?.usage?.output_tokens ?? 0 };
  if (status < 200 || status >= 300) {
    const kind = anthropicAccountKind(status, d);
    if (kind) throw new AiAccountError(kind, "Anthropic");
    // 몰림(429)·서버 문제(5xx, 529 과부하)는 다른 모델로 바로 다시 부르면 된다
    if (status === 429 || status >= 500) return { retry: `Anthropic ${status}`, usage };
    throw new Error(`분석 실패: ${d?.error?.message ?? status}`);
  }
  // 거절이면 답이 형식대로가 아니다. 지침을 빼내려는 말이 섞인 메모(보안 시험 S09)에서 실제로 나왔다
  if (d?.stop_reason === "refusal" || d?.stop_reason === "max_tokens") return { retry: String(d.stop_reason), usage };
  const text = d?.content?.find((c) => c.type === "text")?.text;
  if (d?.stop_reason !== "end_turn" || typeof text !== "string") throw new Error("분석 결과 형식이 맞지 않아요");
  return { text, usage, id: d?.id ?? null };
}
