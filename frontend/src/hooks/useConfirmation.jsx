import { useCallback, useState } from 'react'
import ConfirmDialog from '../components/ConfirmDialog.jsx'

/**
 * Small helper for actions that need an explicit in-app confirmation.
 *
 * Usage:
 *   const { ask, dialog } = useConfirmation()
 *   ask({ title, message, confirmLabel, onConfirm: doTheWork })
 *   ...
 *   return <>{dialog} ...</>
 *
 * The dialog stays open, with its confirm button disabled, until the async
 * action resolves, so a double click cannot start the action twice.
 */
export function useConfirmation() {
  const [request, setRequest] = useState(null)
  const [busy, setBusy] = useState(false)

  const ask = useCallback((options) => {
    setBusy(false)
    setRequest(options)
  }, [])

  const close = useCallback(() => {
    setRequest(null)
    setBusy(false)
  }, [])

  const handleConfirm = useCallback(async () => {
    if (!request || busy) {
      return
    }
    setBusy(true)
    try {
      await request.onConfirm()
    } finally {
      setBusy(false)
      setRequest(null)
    }
  }, [busy, request])

  const dialog = request ? (
    <ConfirmDialog
      title={request.title}
      message={request.message}
      confirmLabel={request.confirmLabel}
      cancelLabel={request.cancelLabel}
      busyLabel={request.busyLabel}
      busy={busy}
      onConfirm={handleConfirm}
      onCancel={close}
    />
  ) : null

  return { ask, dialog, close }
}
