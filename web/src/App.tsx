import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AppShell } from './components/AppShell'
import { useAuth } from './lib/session'
import { LoginScreen } from './screens/LoginScreen'
import { Placeholder } from './screens/Placeholder'
import { ProjectsScreen } from './screens/ProjectsScreen'
import { RecordDetailScreen } from './screens/RecordDetailScreen'
import { RecordsScreen } from './screens/RecordsScreen'
import { ReviewScreen } from './screens/ReviewScreen'
import { SearchScreen } from './screens/SearchScreen'
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
          {/* 고른 항목이 바뀌어도 화면을 새로 열지 않게(트리 접힘 유지) 주소 하나로 */}
          <Route path="/projects/:key?" element={<ProjectsScreen />} />
          {/* 모든 할 일은 프로젝트 화면 안(목업 S07·앱과 같음). 예전 주소로 와도 그곳으로 */}
          <Route path="/tasks" element={<Navigate to="/projects" replace />} />
          <Route path="/search" element={<SearchScreen />} />
          <Route path="/records" element={<RecordsScreen />} />
          <Route path="/records/:id" element={<RecordDetailScreen />} />
          <Route path="/review" element={<ReviewScreen />} />
          <Route path="/settings" element={<Placeholder title="설정" hand="내 방식대로 맞추기" step="나중 단계" />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </AppShell>
    </BrowserRouter>
  )
}
