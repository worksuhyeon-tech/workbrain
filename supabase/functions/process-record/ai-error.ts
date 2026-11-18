// AI 계정 문제(키 만료·맞지 않는 키·크레딧 없음, 11/18 사용자): 기록 잘못이 아니므로 처리 실패로 세지 않고 기다린다.
// 새 키를 넣거나 충전한 뒤 이어서 처리하면 같은 기록이 그대로 진행된다(다시 올리지 않음).
// 화면에 그대로 보이는 글이라, 무엇이 문제이고 무엇을 하면 되는지 바로 알 수 있게 쓴다.

export type AiAccountKind = "key-expired" | "key-invalid" | "quota";

const RESUME = "새 키를 넣은 뒤 '이어서 처리'를 누르면 돼요(다시 올리지 않아도 돼요)";

export const AI_ACCOUNT_MESSAGE: Record<AiAccountKind, string> = {
  "key-expired": `AI API 키 기한이 만료됐어요. ${RESUME}`,
  "key-invalid": `AI API 키가 맞지 않아요(지워졌거나 잘못 넣은 키). ${RESUME}`,
  "quota": "AI 사용 크레딧이 다 떨어졌어요. 충전한 뒤 '이어서 처리'를 누르면 돼요(다시 올리지 않아도 돼요)",
};

/** 서버에 키를 아직 넣지 않았을 때 */
export const AI_NO_KEY_MESSAGE = "AI API 키가 아직 없어요. 키를 넣은 뒤 '이어서 처리'를 누르면 돼요(다시 올리지 않아도 돼요)";

export class AiAccountError extends Error {
  constructor(readonly kind: AiAccountKind) {
    super(AI_ACCOUNT_MESSAGE[kind]);
    this.name = "AiAccountError";
  }
}

type OpenAiBody = { error?: { message?: unknown; code?: unknown; type?: unknown } } | null | undefined;

/** OpenAI 실패 응답 → 계정 문제 종류(아니면 null). 401 = 키 문제(만료는 글로 가림), 429 + insufficient_quota = 크레딧 */
export function accountKind(status: number, body: unknown): AiAccountKind | null {
  const err = (body as OpenAiBody)?.error;
  if (status === 401) return /expired/i.test(String(err?.message ?? "")) ? "key-expired" : "key-invalid";
  // 429는 잠깐 몰린 것(rate limit)도 같은 번호라 code·type으로 크레딧만 가린다(몰린 것은 다시 시도하면 된다)
  if (status === 429 && [err?.code, err?.type].some((v) => v === "insufficient_quota")) return "quota";
  return null;
}

/** 실패한 OpenAI 응답 → 던질 오류: 계정 문제면 AiAccountError, 아니면 '단계 이름: 원문' */
export function openAiError(label: string, status: number, body: unknown): Error {
  const kind = accountKind(status, body);
  if (kind) return new AiAccountError(kind);
  return new Error(`${label}: ${(body as OpenAiBody)?.error?.message ?? status}`);
}
