import { useEffect, useRef, useState } from 'react'
import { hasConsent } from '../data/upload'
import { cn } from '../lib/cn'
import { ProcessRunner } from '../lib/processRunner'
import { durationText } from '../rules/records'
import { decodeText, parseTranscriptText, type ParsedText } from '../rules/transcriptText'
import { checkFile, defaultKind, RECORD_KINDS, titleFromName, type RecordKind } from '../rules/upload'
import { AttendeesField } from './AttendeesField'
import { PRIMARY_BUTTON, SECONDARY_BUTTON } from './Dialog'
import { Icons } from './icons'
import { Seg } from './Seg'
import { CARD } from './ui'

const INPUT = 'h-[38px] w-full rounded-md border border-border-strong bg-card px-3 text-sm text-ink'
const ACCEPT = '.m4a,.mp4,.mp3,.wav,.ogg,.webm,.flac,.txt,audio/*,text/plain'

/** 고른 파일: 음성(확장자·길이) 또는 받아쓴 글(읽은 결과) */
type Chosen = { file: File; kind: 'audio'; ext: string; durationSec: number } | { file: File; kind: 'text'; parsed: ParsedText }

/** 파일 크기: 1MB 아래는 KB로(받아쓴 글은 대개 수십 KB) */
const sizeText = (bytes: number) => (bytes < 1_048_576 ? `${Math.max(1, Math.round(bytes / 1024))}KB` : `${(bytes / 1_048_576).toFixed(1)}MB`)

/** 음성 길이(초). 브라우저가 못 읽으면 0(서버는 길이 없이도 받는다) */
function audioDuration(file: File): Promise<number> {
  return new Promise((resolve) => {
    const url = URL.createObjectURL(file)
    const audio = new Audio()
    const done = (sec: number) => {
      URL.revokeObjectURL(url)
      resolve(Number.isFinite(sec) ? sec : 0)
    }
    const timer = window.setTimeout(() => done(0), 5000)
    audio.preload = 'metadata'
    audio.onloadedmetadata = () => {
      window.clearTimeout(timer)
      done(audio.duration)
    }
    audio.onerror = () => {
      window.clearTimeout(timer)
      done(0)
    }
    audio.src = url
  })
}

/**
 * 기록 화면 [파일 올리기] 칸(웹 목업 record.html .up-panel + 앱 UploadSheet): 끌어다 놓기·고르기(음성 25MB·받아쓴 글 txt 1MB),
 * 제목 · 어떤 기록인지 · 참석자(메모 빼고) · 처음 한 번 외부 AI 처리 동의(FR-17). 예전의 '클라우드에 올려도 되나요?'(M11)는 묻지 않는다(DEC-106).
 * 올리면 이 탭에서 처리까지 이어 간다(ProcessRunner). PC에서는 녹음하지 않는다
 */
