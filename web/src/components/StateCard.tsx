import { useCallback, useEffect, useState } from 'react'
import { loadState, stateLabel, type RecordBrief, type StateVersionRow } from '../data/para'
import { approveProposal, rejectProposal, requestProjectState } from '../data/records'
import { loadPendingState, type StateProposal } from '../data/review'
import { cn } from '../lib/cn'
import { mdOf } from '../rules/records'
import { INIT_RECORDS, initRecords, stateDecisionText, stateStale } from '../rules/review'
import { PRIMARY_BUTTON } from './Dialog'
import { StateProposalCard } from './StateProposalCard'
import { CARD, TONE_CHIP } from './ui'

const SPINNER = 'inline-block size-3.5 animate-spin rounded-full border-2 border-primary border-t-transparent'

/** 정리 요청 결과 중 멈춰야 하는 것(다시 해도 같거나 잠시 뒤 다시) → 알림 */
const STOP_TEXT: Record<string, string> = {
  BUDGET_WAIT: '이번 달 AI 예산 대기선을 넘어 정리를 멈췄어요',
  WAITING_KEY: 'AI 처리 키를 기다리는 중이라 정리하지 못했어요',
  FAILED: '정리하지 못했어요. 잠시 뒤 다시 눌러 주세요',
}

/**
 * 프로젝트 상세 '현재 상태' 카드(FR-09, DEC-86, 앱 projects/StateCard): 지금 상태(요약·상태 칩·마지막 반영) + 기다리는 AI 제안([반영]·[아니요],
 * 검토함에도 같은 제안). 비었으면 [연결된 기록으로 정리하기](최근 회의 3개로 제안 → 반영해야 바뀜, 회의 하나에 약 1~3원).
 * 직접 쓰기·고치기·변경 이력은 앱에서(DEC-102 ① 범위 밖). 아카이브한 프로젝트는 읽기만
 */
export function StateCard({ projectId, archived, linked, onToast }: { projectId: string; archived: boolean; linked: RecordBrief[]; onToast: (text: string) => void }) {
  // undefined = 읽는 중
  const [current, setCurrent] = useState<StateVersionRow | null | undefined>(undefined)
  const [pending, setPending] = useState<StateProposal | null>(null)
  const [failed, setFailed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [working, setWorking] = useState(false)

  const load = useCallback(async () => {
    try {
      const [c, p] = await Promise.all([loadState(projectId), loadPendingState(projectId)])
      setCurrent(c)
      setPending(p)
      setFailed(false)
      return p
    } catch (e) {
      console.error('load project state failed', e)
      setFailed(true)
      setCurrent((c) => (c === undefined ? null : c))
      return null
    }
  }, [projectId])
  useEffect(() => {
    setCurrent(undefined)
    setPending(null)
    void load()
  }, [load])

  const decide = async (p: StateProposal, ok: boolean) => {
    setBusy(true)
    const result = await (ok ? approveProposal(p, 'inbox') : rejectProposal(p, 'inbox')).catch(() => null)
    onToast(stateDecisionText(result, 'project'))
    await load()
    setBusy(false)
  }

  // 같은 프로젝트는 차례로(대기 제안을 고쳐 쓰므로 앞 회의가 끝나야 다음 회의를 합칠 수 있음, 앱 StateWorker).
  // 서버가 이미 반영한 기록은 건너뛰어(ALREADY) 다시 눌러도 비용이 두 번 들지 않는다
  const summarize = async () => {
    setWorking(true)
    let stop: string | null = null
    for (const recordId of initRecords(linked)) {
      const state = await requestProjectState(recordId, projectId).catch(() => 'FAILED')
      if (STOP_TEXT[state]) {
        stop = STOP_TEXT[state]
        break
      }
    }
    const p = await load()
    setWorking(false)
    onToast(stop ?? (p ? '정리했어요 · 아래 AI 제안을 보고 반영해 주세요' : '새로 정리할 내용이 없었어요'))
  }

  const hasLinked = linked.length > 0
  const showAi = hasLinked && !pending && !archived

  return (
    <section aria-label="현재 상태" className={cn(CARD, 'p-5')}>
      <div className="mb-3 flex items-center gap-2">
        <span className="text-sm font-bold text-muted">현재 상태</span>
        <span className={cn('rounded-full px-2 py-px text-[11px] font-bold', TONE_CHIP.mute)}>확정</span>
      </div>
      {current === undefined && <p className="text-sm text-muted">불러오는 중…</p>}
      {current === null && failed && <p className="text-sm text-crit">불러오지 못했어요. 인터넷 연결을 확인해 주세요.</p>}
      {current === null && !failed && (
        <div className="text-center">
          <p className="text-[15px] font-semibold text-ink">아직 확정된 상태가 없어요</p>
          <p className="mt-1 text-[13px] text-muted">
            {archived
              ? '아카이브한 프로젝트라 읽기만 할 수 있어요.'
              : pending
                ? '아래 AI 제안을 확인해 반영해 주세요. 직접 쓰기는 휴대폰 앱에서 할 수 있어요.'
                : hasLinked
                  ? '연결된 회의로 AI가 정리해 제안해요. 반영해야 바뀌어요. 직접 쓰기는 휴대폰 앱에서 할 수 있어요.'
                  : '기록을 연결하면 AI가 정리해 제안해요. 직접 쓰기는 휴대폰 앱에서 할 수 있어요.'}
          </p>
          {showAi && (
            <>
              <button type="button" disabled={working || busy} onClick={() => void summarize()} className={cn(PRIMARY_BUTTON, 'mt-3')}>
                {working ? '정리하는 중…' : '연결된 기록으로 정리하기'}
              </button>
              <p className="mt-1.5 text-xs text-muted">최근 회의 {INIT_RECORDS}개까지 봐요 · 회의 하나에 약 1~3원</p>
            </>
          )}
        </div>
      )}
      {current && (
        <>
          <p className="text-[15px] leading-relaxed whitespace-pre-wrap text-ink">{current.summary || '내용 없음'}</p>
          <div className="mt-3 flex flex-wrap items-center gap-2 text-xs text-muted">
            <span className={cn('rounded-full px-2.5 py-[3px] text-xs font-bold', current.status === 'paused' ? TONE_CHIP.warn : current.status === 'done' ? TONE_CHIP.mute : TONE_CHIP.good)}>
              {stateLabel(current.status)}
            </span>
            마지막 반영 {mdOf(current.created_at) ?? ''} · {current.applied_by === 'user' ? '직접 수정' : 'AI 제안 승인'}
          </div>
        </>
      )}
      {working && (
        <p className="mt-3 flex items-center gap-2 text-[13px] text-primary">
          <span className={SPINNER} aria-hidden="true" /> AI가 연결된 회의로 현재 상태를 정리하는 중…
        </p>
      )}
      {pending && (
        <div className="mt-4">
          <StateProposalCard
            p={pending}
            title="AI 제안"
            busy={busy || archived}
            stale={stateStale(pending.baseVersion, current?.version ?? 0)}
            onApprove={archived ? undefined : () => void decide(pending, true)}
            onReject={archived ? undefined : () => void decide(pending, false)}
          />
        </div>
      )}
    </section>
  )
}
