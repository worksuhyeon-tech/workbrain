/*
 * 사람 이름 맞추기(받는 사람 고르기, DEC-77). 회의 참석자 명단은 같은 사람을 '홍길동', '홍길동 실장', '홍길동님'처럼
 * 다르게 적는 일이 많아(11/13 사용자), 직함·'님'·띄어쓰기를 떼고 같은 사람으로 본다
 */

// 이름 뒤에 붙는 직함·호칭(긴 것부터 떼야 '본부장'이 '부장'으로 잘리지 않는다)
const TITLES = [
  '본부장', '센터장', '파트장', '팀장', '실장', '부장', '차장', '과장', '대리', '주임', '사원', '매니저', '책임', '선임', '수석',
  '이사', '상무', '전무', '대표', '프로', '소장', '위원', '연구원', '선생님', '선생',
].sort((a, b) => b.length - a.length)

/** 같은 사람인지 볼 열쇠: 띄어쓰기·끝의 '님'·직함을 뗀다. 직함만 남는 이름('대리')은 그대로 둔다 */
export function personKey(name: string): string {
  let n = name.replace(/\s+/g, '').replace(/님$/, '')
  for (const t of TITLES) {
    if (n.endsWith(t) && n.length > t.length) {
      n = n.slice(0, -t.length)
      break
    }
  }
  return n
}

/**
 * 이름 목록에서 같은 사람을 하나로(먼저 나온 순서 유지). 같은 사람이 여러 표기면 가장 짧은 것(직함 없는 이름)을 보여 준다.
 * exclude: 빼야 할 이름(내 이름·'나')
 */
export function uniquePeople(names: string[], exclude: string[] = []): string[] {
  const skip = new Set(exclude.map(personKey))
  const order: string[] = []
  const best = new Map<string, string>()
  for (const raw of names) {
    const name = raw.trim()
    const key = personKey(name)
    if (!key || skip.has(key)) continue
    const had = best.get(key)
    if (had === undefined) order.push(key)
    if (had === undefined || name.length < had.length) best.set(key, name)
  }
  return order.map((k) => best.get(k)!)
}
