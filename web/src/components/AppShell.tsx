import { useState, type ReactNode } from 'react'
import { NavLink, useNavigate } from 'react-router-dom'
import { BrandLogo, Icons } from './icons'
import { cn } from '../lib/cn'
import { signOut } from '../lib/session'
import { useTheme } from '../lib/theme'

type NavItem = { to: string; label: string; icon: () => ReactNode; end?: boolean }

// 목업 레일 순서 그대로. '모든 할 일'은 레일에 없고 오늘 카드 '다음 7일 · 전체 →'로 간다
const NAV: NavItem[] = [
  { to: '/', label: '오늘', icon: Icons.today, end: true },
  { to: '/projects', label: '프로젝트', icon: Icons.projects },
  { to: '/records', label: '기록', icon: Icons.records },
  { to: '/review', label: '검토함', icon: Icons.review },
  { to: '/search', label: '검색', icon: Icons.search },
]
const SETTINGS: NavItem = { to: '/settings', label: '설정', icon: Icons.settings }

function RailLink({ item }: { item: NavItem }) {
  return (
    <NavLink
      to={item.to}
      end={item.end}
      className={({ isActive }) =>
        cn(
          'flex h-10 w-full items-center justify-center gap-3 rounded-md text-[15px] font-medium text-body hover:bg-surface min-[761px]:justify-start min-[761px]:px-3',
          '[&>svg]:flex-none [&>svg]:text-muted',
          isActive && 'bg-primary/12 font-semibold text-ink [&>svg]:text-primary',
        )
      }
    >
      {item.icon()}
      <span className="hidden min-[761px]:inline">{item.label}</span>
    </NavLink>
  )
}

/** 목업 .app: 왼쪽 레일(240px, 760px 이하는 64px 아이콘만) + 위 막대(56px) + 본문 */
export function AppShell({ email, children }: { email: string; children: ReactNode }) {
  const navigate = useNavigate()
  const { toggle } = useTheme()
  const [menuOpen, setMenuOpen] = useState(false)

  return (
    <div className="grid h-screen grid-cols-[64px_1fr] grid-rows-[56px_1fr] [grid-template-areas:'rail_topbar''rail_main'] min-[761px]:grid-cols-[240px_1fr]">
      <nav aria-label="주 탐색" className="flex flex-col gap-1 overflow-hidden border-r border-border bg-card p-3 [grid-area:rail]">
        <div className="flex items-center gap-3 px-2 pt-2 pb-4">
          <BrandLogo />
          <div className="hidden min-[761px]:block">
            <div className="text-xl font-bold tracking-[-0.4px] text-ink">WorkBrain</div>
            <div className="mt-0.5 font-hand text-[21px] leading-none text-muted">일의 기억</div>
          </div>
        </div>
        {NAV.map((item) => <RailLink key={item.to} item={item} />)}
        <div className="m-2 h-px bg-border" />
        <RailLink item={SETTINGS} />
      </nav>

      <header className="flex items-center gap-4 border-b border-border bg-card px-6 [grid-area:topbar]">
        <form
          role="search"
          className="flex h-[34px] max-w-[520px] flex-1 items-center gap-2 rounded-md border border-border bg-surface px-3 text-[15px] text-muted"
          onSubmit={(e) => {
            e.preventDefault()
            const q = new FormData(e.currentTarget).get('q')?.toString().trim() ?? ''
            navigate(q ? `/search?q=${encodeURIComponent(q)}` : '/search')
          }}
        >
          <Icons.search size={15} />
          <input name="q" type="text" placeholder="프로젝트·회의록·할 일 검색" aria-label="검색" className="w-full border-0 bg-transparent text-body outline-none" />
        </form>
        <div className="flex-1" />
        <button type="button" aria-label="테마 전환" onClick={toggle} className="grid size-[34px] place-items-center rounded-md text-muted hover:bg-surface hover:text-ink">
          <Icons.theme />
        </button>
        <div className="relative">
          <button
            type="button"
            aria-label="계정"
            aria-expanded={menuOpen}
            onClick={() => setMenuOpen((v) => !v)}
            className="grid size-8 place-items-center rounded-full text-sm font-bold text-white"
            style={{ background: 'var(--g-purple)' }}
          >
            {(email[0] ?? 'W').toUpperCase()}
          </button>
          {menuOpen && (
            <div className="absolute top-10 right-0 z-40 w-64 rounded-lg border border-border bg-card p-2 shadow-lg">
              <div className="truncate px-2 py-1.5 text-[13px] text-muted">{email}</div>
              <button type="button" onClick={() => void signOut()} className="w-full rounded-md px-2 py-2 text-left text-sm font-semibold text-body hover:bg-surface">
                로그아웃
              </button>
            </div>
          )}
        </div>
      </header>

      <main className="overflow-y-auto p-4 [grid-area:main] min-[761px]:px-8 min-[761px]:pt-6 min-[761px]:pb-8">{children}</main>
    </div>
  )
}

/** 목업 .page-head: 큰 제목 + 손글씨 부제 + 아래 작은 줄 */
export function PageHead({ title, hand, sub, children }: { title: string; hand?: string; sub?: string; children?: ReactNode }) {
  return (
    <div className="mb-6">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-[30px] font-semibold tracking-[-0.6px]">{title}</h1>
        {hand && <span className="font-hand text-xl text-muted">{hand}</span>}
        <div className="flex-1" />
        {children}
      </div>
      {sub && <div className="mt-1 text-sm text-muted">{sub}</div>}
    </div>
  )
}