export function UploadPanel({ onClose, onStarted }: { onClose: () => void; onStarted: (title: string) => void }) {
  const input = useRef<HTMLInputElement>(null)
  const [chosen, setChosen] = useState<Chosen | null>(null)
  const [reject, setReject] = useState<string | null>(null)
  const [reading, setReading] = useState(false)
  const [drag, setDrag] = useState(false)
  const [title, setTitle] = useState('')
  const [kind, setKind] = useState<RecordKind>('meeting')
  const [attendees, setAttendees] = useState<string[]>([])
  // null = 확인 중. 5초 안에 답이 없거나 실패하면 '동의 필요'로 보고 동의 칸을 보여 준다(앱과 같음)
  const [needConsent, setNeedConsent] = useState<boolean | null>(null)
  const [consent, setConsent] = useState(false)

  useEffect(() => {
    let alive = true
    const timer = window.setTimeout(() => alive && setNeedConsent((v) => v ?? true), 5000)
    hasConsent().then(
      (ok) => alive && setNeedConsent(!ok),
      () => alive && setNeedConsent(true),
    )
    return () => {
      alive = false
      window.clearTimeout(timer)
    }
  }, [])

  const pick = async (file: File | undefined) => {
    if (!file) return
    setReject(null)
    const c = checkFile(file.name, file.size)
    if (c.kind === 'rejected') {
      setChosen(null)
      setReject(c.reason)
      return
    }
    setReading(true)
    let next: Chosen
    if (c.kind === 'audio') {
      next = { file, kind: 'audio', ext: c.ext, durationSec: await audioDuration(file) }
    } else {
      const parsed = parseTranscriptText(decodeText(new Uint8Array(await file.arrayBuffer())))
      if (!parsed.body.trim()) {
        setReading(false)
        setChosen(null)
        setReject('받아쓴 글이 없어요.')
        return
      }
      next = { file, kind: 'text', parsed }
    }
    setReading(false)
    setChosen(next)
    const t = (next.kind === 'text' ? next.parsed.title?.trim().slice(0, 80) : null) || titleFromName(file.name) || (next.kind === 'text' ? '가져온 받아쓰기' : '가져온 음성')
    setTitle(t)
    setKind(defaultKind(t))
  }

  const canSend = !!chosen && !!title.trim() && (needConsent === false || (needConsent === true && consent))

  const send = () => {
    if (!chosen || !canSend) return
    const finalTitle = title.trim().slice(0, 200)
    ProcessRunner.upload({
      record: {
        channel: chosen.kind === 'text' ? 'text-import' : 'upload',
        title: finalTitle,
        // 회의 시각: 받아쓴 글에 적힌 시작 시각, 없으면 파일을 마지막으로 고친 시각
        occurredAt: (chosen.kind === 'text' ? chosen.parsed.startedAt : null) ?? new Date(chosen.file.lastModified || Date.now()),
        kind,
        attendees: kind === 'memo' ? [] : attendees,
      },
      content: chosen.kind === 'audio' ? { kind: 'audio', file: chosen.file, ext: chosen.ext, durationSec: chosen.durationSec } : { kind: 'text', body: chosen.parsed.body, turns: chosen.parsed.turns },
      consentGiven: consent,
    })
    onStarted(finalTitle)
  }

  const meta = chosen
    ? [
        sizeText(chosen.file.size),
        chosen.kind === 'audio' ? (chosen.durationSec > 0 ? durationText(chosen.durationSec) : null) : `받아쓴 글${chosen.parsed.turns.length ? ` · 말한 차례 ${chosen.parsed.turns.length}` : ''}`,
      ].filter(Boolean)
    : []

  return (
    <section aria-label="파일 올리기" className={cn(CARD, 'mb-5 px-6 py-5')}>
      <input ref={input} type="file" accept={ACCEPT} className="hidden" onChange={(e) => void pick(e.target.files?.[0])} />
      <button
        type="button"
        onClick={() => input.current?.click()}
        onDragOver={(e) => {
          e.preventDefault()
          setDrag(true)
        }}
        onDragLeave={() => setDrag(false)}
        onDrop={(e) => {
          e.preventDefault()
          setDrag(false)
          void pick(e.dataTransfer.files[0])
        }}
        className={cn('flex w-full flex-col items-center gap-1 rounded-xl border-2 border-dashed px-4 py-6 text-center', drag ? 'border-primary bg-info-soft' : 'border-border-strong bg-surface hover:border-primary')}
      >
        {chosen ? (
          <>
            <span className="inline-flex items-center gap-2 text-[15px] font-bold text-ink">
              <span className="text-muted">{chosen.kind === 'text' ? <Icons.doc /> : <Icons.mic />}</span>
              {chosen.file.name}
            </span>
            <span className="text-[13px] text-muted">{meta.join(' · ')} · 다른 파일을 고르려면 누르거나 끌어다 놓아요</span>
          </>
        ) : (
          <>
            <b className="text-[15px] text-ink">{reading ? '파일을 읽는 중…' : '녹음 파일이나 받아쓴 글(txt)을 여기에 끌어다 놓거나 눌러서 고르세요'}</b>
            <span className="text-[13px] text-muted">m4a · mp3 · wav · webm · ogg · flac 25MB(약 100분)까지 · 클로바노트·삼성 음성 녹음 txt 1MB까지</span>
          </>
        )}
      </button>
      {reject && <p className="mt-2 text-sm text-crit">{reject}</p>}

      {chosen && (
        <>
          {/* 왼쪽 제목·종류, 오른쪽 참석자, 처음 한 번 동의는 아래 한 줄로 */}
          <div className="mt-5 grid gap-5 min-[1100px]:grid-cols-2">
            <div className="flex flex-col gap-4">
              <label className="flex flex-col gap-1.5">
                <span className="text-[13px] font-bold text-ink">제목</span>
                <input value={title} maxLength={200} onChange={(e) => setTitle(e.target.value)} placeholder="예: 청구 화면 2차 범위 회의" className={INPUT} />
              </label>
              <div className="flex flex-col gap-1.5">
                <span className="text-[13px] font-bold text-ink">어떤 기록인가요?</span>
                <div>
                  <Seg label="기록 종류" items={RECORD_KINDS} value={kind} onChange={setKind} />
                </div>
                <span className="text-xs text-muted">메모는 '제가 할게요'를 내 할 일로, 회의·통화는 '담당 확인'으로 정리해요.</span>
              </div>
            </div>
            {kind !== 'memo' && (
              <div className="flex flex-col gap-1.5">
                <span className="text-[13px] font-bold text-ink">참석자 (선택)</span>
                <span className="text-xs text-muted">이름을 넣으면 받아쓰기에서 잘못 들린 이름을 바로잡아요. 명단만 보고 담당자를 정하지는 않아요.</span>
                <AttendeesField value={attendees} onChange={setAttendees} />
              </div>
            )}
          </div>
          {needConsent === null && <p className="mt-4 text-xs text-muted">외부 AI 처리 동의 여부를 확인하고 있어요…</p>}
          {needConsent === true && (
            <div className="mt-4 rounded-lg bg-surface px-4 py-3">
              <b className="text-sm text-ink">외부 AI 처리 동의 (처음 한 번)</b>
              <p className="mt-1 text-xs leading-relaxed text-body">
                음성은 OpenAI 전사(gpt-transcribe)로, 받아쓴 글은 OpenAI 분석(gpt-6-luna)으로 보내 회의록·할 일 후보를 만들어요. 내가 승인하기 전까지 아무것도 바뀌지 않아요. 음성은 30일 뒤 자동으로 지워요.
              </p>
              <label className="mt-2 flex cursor-pointer items-center gap-2 text-sm font-semibold text-ink">
                <input type="checkbox" checked={consent} onChange={(e) => setConsent(e.target.checked)} className="size-4 accent-primary" />
                동의해요
              </label>
            </div>
          )}
        </>
      )}

      <div className="mt-5 flex flex-wrap items-center gap-2">
        <span className="flex-1 text-xs text-muted">PC에서는 녹음하지 않아요. 녹음은 휴대폰 앱에서 하고, 여기서는 이미 있는 파일만 올려요. 올린 뒤에는 이 창(탭)을 열어 두면 정리까지 이어서 해요.</span>
        <button type="button" onClick={onClose} className={SECONDARY_BUTTON}>
          닫기
        </button>
        <button type="button" disabled={!canSend} onClick={send} className={PRIMARY_BUTTON}>
          올리기
        </button>
      </div>
    </section>
  )
}
