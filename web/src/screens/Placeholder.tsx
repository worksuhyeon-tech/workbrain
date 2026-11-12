import { PageHead } from '../components/AppShell'

/** ②·③ 단계에서 만들 화면 자리. 앱에서는 이미 쓸 수 있다고 알려 준다 */
export function Placeholder({ title, hand, step }: { title: string; hand?: string; step: string }) {
  return (
    <>
      <PageHead title={title} hand={hand} />
      <div className="rounded-xl border border-border bg-card px-4 py-8 text-center text-sm text-muted shadow-sm">
        <b className="mb-1.5 block text-base text-ink">PC 웹은 준비 중이에요</b>
        {step}에서 만들어요. 그전까지는 휴대폰 앱에서 써 주세요.
      </div>
    </>
  )
}
