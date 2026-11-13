import { useCallback, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { PageHead } from '../components/AppShell'
import { CandidateEditDialog } from '../components/CandidateEditDialog'
import { CandidateRow } from '../components/CandidateRow'
import { ChangeRow } from '../components/ChangeRow'
import { ConfirmDialog, Dialog, PRIMARY_BUTTON, SECONDARY_BUTTON } from '../components/Dialog'
import { Icons } from '../components/icons'
import { PlaceSelect, placeKeyOf, placeOfKey } from '../components/PlaceSelect'
import { RecipientsDialog } from '../components/RecipientsDialog'
import { Seg } from '../components/Seg'
import { SnoozeDialog } from '../components/SnoozeDialog'
import { StateProposalCard } from '../components/StateProposalCard'
import { useToast } from '../components/Toast'
import { CARD } from '../components/ui'
import { approveProposal, linkRecord, rejectProposal, requestProjectState, type LinkSuggestion, type ProjectName } from '../data/records'
import { loadInbox, loadProcessed, snoozeProposals, type Inbox, type InboxRow, type StateProposal } from '../data/review'
import { loadPlaces } from '../data/server'
import { cn } from '../lib/cn'
import { seoulToday, type LocalDate } from '../rules/dates'
import type { Place } from '../rules/place'
import { followNamesOf, mdOf, recipientsOf, snoozeActive, snoozeLabel, suggestionNote, withPlace } from '../rules/records'
import { evidenceUntil, groupByRecord, lastAllowed, retentionNote, snoozeText, stateDecisionText, stateDoneLabel } from '../rules/review'
import { UNSORTED } from '../rules/tasks'

type Tab = 'today' | 'snoozed' | 'done'
type Json = Record<string, unknown>
const NET_FAILED = '인터넷 연결을 확인해 주세요'

/**
 * 검토함(FR-28, DEC-63, 웹 목업 review-inbox.html 머리·탭, 앱 ReviewScreen 동작): 모든 기록에서 나온 승인 대기 후보를 기록별로 모아 한 번에 고른다.
 * 오늘(처음에 모두 체크) · 미룬 항목(미룬 날 0시에 오늘로) · 처리됨(최근 100). 승인은 기록 상세와 같은 함수로 하되 위치를 inbox로 남긴다(DEC-23).
 * 프로젝트 현재 상태 제안(FR-09)은 할 일 후보와 따로 위에 두고 하나씩 [반영]·[아니요]
 */
export function ReviewScreen() {
  const [inbox, setInbox] = useState<Inbox | null>(null)
  const [failed, setFailed] = useState(false)
  const [tab, setTab] = useState<Tab>('today')
  const [processed, setProcessed] = useState<{ row: InboxRow; processedAt: string | null }[] | null>(null)
  const [places, setPlaces] = useState<Place[]>([])
  // 체크: 오늘은 처음에 모두 체크(누른 것만 저장), 미룬 항목은 처음에 체크하지 않는다(목업)
  const [checked, setChecked] = useState<Record<string, boolean>>({})
  const [snoozeChecked, setSnoozeChecked] = useState<Record<string, boolean>>({})
  const [edits, setEdits] = useState<Record<string, Json>>({})
  const [picked, setPicked] = useState<{ place: Place | null } | null>(null)
  const [editing, setEditing] = useState<{ row: InboxRow; mode: 'owner' | 'due' } | null>(null)
  const [recipientsFor, setRecipientsFor] = useState<InboxRow | null>(null)
  const [snoozeTarget, setSnoozeTarget] = useState<InboxRow[] | null>(null)
  const [discardTarget, setDiscardTarget] = useState<InboxRow[] | null>(null)
  const [suggestFor, setSuggestFor] = useState<{ recordId: string; title: string } | null>(null)
  const [busy, setBusy] = useState(false)
  const toast = useToast()

  const reload = useCallback(async () => {
    try {
      setInbox(await loadInbox())
      setFailed(false)
    } catch (e) {
      console.error('load inbox failed', e)
      setFailed(true)
    }
  }, [])
  useEffect(() => {
    void reload()
    loadPlaces().then(setPlaces, (e) => console.warn('load places failed', e))
  }, [reload])
  // 처리됨은 그 탭을 열 때만 읽는다(최근 100건)
  useEffect(() => {
    if (tab !== 'done') return
    setProcessed(null)
    loadProcessed().then(setProcessed, (e) => {
      console.error('load processed failed', e)
      toast.show(`처리한 후보를 불러오지 못했어요. ${NET_FAILED}`)
      setProcessed([])
    })
  }, [tab, toast.show])

  const rows = inbox?.rows ?? []
  const snoozedRows = rows.filter((r) => snoozeActive(r.snoozed_until))
  const todayRows = rows.filter((r) => !snoozeActive(r.snoozed_until))
  const tabRows = tab === 'today' ? todayRows : tab === 'snoozed' ? snoozedRows : []
  const isMarked = (r: InboxRow) => (tab === 'snoozed' ? snoozeChecked[r.id] === true : (checked[r.id] ?? true))
  const setMarks = (list: InboxRow[], v: boolean) => {
    const put = (m: Record<string, boolean>) => ({ ...m, ...Object.fromEntries(list.map((r) => [r.id, v])) })
    if (tab === 'snoozed') setSnoozeChecked(put)
    else setChecked(put)
  }
  const selected = tabRows.filter(isMarked)
  const names = new Map((inbox?.projects ?? []).map((p) => [p.id, p]))
  // 직접 고르지 않았으면, 고른 새 할 일 후보가 모두 같은 프로젝트 하나에 연결된 기록에서 왔을 때만 그 프로젝트(DEC-69). 섞이면 정리 안 됨
  const common = [...new Set(selected.filter((r) => !r.target_id).map((r) => (r.recordId ? inbox?.recordProjects.get(r.recordId) : undefined)?.length === 1 ? inbox!.recordProjects.get(r.recordId!)![0] : null))]
  const autoPlace = common.length === 1 && common[0] ? (places.find((p) => p.type === 'PROJECT' && p.id === common[0]) ?? null) : null
  const addPlace = picked ? picked.place : autoPlace
  const followNames = followNamesOf([], rows.map((r) => r.after_json.assignee), inbox?.myNames ?? [])
  const current = (r: InboxRow): Json => ({ ...r.after_json, ...edits[r.id] })
  const edit = (r: InboxRow, f: Json) => {
    setEdits((e) => ({ ...e, [r.id]: { ...e[r.id], ...f } }))
    setChecked((c) => ({ ...c, [r.id]: true }))
  }
  // 이 기록에 보일 AI 연결 제안: 아직 잇지 않은 진행 중 프로젝트만
  const suggestionsOf = (recordId: string): { s: LinkSuggestion; p: ProjectName }[] =>
    (inbox?.suggestions.get(recordId) ?? []).flatMap((s) => {
      const p = names.get(s.projectId)
      return p && !p.archived_at && !(inbox?.recordProjects.get(recordId) ?? []).includes(s.projectId) ? [{ s, p }] : []
    })

  // 버리기·추가·미루기 모두 체크한 후보에만(사용자 요청 10/26)
  const discard = async (list: InboxRow[]) => {
    setBusy(true)
    try {
      for (const r of list) await rejectProposal(r, 'inbox')
      toast.show(`후보 ${list.length}개를 버렸어요`)
    } catch {
      toast.show('버리지 못했어요. 다시 시도해 주세요')
    }
    await reload()
    setBusy(false)
  }
  const approve = async (list: InboxRow[], target: Place | null) => {
    setBusy(true)
    let results: string[] | null = []
    try {
      // 추가할 곳은 새 할 일 후보에만 붙인다. 변경 제안은 이미 있는 할 일을 고치는 것이라 그 자리를 바꾸지 않는다
      for (const r of list) results.push(await approveProposal(r, 'inbox', r.target_id ? edits[r.id] : withPlace(edits[r.id], target)))
    } catch (e) {
      console.error('approve failed', e)
      results = null
    }
    toast.show(
      results === null
        ? `추가하지 못했어요. ${NET_FAILED}`
        : results.every((x) => x === 'approved' || x === 'already-approved')
          ? list.every((r) => !r.target_id)
            ? `할 일 ${list.length}개를 ‘${target?.name ?? UNSORTED}’에 추가했어요`
            : `후보 ${list.length}개를 반영했어요`
          : `일부는 추가하지 못했어요(${results.filter((x) => x !== 'approved').join(', ')})`,
    )
    // 다음에 고르는 후보는 다시 연결된 프로젝트로 처음 값을 잡는다
    setPicked(null)
    await reload()
    setBusy(false)
  }
  // until = null이면 지금 검토(미룸 해제). 옮긴 후보는 체크를 지워 다시 '오늘'로 오면 기본값(체크됨)으로
  const snooze = async (list: InboxRow[], until: LocalDate | null) => {
    setBusy(true)
    const updated = await snoozeProposals(
      list.map((r) => r.id),
      until,
    ).catch(() => null)
    toast.show(snoozeText(updated, until))
    const drop = (m: Record<string, boolean>) => Object.fromEntries(Object.entries(m).filter(([id]) => !list.some((r) => r.id === id)))
    setChecked(drop)
    setSnoozeChecked(drop)
    await reload()
    setBusy(false)
  }
  const decideState = async (p: StateProposal, ok: boolean) => {
    setBusy(true)
    const result = await (ok ? approveProposal(p, 'inbox') : rejectProposal(p, 'inbox')).catch(() => null)
    toast.show(stateDecisionText(result))
    await reload()
    setBusy(false)
  }
  // AI 연결 제안 [연결]·[아니요]. 연결하면 그 회의로 프로젝트 현재 상태 제안을 만든다(FR-09). 그 기록에 남은 제안이 없으면 창을 닫는다
  const decideLink = async (recordId: string, p: ProjectName, on: boolean) => {
    setBusy(true)
    try {
      const result = await linkRecord(recordId, p.id, on)
      if (result === 'linked') void requestProjectState(recordId, p.id).catch((e) => console.warn('project_state request failed', e))
      toast.show(on ? `‘${p.name}’에 연결했어요` : `‘${p.name}’ 연결 제안을 넘겼어요 · 다시 제안하지 않아요`)
    } catch (e) {
      toast.show(String((e as Error)?.message ?? '').includes('WB409') ? '아카이브된 프로젝트에는 연결할 수 없어요' : `바꾸지 못했어요. ${NET_FAILED}`)
    }
    await reload()
    setBusy(false)
  }
  useEffect(() => {
    if (suggestFor && inbox && suggestionsOf(suggestFor.recordId).length === 0) setSuggestFor(null)
  }, [suggestFor, inbox])

  const groups = groupByRecord(tabRows, (r) => ({ id: r.recordId, title: r.recordTitle }))
  const states = inbox?.states ?? []

  const row = (r: InboxRow) => {
    const snoozedTab = tab === 'snoozed'
    const badges = snoozedTab ? [{ text: `미룸 ${snoozeLabel(r.snoozed_until) ?? ''}`.trim(), tone: 'info' as const }] : []
    // 새 할 일 후보(오늘)는 기록 상세처럼 그 자리에서 고친다. 변경 제안은 전후를 보고 승인·버림만, 미룬 후보는 '지금 검토' 뒤에 고친다
    if (!r.target_id && !snoozedTab) {
      return (
        <CandidateRow
          key={r.id}
          after={current(r)}
          rationale={r.rationale}
          status={r.status}
          editing={{
            checked: isMarked(r),
            onChecked: (v) => setMarks([r], v),
            followNames,
            onTitle: (title) => edit(r, { title }),
            onOwner: (kind, who) => edit(r, { owner_kind: kind, assignee: who, owner_basis: null }),
            onOwnerOther: () => setEditing({ row: r, mode: 'owner' }),
            onDue: () => setEditing({ row: r, mode: 'due' }),
            onRecipients: () => setRecipientsFor(r),
          }}
        />
      )
    }
    const note = snoozedTab ? retentionNote(r.transcriptExpiresAt, r.audio) : null
    return (
      <div key={r.id} className={cn('flex items-start gap-2 border-b border-border pl-4 last:border-b-0', isMarked(r) && 'bg-info-soft/40')}>
        <input type="checkbox" checked={isMarked(r)} onChange={(e) => setMarks([r], e.target.checked)} aria-label="고르기" className="mt-[15px] size-[18px] flex-none accent-primary" />
        {/* 줄 안쪽 밑줄은 빼고 바깥 줄 하나로 나눈다(보관 기한 안내가 줄 아래에 붙게) */}
        <div className="min-w-0 flex-1 [&>div:first-child]:border-b-0 [&>div:first-child]:pl-2">
          {r.target_id ? <ChangeRow p={r} /> : <CandidateRow after={current(r)} rationale={r.rationale} status={r.status} badges={badges} />}
          {note && <p className="-mt-1.5 pb-3 pl-2 text-xs text-muted">{note}</p>}
        </div>
      </div>
    )
  }

  return (
    <>
      <PageHead title="검토함" hand="꼼꼼히 살펴봐요" sub="AI가 제안한 변경을 확정 상태에 반영하려면 승인하세요 · 오늘 못 하면 미뤄 둘 수 있어요">
        <button type="button" onClick={() => void reload()} className="text-sm font-semibold text-link">
          새로고침
        </button>
      </PageHead>

      {failed && (
        <div className="mb-4 flex items-center gap-3 rounded-lg bg-crit-soft p-3 text-sm text-crit">
          <span className="flex-1">검토함을 불러오지 못했어요. {NET_FAILED}.</span>
          <button type="button" onClick={() => void reload()} className="font-semibold underline">
            다시 불러오기
          </button>
        </div>
      )}

      <div className="mb-4 flex flex-wrap items-center gap-3 border-b border-border pb-4">
        <Seg
          label="검토함 보기"
          items={[
            { value: 'today' as const, label: '오늘', count: todayRows.length + states.length },
            { value: 'snoozed' as const, label: '미룬 항목', count: snoozedRows.length },
            { value: 'done' as const, label: '처리됨' },
          ]}
          value={tab}
          onChange={setTab}
        />
        {tabRows.length > 0 && (
          <label className="flex cursor-pointer items-center gap-2 text-sm font-semibold text-body">
            <TriCheckbox state={selected.length === 0 ? 'none' : selected.length === tabRows.length ? 'all' : 'some'} onToggle={() => setMarks(tabRows, selected.length !== tabRows.length)} />
            전체 선택
          </label>
        )}
        <div className="flex-1" />
        {tabRows.length > 0 && <span className="text-sm text-muted">선택 {selected.length}건</span>}
      </div>

      {!inbox && !failed && <p className="py-10 text-center text-sm text-muted">불러오는 중…</p>}

      {tab === 'today' && inbox && (
        <>
          {states.length > 0 && (
            <section aria-label="프로젝트 현재 상태 제안" className="mb-6">
              <h2 className="mb-2 text-[13px] font-bold text-muted">프로젝트 현재 상태 제안 · {states.length}</h2>
              <div className="grid gap-3 min-[1100px]:grid-cols-2">
                {states.map((p) => (
                  <StateProposalCard key={p.id} p={p} title={names.get(p.projectId)?.name ?? '프로젝트'} busy={busy} onApprove={() => void decideState(p, true)} onReject={() => void decideState(p, false)} />
                ))}
              </div>
            </section>
          )}
          {todayRows.length === 0 && states.length === 0 && (
            <div className={cn(CARD, 'px-6 py-12 text-center')}>
              <p className="font-hand text-[26px] text-good">검토 끝! 깔끔해요</p>
              <p className="mt-1 text-[13px] text-muted">{snoozedRows.length === 0 ? '기록을 올리면 할 일 후보가 여기에 모여요.' : `미룬 후보 ${snoozedRows.length}개는 그날 여기에 다시 나와요.`}</p>
            </div>
          )}
        </>
      )}
      {tab === 'snoozed' && inbox && (
        <p className="mb-3 text-[13px] text-muted">
          {snoozedRows.length === 0 ? '미뤄 둔 후보가 없어요.' : '미룬 날 0시에 ‘오늘’로 다시 나와요. 미뤄도 근거 보관 기간은 줄어들고, 음성만 지난 후보는 전사로 검토할 수 있어요.'}
        </p>
      )}

      {tab !== 'done' &&
        groups.map((g) => {
          const marked = g.rows.filter(isMarked).length
          const sugg = g.id ? suggestionsOf(g.id) : []
          const at = mdOf(g.rows[0]?.recordAt ?? null)
          return (
            <section key={g.id ?? 'none'} aria-label={g.title} className={cn(CARD, 'mb-4 overflow-hidden')}>
              <div className="flex flex-wrap items-center gap-2 border-b border-border bg-surface/60 px-4 py-2.5">
                <TriCheckbox state={marked === 0 ? 'none' : marked === g.rows.length ? 'all' : 'some'} onToggle={() => setMarks(g.rows, marked !== g.rows.length)} label={`${g.title} 후보 모두 고르기`} />
                <span className="text-[15px] font-bold text-ink">{g.title}</span>
                <span className="text-xs font-semibold text-muted">
                  {[at, `${g.rows.length}건`].filter(Boolean).join(' · ')}
                </span>
                {/* 후보를 고르기 전에 프로젝트부터 정하면 '추가할 곳'이 따라간다(DEC-75) */}
                {sugg.map(({ p }) => (
                  <button
                    key={p.id}
                    type="button"
                    onClick={() => setSuggestFor({ recordId: g.id!, title: g.title })}
                    className="inline-flex items-center gap-1 rounded-full border border-dashed border-border-strong px-2.5 py-0.5 text-xs font-semibold text-muted hover:text-ink"
                  >
                    <Icons.folder /> {p.name} · 제안
                  </button>
                ))}
                <div className="flex-1" />
                {g.id && (
                  <Link to={`/records/${g.id}`} className="text-[13px] font-semibold">
                    회의록 보기 →
                  </Link>
                )}
              </div>
              {g.rows.map(row)}
            </section>
          )
        })}

      {tab === 'done' && (
        <>
          {processed === null && <p className="py-10 text-center text-sm text-muted">불러오는 중…</p>}
          {processed?.length === 0 && <p className="py-6 text-sm text-muted">아직 처리한 후보가 없어요.</p>}
          {processed && processed.length > 0 && <p className="mb-3 text-[13px] text-muted">최근에 처리한 후보예요(최대 100개).</p>}
          <div className="flex flex-col gap-3">
            {processed?.map(({ row: r, processedAt }) =>
              r.target_type === 'project_state' ? (
                <StateProposalCard
                  key={r.id}
                  p={{ id: r.id, status: r.status, revision: r.revision, projectId: r.target_id ?? '', baseVersion: null, before: r.before_json, after: r.after_json, rationale: r.rationale, quote: null }}
                  title={`${names.get(r.target_id ?? '')?.name ?? '프로젝트'} · ${stateDoneLabel(r.status)}`}
                  busy
                />
              ) : (
                <div key={r.id} className={cn(CARD, 'overflow-hidden')}>
                  <div className="flex items-center gap-2 border-b border-border bg-surface/60 px-4 py-2 text-xs text-muted">
                    {r.recordId ? (
                      <Link to={`/records/${r.recordId}`} className="font-semibold">
                        {r.recordTitle} · 회의록 보기
                      </Link>
                    ) : (
                      <span>{r.recordTitle}</span>
                    )}
                    <div className="flex-1" />
                    {mdOf(processedAt) && <span>{mdOf(processedAt)} 처리</span>}
                  </div>
                  {r.target_id ? <ChangeRow p={r} /> : <CandidateRow after={r.after_json} rationale={r.rationale} status={r.status} />}
                </div>
              ),
            )}
          </div>
        </>
      )}

      {/* 아래 줄(떠 있음): 오늘 = 추가할 곳 + 버리기·미루기·추가, 미룬 항목 = 버리기·미루기 변경·지금 검토(목업·앱) */}
      {tabRows.length > 0 && (
        <>
          <div className="h-20" aria-hidden="true" />
          <div className="sticky bottom-0 z-20 -mx-1 flex flex-wrap items-center gap-3 rounded-xl border border-border bg-card px-4 py-3 shadow-lg">
            {tab === 'today' && (
              <label className="flex items-center gap-2 text-[13px] font-semibold text-muted">
                추가할 곳
                <PlaceSelect
                  value={placeKeyOf(addPlace)}
                  places={places}
                  onChange={(k) => setPicked({ place: placeOfKey(places, k) })}
                  label="추가할 곳"
                  className="h-[38px] w-[220px] rounded-md border border-border-strong bg-card px-2 text-sm font-semibold text-ink"
                />
              </label>
            )}
            <div className="flex-1" />
            <button type="button" disabled={busy || selected.length === 0} onClick={() => (selected.length > 1 ? setDiscardTarget(selected) : void discard(selected))} className={SECONDARY_BUTTON}>
              버리기
            </button>
            <button type="button" disabled={busy || selected.length === 0} onClick={() => setSnoozeTarget(selected)} className={SECONDARY_BUTTON}>
              {tab === 'snoozed' ? '미루기 변경' : '미루기'}
            </button>
            {tab === 'snoozed' ? (
              <button type="button" disabled={busy || selected.length === 0} onClick={() => void snooze(selected, null)} className={PRIMARY_BUTTON}>
                지금 검토 ({selected.length})
              </button>
            ) : (
              <button type="button" disabled={busy || selected.length === 0} onClick={() => void approve(selected, addPlace)} className={PRIMARY_BUTTON}>
                {busy ? '처리하는 중…' : `선택한 할 일 추가 (${selected.length})`}
              </button>
            )}
          </div>
        </>
      )}

      {discardTarget && (
        <ConfirmDialog
          title={`후보 ${discardTarget.length}개 버리기`}
          text={`체크한 할 일 후보 ${discardTarget.length}개를 버려요. 버린 후보는 검토함에서 사라져요.`}
          confirmLabel="버리기"
          danger
          onDismiss={() => setDiscardTarget(null)}
          onConfirm={() => {
            const list = discardTarget
            setDiscardTarget(null)
            void discard(list)
          }}
        />
      )}
      {snoozeTarget && (
        <SnoozeDialog
          count={snoozeTarget.length}
          lastAllowed={lastAllowed(
            snoozeTarget.map((r) => r.transcriptExpiresAt),
            seoulToday(),
          )}
          evidenceUntil={evidenceUntil(snoozeTarget.map((r) => r.transcriptExpiresAt))}
          onDismiss={() => setSnoozeTarget(null)}
          onPick={(day) => {
            const list = snoozeTarget
            setSnoozeTarget(null)
            void snooze(list, day)
          }}
        />
      )}
      {editing && (
        <CandidateEditDialog
          current={current(editing.row)}
          mode={editing.mode}
          onDismiss={() => setEditing(null)}
          onApply={(e) => {
            edit(editing.row, e)
            setEditing(null)
          }}
        />
      )}
      {recipientsFor && (
        <RecipientsDialog
          recordId={recipientsFor.recordId}
          value={recipientsOf(current(recipientsFor))}
          followNames={followNames}
          onDismiss={() => setRecipientsFor(null)}
          onApply={(list) => {
            edit(recipientsFor, { recipients: list })
            setRecipientsFor(null)
          }}
        />
      )}
      {suggestFor && (
        <Dialog
          title={`‘${suggestFor.title}’ 연결 제안`}
          onDismiss={() => setSuggestFor(null)}
          actions={
            <button type="button" onClick={() => setSuggestFor(null)} className={SECONDARY_BUTTON}>
              닫기
            </button>
          }
        >
          <p className="mb-3 text-[13px] text-muted">이 기록을 어느 프로젝트에 이을지 AI가 제안했어요. 연결하면 ‘추가할 곳’이 그 프로젝트로 따라가요.</p>
          <ul className="flex flex-col gap-3 pb-1">
            {suggestionsOf(suggestFor.recordId).map(({ s, p }) => {
              const note = suggestionNote(s.confidence, s.reasons)
              return (
                <li key={p.id} className="rounded-lg border border-dashed border-border-strong px-4 py-3">
                  <div className="flex items-center gap-2">
                    <span className="flex-1 font-semibold">{p.name}</span>
                    <button type="button" disabled={busy} onClick={() => void decideLink(suggestFor.recordId, p, false)} className="h-8 rounded-full border border-border-strong px-3 text-[13px] font-semibold text-body disabled:opacity-50">
                      아니요
                    </button>
                    <button type="button" disabled={busy} onClick={() => void decideLink(suggestFor.recordId, p, true)} className="h-8 rounded-full bg-primary px-3 text-[13px] font-semibold text-on-primary disabled:opacity-50">
                      연결
                    </button>
                  </div>
                  {s.reason && <p className="mt-1 text-sm text-body">{s.reason}</p>}
                  {s.quote && <p className="mt-1 text-[13px] text-muted">“{s.quote}”</p>}
                  {note && <p className="mt-1 text-xs font-semibold text-warn">{note}</p>}
                </li>
              )
            })}
          </ul>
        </Dialog>
      )}
      {toast.element}
    </>
  )
}

/** 일부만 골랐으면 '–'인 체크(앱 TriStateCheckbox) */
function TriCheckbox({ state, onToggle, label }: { state: 'none' | 'some' | 'all'; onToggle: () => void; label?: string }) {
  const ref = useRef<HTMLInputElement>(null)
  useEffect(() => {
    if (ref.current) ref.current.indeterminate = state === 'some'
  }, [state])
  return <input ref={ref} type="checkbox" checked={state === 'all'} onChange={onToggle} aria-label={label} className="size-[18px] flex-none accent-primary" />
}
