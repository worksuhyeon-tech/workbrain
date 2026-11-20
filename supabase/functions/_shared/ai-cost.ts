// AI 모델·값·예산(NFR-07, DEC-19) — 기록 처리(process-record)와 비서(secretary)가 같이 쓴다.
// 비용은 확정 원장(cost_ledger)에 원 단위로 쌓고, 이번 달 합이 예산 대기선을 넘으면 새 AI 호출을 하지 않는다.
import type { SupabaseClient } from "jsr:@supabase/supabase-js@2";

export type UsdPer1M = { input: number; output: number };

export const LLM_MODEL = "gpt-6-luna";
export const LLM_USD_PER_1M: UsdPer1M = { input: 0.10, output: 0.50 };
// 할 일·결정 후보 뽑기만 윗급 모델(DEC-113: 같은 일을 두 후보로 나누지 않은 유일한 모델). 나머지 단계는 LLM_MODEL
export const EXTRACT_MODEL = "claude-sonnet-5-5";
export const EXTRACT_USD_PER_1M: UsdPer1M = { input: 2.00, output: 10.00 };
export const KRW_PER_USD = 1400;

/** 청구 주기(UTC 기준 yyyy-MM, 앱의 '이번 달 AI 처리 비용'과 같은 구분) */
export const billingCycle = (): string => new Date().toISOString().slice(0, 7);

/** 응답 사용량(토큰) → 달러. 단가를 안 주면 LLM_MODEL 단가 */
export function llmUsd(usage: { input_tokens?: number; output_tokens?: number } | null | undefined, price: UsdPer1M = LLM_USD_PER_1M): number {
  return ((usage?.input_tokens ?? 0) * price.input + (usage?.output_tokens ?? 0) * price.output) / 1e6;
}

/** 이번 달 확정 비용이 예산 대기선(기본 2만4천 원)을 넘었는지 */
export async function overBudget(db: SupabaseClient, uid: string, cycle: string): Promise<boolean> {
  const { data: setting } = await db.from("settings").select("budget_wait_krw").eq("user_id", uid).single();
  const { data: spent } = await db.from("cost_ledger").select("confirmed_krw").eq("owner_id", uid).eq("billing_cycle", cycle);
  const spentKrw = (spent ?? []).reduce((n, r) => n + (r.confirmed_krw ?? 0), 0);
  return spentKrw >= (setting?.budget_wait_krw ?? 24000);
}

/** 쓴 만큼 원장에 남긴다(1원 미만도 1원으로 올림) */
export async function addCost(db: SupabaseClient, uid: string, cycle: string, usd: number) {
  await db.from("cost_ledger").insert({ owner_id: uid, billing_cycle: cycle, confirmed_krw: Math.max(1, Math.ceil(usd * KRW_PER_USD)) });
}
