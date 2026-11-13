import { useEffect, useState } from 'react'
import { recordPeople } from '../data/server'
import { Dialog, PRIMARY_BUTTON, SECONDARY_BUTTON } from './Dialog'
import { RecipientsField } from './RecipientsField'

/** 후보 받는 사람 고르기(DEC-77): 기록 참석자·이 기록에서 쓴 이름 중에서 넣고 빼며, 없으면 적는다. 고른 값은 승인 때 덮는다 */
export function RecipientsDialog({
  recordId,
  value,
  followNames,
  onDismiss,
  onApply,
}: {
  recordId: string | null
  value: string[]
  followNames: string[]
  onDismiss: () => void
  onApply: (names: string[]) => void
}) {
  const [names, setNames] = useState(value)
  const [people, setPeople] = useState<string[]>([])
  useEffect(() => {
    if (recordId) void recordPeople(recordId).then(setPeople)
  }, [recordId])
  return (
    <Dialog
      title="받는 사람"
      onDismiss={onDismiss}
      actions={
        <>
          <button type="button" onClick={onDismiss} className={SECONDARY_BUTTON}>
            취소
          </button>
          <button type="button" onClick={() => onApply(names)} className={PRIMARY_BUTTON}>
            적용
          </button>
        </>
      }
    >
      <div className="flex flex-col gap-2 pb-1">
        <RecipientsField value={names} choices={[...new Set([...followNames, ...people])]} onChange={setNames} />
      </div>
    </Dialog>
  )
}
