import { useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { isDone } from '../data/types'
import { liveTasks, openCount, recordsOf, recordMeta, type ParaData, type ResourceRow } from '../data/para'
import { cn } from '../lib/cn'
import { Icons } from './icons'

/** 자료 종류 그림(앱 ParaGlyph.resource): 링크 · 메모 · 기록 참조 */
export function resourceIcon(kind: string): ReactNode {
  return kind === 'link' ? <Icons.link /> : kind === 'record-ref' ? <Icons.mic /> : <Icons.doc />
}

type SectionNo = 1 | 2 | 3 | 4
const SECTIONS: { no: SectionNo; label: string; en: string }[] = [
  { no: 1, label: '1. 프로젝트', en: 'Projects' },
  { no: 2, label: '2. 반복실행', en: 'Areas' },
  { no: 3, label: '3. 관심영역', en: 'Resources' },
  { no: 4, label: '4. 아카이브', en: 'Archives' },
]

function Row({ to, selected, depth = 0, icon, label, count, muted, toggle }: {
  to: string
  selected: boolean
  depth?: number
  icon: ReactNode
  label: string
  count?: number | string | null
  muted?: boolean
  toggle?: ReactNode
}) {
  return (
    <div className={cn('flex min-h-[34px] items-center gap-1 rounded-md pr-2', selected ? 'bg-sky font-semibold text-ink' : 'hover:bg-surface')} style={{ paddingLeft: 8 + depth * 18 }}>
      <span className="grid w-4 flex-none place-items-center text-muted">{toggle}</span>
      <Link to={to} aria-current={selected ? 'page' : undefined} className={cn('flex min-w-0 flex-1 items-center gap-2 py-1.5 text-[15px]', muted ? 'text-muted' : 'text-body', selected && 'text-ink')}>
        <span className="flex-none">{icon}</span>
        <span className="truncate">{label}</span>
        {count != null && count !== 0 && <span className="ml-auto flex-none text-[13px] font-semibold text-muted tabular-nums">{count}</span>}
      </Link>
    </div>
  )
}

/**
 * PARA 트리(목업 S03 .side, 앱 ParaTree): 공간 칸 · 모든 할 일 · 1. 프로젝트(펼치면 연결된 기록) · 2. 반복실행 · 3. 관심영역 · 4. 아카이브.
 * 숫자는 프로젝트·반복실행은 안 끝난 상위 할 일, 칸 머리는 항목 수. 아카이브는 처음에 접어 둔다
 */
export function ParaTree({ d, selected }: { d: ParaData; selected: string | null }) {
  const [openSections, setOpenSections] = useState<ReadonlySet<SectionNo>>(new Set([1, 2, 3]))
  const [openProjects, setOpenProjects] = useState<ReadonlySet<string>>(new Set())
  const flip = <T,>(set: ReadonlySet<T>, v: T) => {
    const next = new Set(set)
    if (next.has(v)) next.delete(v)
    else next.add(v)
    return next
  }

  const projects = d.projects.filter((p) => !p.archived_at)
  const areas = d.areas.filter((a) => !a.archived_at)
  const resources = d.resources.filter((r) => !r.archived_at)
  const archived = [
    ...d.projects.filter((p) => p.archived_at).map((p) => ({ key: `p:${p.id}`, icon: <Icons.folderBig />, label: p.name })),
    ...d.areas.filter((a) => a.archived_at).map((a) => ({ key: `a:${a.id}`, icon: <Icons.repeatBig />, label: a.name })),
    ...d.resources.filter((r) => r.archived_at).map((r: ResourceRow) => ({ key: `r:${r.id}`, icon: resourceIcon(r.kind), label: r.title })),
  ]
  const counts: Record<SectionNo, number> = { 1: projects.length, 2: areas.length, 3: resources.length, 4: archived.length }

  return (
    <nav aria-label="PARA 트리" className="flex flex-col gap-0.5">
      <div className="mb-2 flex items-center gap-3 rounded-lg border border-border bg-card px-3 py-2.5">
        <span className="text-muted">
          <Icons.building />
        </span>
        <div className="min-w-0">
          <div className="text-[15px] font-bold text-ink">{d.workspace.name}</div>
          {d.workspace.description && <div className="truncate text-xs text-muted">{d.workspace.description}</div>}
        </div>
      </div>

      {/* 아무것도 안 고른 프로젝트 화면 = 모든 할 일(앱 펼친 화면과 같음) */}
      <Row
        to="/projects"
        selected={selected === null}
        icon={<span className="text-all-tasks"><Icons.allTasks /></span>}
        label="모든 할 일"
        count={liveTasks(d).filter((t) => !isDone(t)).length}
      />

      {SECTIONS.map((s) => {
        const open = openSections.has(s.no)
        return (
          <div key={s.no} className="mt-2">
            <button
              type="button"
              onClick={() => setOpenSections((o) => flip(o, s.no))}
              aria-expanded={open}
              className="flex w-full items-center gap-1 rounded-md py-1.5 pr-2 pl-2 text-left hover:bg-surface"
            >
              <span className="grid w-4 place-items-center text-muted">
                <Icons.chevron open={open} />
              </span>
              <span className="text-sm font-bold text-ink">{s.label}</span>
              <span className="text-xs text-muted">{s.en}</span>
              <span className="ml-auto text-[13px] font-semibold text-muted tabular-nums">{counts[s.no]}</span>
            </button>
            {open && s.no === 1 &&
              projects.map((p) => {
                const records = recordsOf(d, p.id)
                const recOpen = openProjects.has(p.id)
                return (
                  <div key={p.id}>
                    <Row
                      to={`/projects/p:${p.id}`}
                      selected={selected === `p:${p.id}`}
                      depth={1}
                      icon={<span className="text-tree-folder"><Icons.folderBig /></span>}
                      label={p.name}
                      count={openCount(d, 'PROJECT', p.id)}
                      toggle={
                        records.length > 0 ? (
                          <button type="button" aria-label={recOpen ? '기록 접기' : '기록 펼치기'} aria-expanded={recOpen} onClick={() => setOpenProjects((o) => flip(o, p.id))}>
                            <Icons.chevron open={recOpen} />
                          </button>
                        ) : null
                      }
                    />
                    {recOpen &&
                      records.map((r) => (
                        <Row key={r.id} to={`/records/${r.id}`} selected={false} depth={2} icon={<span className="text-muted"><Icons.mic /></span>} label={r.title} count={recordMeta(r).split(' · ')[0]} muted />
                      ))}
                  </div>
                )
              })}
            {open && s.no === 2 &&
              areas.map((a) => (
                <Row key={a.id} to={`/projects/a:${a.id}`} selected={selected === `a:${a.id}`} depth={1} icon={<span className="text-area-icon"><Icons.repeatBig /></span>} label={a.name} count={openCount(d, 'AREA', a.id)} />
              ))}
            {open && s.no === 3 &&
              resources.map((r) => (
                <Row key={r.id} to={`/projects/r:${r.id}`} selected={selected === `r:${r.id}`} depth={1} icon={<span className="text-muted">{resourceIcon(r.kind)}</span>} label={r.title} />
              ))}
            {open && s.no === 4 &&
              archived.map((x) => <Row key={x.key} to={`/projects/${x.key}`} selected={selected === x.key} depth={1} icon={<span className="text-muted">{x.icon}</span>} label={x.label} muted />)}
          </div>
        )
      })}

      <p className="mt-4 border-t border-border px-2 pt-3 text-xs leading-relaxed text-muted">
        <b>1</b> 끝이 있는 일 · <b>2</b> 계속 맡는 일 · <b>3</b> 참고 자료 · <b>4</b> 끝난 것(복원 가능)
      </p>
    </nav>
  )
}
