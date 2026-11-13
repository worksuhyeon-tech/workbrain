import { useState } from 'react'
import type { Place } from '../rules/place'
import { candidateDueKind, candidateEdits, candidateValid, type CandidateDue, type OwnerKind } from '../rules/records'
import { Dialog, PRIMARY_BUTTON, SECONDARY_BUTTON } from './Dialog'
import { DuePicker } from './DuePicker'
import { PlaceSelect, placeKeyOf, placeOfKey } from './PlaceSelect'
import { Seg } from './Seg'

const INPUT = 'h-[38px] w-full rounded-md border border-border-strong bg-card px-3 text-sm text-ink'
const OWNERS: { value: OwnerKind; label: string }[] = [
  { value: 'me', label: '나' },
  { value: 'other', label: '다른 사람 (팔로업)' },
  { value: 'unknown', label: '확인 필요' },
]

/** owner: 담당 고치기('다른 사람…'을 고르면 이름 칸부터) · due: 기한 고치기 · create: 결정 후보를 할 일로 만들기(모든 칸 + 어디에) */
export type CandidateEditMode = 'owner' | 'due' | 'create'

/**
 * 할 일 후보 고치기 창(FR-10, 앱 CandidateEditSheet). 고친 값은 승인 때 AI 값 위에 덮어 할 일로 만든다 — 후보 원본은 그대로.
 * 누른 칸 하나만 보여 주고(Q3), create면 모든 칸과 '어디에'를 보여 준다(DEC-48·69).
 * 결과는 승인 함수가 읽는 키 전체(title·owner_kind·assignee·due_*·owner_basis)
 */
export function CandidateEditDialog({
  current,
  mode,
  places = [],
  place = null,
  onDismiss,
  onApply,
}: {
  current: Record<string, unknown>
  mode: CandidateEditMode
  places?: Place[]
  place?: Place | null
  onDismiss: () => void
  onApply: (edits: Record<string, unknown>, place: Place | null) => void
}) {
  const wasVague = current.due_ambiguous === true
  const [title, setTitle] = useState(typeof current.title === 'string' ? current.title : '')
  const [owner, setOwner] = useState<OwnerKind>(mode === 'owner' ? 'other' : ((['me', 'other', 'unknown'] as const).find((o) => o === current.owner_kind) ?? 'unknown'))
  const [assignee, setAssignee] = useState(typeof current.assignee === 'string' ? current.assignee : '')
  // '+ 기한'으로 들어오면(기한 없음) 달력을 바로 펼친다(앱과 같음)
  const [due, setDue] = useState<CandidateDue>(mode === 'due' && candidateDueKind(current) === 'none' ? 'date' : candidateDueKind(current))
  const [date, setDate] = useState(typeof current.due_date === 'string' ? current.due_date : '')
  const [placeKey, setPlaceKey] = useState(placeKeyOf(place))
  const valid = candidateValid(title, owner, assignee, due, date)
  const shows = (f: 'title' | 'owner' | 'due') => mode === 'create' || f === mode
  const dues: { value: CandidateDue; label: string }[] = [
    { value: 'none', label: '없음' },
    { value: 'date', label: '날짜' },
    ...(wasVague ? [{ value: 'vague' as const, label: '모호' }] : []),
  ]

  const apply = () => {
    if (!valid) return
    onApply(candidateEdits(current, { title: title.trim(), owner, assignee: assignee.trim(), due, date }), placeOfKey(places, placeKey))
  }

  return (
    <Dialog
      title={mode === 'create' ? '할 일로 만들기' : mode === 'owner' ? '담당 고치기' : '기한 고치기'}
      onDismiss={onDismiss}
      actions={
        <>
          <button type="button" onClick={onDismiss} className={SECONDARY_BUTTON}>
            취소
          </button>
          <button type="button" disabled={!valid} onClick={apply} className={PRIMARY_BUTTON}>
            {mode === 'create' ? '할 일로 만들기' : '적용'}
          </button>
        </>
      }
    >
      <p className="mb-4 text-[13px] text-muted">
        {mode === 'create' ? '결정 후보를 내 할 일로 옮겨요. 제목·어디에·담당·기한을 확인해 주세요.' : '고친 값으로 할 일이 만들어져요. AI가 낸 후보 원본은 그대로 남아요.'}
      </p>
      <div className="flex flex-col gap-4 pb-1">
        {shows('title') && (
          <label className="flex flex-col gap-1.5">
            <span className="text-[13px] font-bold text-ink">제목</span>
            <input value={title} maxLength={200} onChange={(e) => setTitle(e.target.value)} className={INPUT} autoFocus />
          </label>
        )}
        {mode === 'create' && (
          <label className="flex flex-col gap-1.5">
            <span className="text-[13px] font-bold text-ink">어디에</span>
            <PlaceSelect value={placeKey} places={places} onChange={setPlaceKey} className={INPUT} />
          </label>
        )}
        {shows('owner') && (
          <div className="flex flex-col gap-1.5">
            {mode === 'create' && <span className="text-[13px] font-bold text-ink">담당</span>}
            <div>
              <Seg label="담당" items={OWNERS} value={owner} onChange={setOwner} />
            </div>
            {owner === 'other' && (
              <input value={assignee} maxLength={40} onChange={(e) => setAssignee(e.target.value)} placeholder="이름이나 팀" className={INPUT} autoFocus={mode === 'owner'} />
            )}
          </div>
        )}
        {shows('due') && (
          <div className="flex flex-col gap-1.5">
            {mode === 'create' && <span className="text-[13px] font-bold text-ink">기한</span>}
            <div>
              <Seg label="기한" items={dues} value={due} onChange={setDue} />
            </div>
            {due === 'date' && <DuePicker date={date} onChange={(d) => setDate(d)} />}
            {due === 'vague' && <span className="text-xs text-muted">‘{typeof current.due_text === 'string' ? current.due_text : '모호'}’ · 확인 필요로 둬요</span>}
          </div>
        )}
      </div>
    </Dialog>
  )
}
