import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { PageHead } from '../components/AppShell'
import { Icons } from '../components/icons'
import { Seg } from '../components/Seg'
import { useToast } from '../components/Toast'
import { UploadPanel } from '../components/UploadPanel'
import { CARD, TONE_CHIP } from '../components/ui'
import { loadRecords, type ProjectName, type RecordListRow } from '../data/records'
import { cn } from '../lib/cn'
import { ProcessRunner, useProcessing, type Progress } from '../lib/processRunner'
import {
  CHANNEL_LABEL,
  CLASS_LABEL,
  RECORD_FILTERS,
  dayLabel,
  durationText,
  groupByDay,
  inFilter,
  recordStage,
  stageChip,
  stageNote,
  type RecordFilter,
  type RecordStage,
} from '../rules/records'
import { seoulTime } from '../rules/tasks'

const byName = new Intl.Collator('ko').compare
const LOAD_FAILED = '기록을 불러오지 못했어요. 인터넷 연결을 확인해 주세요.'

interface Item {
  r: RecordListRow
  stage: RecordStage
}

/**
 * 기록 목록(웹 목업 record.html): 서버의 내 기록을 회의 날짜로 묶어 보여 준다.
 * 상태(처리 중·처리 실패·정리 필요·정리 끝)와 덧말, 탭·프로젝트로 거르기. 누르면 기록 상세(회의록·할 일 후보·전사).
 * 앱의 기록 탭은 휴대폰 안 녹음 목록이라 모양만 목업을 따르고 내용은 서버 처리 상태로 정했다
 */
export function RecordsScreen() {
  const [data, setData] = useState<{ records: RecordListRow[]; projects: ProjectName[] } | null>(null)
  const [failed, setFailed] = useState(false)
  const [filter, setFilter] = useState<RecordFilter>('all')
  const [projectId, setProjectId] = useState('')
  const [uploading, setUploading] = useState(false)
  const toast = useToast()
  const processing = useProcessing()

  const reload = useCallback(() => {
    setFailed(false)
    loadRecords().then(setData, (e) => {
      console.error('load records failed', e)
      setFailed(true)
    })
  }, [])
  useEffect(reload, [reload])
  // 이 탭에서 올린 기록이 서버에 생기거나 처리가 끝나면 목록을 다시 읽는다
  const progressKey = [...processing].map(([id, p]) => `${id}:${p.created}:${p.busy}`).join(',')
  useEffect(() => {
    if (progressKey) reload()
  }, [progressKey, reload])

  const items: Item[] = useMemo(() => (data?.records ?? []).map((r) => ({ r, stage: recordStage(r.job, r.proposals) })), [data])
  const names = useMemo(() => new Map((data?.projects ?? []).map((p) => [p.id, p])), [data])
  // 거르기 목록: 기록이 하나라도 연결된 프로젝트만(이름순)
  const filterProjects = useMemo(
    () => [...new Set(items.flatMap((x) => x.r.projectIds))].flatMap((id) => names.get(id) ?? []).sort((a, b) => byName(a.name, b.name)),
    [items, names],
  )
  const inProject = items.filter((x) => !projectId || x.r.projectIds.includes(projectId))
  const shown = inProject.filter((x) => inFilter(x.stage, filter))
  const groups = groupByDay(shown, (x) => x.r.at)

  return (
    <>
      <PageHead title="기록" hand="회의·통화·메모">
        <button
          type="button"
          aria-expanded={uploading}
          onClick={() => setUploading((v) => !v)}
          className="flex h-10 items-center gap-1.5 rounded-full bg-primary px-5 text-sm font-semibold text-on-primary hover:bg-primary-hover"
        >
          <Icons.upload /> 파일 올리기
        </button>
      </PageHead>

      {uploading && (
        <UploadPanel
          onClose={() => setUploading(false)}
          onStarted={(title) => {
            setUploading(false)
            toast.show(`‘${title}’을(를) 올리고 있어요 · 이 창을 열어 두면 정리까지 이어서 해요`)
          }}
        />
      )}
      {processing.size > 0 && <ProcessingList processing={processing} />}

      {failed && (
        <div className="mb-4 flex items-center gap-3 rounded-lg bg-crit-soft p-3 text-sm text-crit">
          <span className="flex-1">{LOAD_FAILED}</span>
          <button type="button" onClick={reload} className="font-semibold underline">
            다시 불러오기
          </button>
        </div>
      )}

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Seg
          label="상태"
          items={RECORD_FILTERS.map((f) => ({ ...f, count: inProject.filter((x) => inFilter(x.stage, f.value)).length }))}
          value={filter}
          onChange={setFilter}
        />
        <select
          aria-label="프로젝트로 거르기"
          value={projectId}
          onChange={(e) => setProjectId(e.target.value)}
          className="h-[38px] rounded-md border border-border-strong bg-card px-3 text-sm text-ink"
        >
          <option value="">모든 프로젝트</option>
          {filterProjects.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
              {p.archived_at ? ' (보관됨)' : ''}
            </option>
          ))}
        </select>
      </div>

      {!data && !failed && <p className="py-10 text-center text-sm text-muted">불러오는 중…</p>}

      {data && shown.length === 0 && (
        <div className={cn(CARD, 'mb-4 px-6 py-10 text-center')}>
          <p className="text-[15px] font-semibold text-ink">{items.length === 0 ? '아직 기록이 없어요' : '여기엔 기록이 없어요'}</p>
          <p className="mt-1 text-[13px] text-muted">
            {items.length === 0 ? '휴대폰 앱에서 녹음하거나 파일을 올리면 여기에 보여요.' : '다른 탭이나 다른 프로젝트를 골라 보세요.'}
          </p>
        </div>
      )}

      {groups.map((g) => (
        <section key={g.day ?? 'none'} aria-label={g.day ? dayLabel(g.day) : '날짜 모름'} className="mb-5">
          <h2 className="mb-2 text-[13px] font-bold text-muted">{g.day ? dayLabel(g.day) : '날짜 모름'}</h2>
          <div className={cn(CARD, 'divide-y divide-border overflow-hidden')}>
            {g.rows.map((x) => (
              <RecordRow key={x.r.id} item={x} names={names} />
            ))}
          </div>
        </section>
      ))}

      {data && (
        <p className="text-xs leading-relaxed text-muted">
          기록을 누르면 <b>회의록 · 할 일 후보 · 전사</b>를 보는 기록 화면으로 가요. 할 일 후보는 그 화면에서 골라 바로 추가해요.
        </p>
      )}
      {toast.element}
    </>
  )
}

