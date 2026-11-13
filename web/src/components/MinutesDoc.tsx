import type { MinutesDocument } from '../data/records'
import { cn } from '../lib/cn'
import { markedParts } from '../rules/records'

/** [확인 필요]는 빨간 굵게, [미정]은 회색 굵게(사용자 회의록 스킬 표기, 앱 marked) */
export function Marked({ text }: { text: string }) {
  return (
    <>
      {markedParts(text).map((p, i) =>
        p.mark ? (
          <b key={i} className={p.mark === 'crit' ? 'text-crit' : 'text-muted'}>
            {p.text}
          </b>
        ) : (
          <span key={i}>{p.text}</span>
        ),
      )}
    </>
  )
}

/**
 * 회의록 문서 본문(FR-30, 앱 MinutesPane 본문): 문서 제목 · 섹션마다 머리·밑줄·글줄·(있으면) 표.
 * 넓은 화면이라 표는 브라우저가 열 너비를 나누고, 그래도 넘치면 표만 옆으로 민다(앱 tableLayout 대신)
 */
export function MinutesDoc({ doc }: { doc: MinutesDocument }) {
  return (
    <article className="max-w-[860px] text-[15px] leading-relaxed text-body">
      {doc.title?.trim() && <h3 className="mb-1 text-lg font-bold text-ink">{doc.title}</h3>}
      {(doc.sections ?? []).map((s, i) => (
        <section key={i}>
          <h4 className="mt-5 mb-1.5 border-b border-border pb-1.5 text-[15px] font-bold text-ink">{s.heading}</h4>
          {(s.lines ?? []).map((line, j) => (
            <p key={j} className="py-0.5 whitespace-pre-wrap">
              <Marked text={line} />
            </p>
          ))}
          {s.table && s.table.columns.length > 0 && (
            <div className="mt-1.5 overflow-x-auto">
              <table className="w-full border-collapse border border-border text-sm">
                <thead>
                  <tr className="bg-surface">
                    {s.table.columns.map((c, k) => (
                      <th key={k} className="border border-border px-2.5 py-2 text-left font-bold text-ink">
                        <Marked text={c} />
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {(s.table.rows ?? []).map((row, r) => (
                    <tr key={r} className="bg-card">
                      {s.table!.columns.map((_, k) => (
                        <td key={k} className={cn('border border-border px-2.5 py-2 align-top', k === 0 && 'whitespace-nowrap')}>
                          <Marked text={row[k] ?? ''} />
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      ))}
    </article>
  )
}
