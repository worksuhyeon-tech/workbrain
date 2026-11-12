import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AppShell } from './components/AppShell'
import { useAuth } from './lib/session'
import { LoginScreen } from './screens/LoginScreen'
import { Placeholder } from './screens/Placeholder'
import { TodayScreen } from './screens/TodayScreen'

export function App() {
  const auth = useAuth()

  // 저장된 로그인을 다 읽기 전에는 아무 화면도 서버를 부르지 않는다(lib/session.ts)
  if (auth.kind === 'loading') return <div className="grid min-h-screen place-items-center text-sm text-muted">불러오는 중…</div>
  if (auth.kind === 'signedOut') return <LoginScreen />

  return (
    <BrowserRouter>
      <AppShell email={auth.session.user.email ?? ''}>
        <Routes>
          <Route path="/" element={<TodayScreen />} />
          <Route path="/projects/*" element={<Placeholder title="프로젝트" hand="하고 있는 일 한눈에" step="① 할 일 단계" />} />
          <Route path="/tasks" element={<Placeholder title="모든 할 일" hand="하고 있는 일 한눈에" step="① 할 일 단계" />} />
          <Route path="/search" element={<Placeholder title="검색" hand="그때 뭐라고 했더라" step="① 할 일 단계" />} />
          <Route path="/records/*" element={<Placeholder title="기록" hand="회의·통화·메모" step="② 검토·회의록 단계" />} />
          <Route path="/review" element={<Placeholder title="검토함" hand="꼼꼼히 살펴봐요" step="② 검토·회의록 단계" />} />
          <Route path="/settings" element={<Placeholder title="설정" hand="내 방식대로 맞추기" step="나중 단계" />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </AppShell>
    </BrowserRouter>
  )
}