/** 목업 .rec-row: 종류 아이콘 · 제목·덧말·프로젝트 칩 · 오른쪽 상태 칩과 덧말 */
function RecordRow({ item: { r, stage }, names }: { item: Item; names: Map<string, ProjectName> }) {
  const chip = stageChip(stage, r.job)
  const note = stageNote(stage, r.proposals)
  const time = r.at ? seoulTime(r.at) : null
  const kind = r.channel ? CHANNEL_LABEL[r.channel] : undefined
  const meta = [
    time,
    kind && (r.durationSec ? `${kind} ${durationText(r.durationSec)}` : kind),
    r.attendees.length > 0 && r.attendees.join('·'),
    r.audioPurged && '원본 음성 만료',
  ].filter(Boolean)
  const projects = r.projectIds.flatMap((id) => names.get(id) ?? [])
  const cls = r.classification === 'personal' || r.classification === 'uncertain' ? r.classification : null
  // 끝까지 처리된 업무 기록인데 프로젝트가 없으면 이어 달라고 알린다(목업 '프로젝트 연결 필요')
  const needsProject = projects.length === 0 && !cls && (stage === 'review' || stage === 'done')

  return (
    <Link to={`/records/${r.id}`} className="flex items-center gap-4 px-5 py-4 text-ink no-underline hover:bg-surface">
      <span className="grid w-6 flex-none place-items-center text-muted">{r.channel === 'call-share' ? <Icons.phone /> : r.channel === 'text-import' ? <Icons.doc /> : <Icons.mic />}</span>
      <div className="min-w-0 flex-1">
        <div className="truncate text-[15px] font-bold">{r.title}</div>
        {meta.length > 0 && <div className="mt-0.5 text-[13px] text-muted">{meta.join(' · ')}</div>}
        {(projects.length > 0 || cls || needsProject) && (
          <div className="mt-1.5 flex flex-wrap gap-1.5">
            {cls && <span className={cn('rounded-full px-2.5 py-[3px] text-xs font-bold', TONE_CHIP[cls === 'uncertain' ? 'warn' : 'mute'])}>{CLASS_LABEL[cls]}</span>}
            {projects.map((p) => (
              <span key={p.id} className={cn('inline-flex items-center gap-1 rounded-full border border-border bg-surface px-2.5 py-[3px] text-xs font-semibold', p.archived_at ? 'text-muted' : 'text-body')}>
                <span className={p.archived_at ? 'text-muted' : 'text-tree-folder'}>
                  <Icons.folder />
                </span>
                {p.name}
              </span>
            ))}
            {needsProject && <span className={cn('rounded-full px-2.5 py-[3px] text-xs font-bold', TONE_CHIP.warn)}>프로젝트 연결 필요</span>}
          </div>
        )}
      </div>
      <div className="flex flex-none flex-col items-end gap-1">
        <span className={cn('rounded-full px-2.5 py-[3px] text-xs font-bold whitespace-nowrap', TONE_CHIP[chip.tone])}>{chip.text}</span>
        {note && <span className="text-xs text-muted">{note}</span>}
      </div>
    </Link>
  )
}

/**
 * 이 탭에서 올리거나 처리 중인 기록(③): 제목 · 지금 단계(돌아가는 표시·실패는 빨강) · [열기] · 끝난 줄은 [닫기].
 * 처리는 이 탭이 열려 있는 동안만 이어진다(서버가 스스로 진행하지 않음)
 */
function ProcessingList({ processing }: { processing: ReadonlyMap<string, Progress> }) {
  return (
    <section aria-label="올리는 중" className={cn(CARD, 'mb-5 divide-y divide-border overflow-hidden')}>
      {[...processing].reverse().map(([id, p]) => (
        <div key={id} className="flex flex-wrap items-center gap-3 px-5 py-3">
          {p.busy ? (
            <span className="inline-block size-4 flex-none animate-spin rounded-full border-2 border-primary border-t-transparent" aria-hidden="true" />
          ) : (
            <span className={cn('grid size-4 flex-none place-items-center text-sm font-bold', p.error ? 'text-crit' : 'text-good')}>{p.error ? '!' : '✓'}</span>
          )}
          <span className="min-w-0 flex-1">
            <span className="block truncate text-[15px] font-bold text-ink">{p.title}</span>
            <span className={cn('text-[13px]', p.error ? 'text-crit' : p.busy ? 'text-primary' : 'text-good')}>{p.message}</span>
          </span>
          {p.created && (
            <Link to={`/records/${id}`} className="text-[13px] font-semibold">
              열기 →
            </Link>
          )}
          {!p.busy && (
            <button type="button" onClick={() => ProcessRunner.dismiss(id)} className="text-[13px] font-semibold text-muted hover:text-ink">
              닫기
            </button>
          )}
        </div>
      ))}
    </section>
  )
}

