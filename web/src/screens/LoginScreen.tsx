import { useState, type ReactNode } from 'react'
import { BrandLogo, GlassIcon, Icons, type Tint } from '../components/icons'
import { signInWithGoogle } from '../lib/session'
import { useTheme } from '../lib/theme'

const STEPS: { tint: Tint; label: string; desc: string; icon: ReactNode }[] = [
  { tint: 'orange', label: '녹음', desc: '회의·통화·메모', icon: <Icons.records /> },
  {
    tint: 'purple',
    label: '전사·분석',
    desc: '회의록·할 일',
    icon: <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true"><path d="M4 6h16M4 12h10M4 18h7" /></svg>,
  },
  {
    tint: 'pink',
    label: '검토',
    desc: '전후·근거 비교',
    icon: <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true"><path d="M4 7h7l2 2h7" /><path d="M4 7v12h16V9" /></svg>,
  },
  {
    tint: 'green',
    label: '승인',
    desc: '확정·반영',
    icon: <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true"><path d="M20 6 9 17l-5-5" /></svg>,
  },
]

/**
 * 목업 S01 로그인. 목업 오른쪽은 메일 로그인 링크 + AI 사용 동의였지만, 실제 앱은 Google 로그인(DEC-26)이고
 * 동의는 앱에서 이미 서버에 저장되어 있어 Google 단추만 둔다(동의는 ③ 올리기 때 확인)
 */
export function LoginScreen() {
  const { toggle } = useTheme()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const login = async () => {
    setBusy(true)
    setError(null)
    try {
      await signInWithGoogle()
    } catch (e) {
      console.error('Google sign-in failed', e)
      setError('로그인 창을 열지 못했어요. 잠시 뒤 다시 눌러 주세요.')
      setBusy(false)
    }
  }

  return (
    <div className="grid min-h-screen min-[761px]:grid-cols-2">
      <button
        type="button"
        aria-label="테마 전환"
        onClick={toggle}
        className="fixed top-4 right-4 z-40 grid size-[38px] place-items-center rounded-md border border-border bg-card text-muted shadow-sm hover:text-ink"
      >
        <Icons.theme />
      </button>

      {/* 목업 .hero: 은은한 배경 + 흐린 보라·초록 원 두 개 */}
      <aside className="relative hidden items-center justify-center overflow-hidden p-12 min-[761px]:flex" style={{ background: 'var(--hero-bg)' }}>
        <div className="pointer-events-none absolute -top-[60px] -right-[60px] size-[340px] rounded-full opacity-[.22] blur-[80px]" style={{ background: 'var(--g-purple)' }} />
        <div className="pointer-events-none absolute -bottom-[70px] -left-[50px] size-[300px] rounded-full opacity-20 blur-[80px]" style={{ background: 'var(--g-green)' }} />
        <div className="relative z-10 max-w-[420px]">
          <div className="mb-8 flex items-center gap-3">
            <BrandLogo size={48} />
            <div className="leading-[1.1]">
              <div className="text-[26px] font-extrabold tracking-[-0.15px] text-ink">WorkBrain</div>
              <div className="mt-0.5 font-hand text-[22px] text-muted">일의 기억</div>
            </div>
          </div>
          <h1 className="mb-4 text-[38px] leading-[1.18] font-semibold tracking-[-0.23px]">
            회의는 녹음만,
            <br />
            정리는 WorkBrain이.
          </h1>
          <p className="mb-8 text-base leading-[1.6] text-body opacity-90">
            음성 기록을 전사하고 기존 프로젝트와 비교해 변경을 제안해요. 당신은 <b>확인하고 승인만</b> 하면, 근거·이력과 함께 쌓입니다.
          </p>
          <div className="grid grid-cols-2 gap-3">
            {STEPS.map((s) => (
              <div
                key={s.label}
                className="flex items-center gap-3 rounded-xl border p-3 shadow-sm backdrop-blur-[7px]"
                style={{ background: 'var(--glass-front)', borderColor: 'var(--glass-border)' }}
              >
                <GlassIcon tint={s.tint}>{s.icon}</GlassIcon>
                <div>
                  <div className="text-sm font-semibold text-ink">{s.label}</div>
                  <div className="text-xs text-muted">{s.desc}</div>
                </div>
              </div>
            ))}
          </div>
        </div>
      </aside>

      <main className="flex items-center justify-center bg-surface p-6">
        <div className="w-full max-w-[440px]">
          <div className="mb-1.5 font-hand text-2xl leading-none text-primary">다시 오셨네요</div>
          <h2 className="mb-6 text-[28px] font-bold tracking-[-0.17px]">로그인</h2>
          <button
            type="button"
            onClick={login}
            disabled={busy}
            className="flex h-11 w-full items-center justify-center gap-2 rounded-full bg-primary text-[15px] font-semibold text-on-primary hover:bg-primary-hover disabled:opacity-60"
          >
            {busy ? '로그인 창으로 가는 중…' : 'Google로 로그인'}
          </button>
          {error && <p className="mt-3 text-sm text-crit">{error}</p>}
          <p className="mt-3 text-[13px] text-muted">
            공개 가입이 없어요. <b className="text-body">등록된 본인 Google 계정만</b> 로그인됩니다.
          </p>
        </div>
      </main>
    </div>
  )
}
