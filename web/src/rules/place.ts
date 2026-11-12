/** 할 일을 넣을 곳: 프로젝트 또는 반복실행(앱 data.Place와 같음) */
export type PlaceType = 'PROJECT' | 'AREA'

export interface Place {
  type: PlaceType
  id: string
  name: string
}

export function samePlace(a: Place | null | undefined, b: Place | null | undefined): boolean {
  return a?.type === b?.type && a?.id === b?.id
}
