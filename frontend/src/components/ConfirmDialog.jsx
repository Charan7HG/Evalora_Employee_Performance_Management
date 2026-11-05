import { useEffect, useId, useRef } from 'react'

/**
 * In-app replacement for window.confirm / window.alert.
 *
 * It is a plain modal dialog owned by the application: it keeps the focus in
 * the page, is announced to assistive technology as a dialog, can be dismissed
 * with Escape and never blocks the browser. The confirm button is disabled
 * while the caller's request is in flight, which prevents duplicate actions.
 */
export default function ConfirmDialog({
  title,
  message,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  busy = false,
  busyLabel = 'Working…',
  onConfirm,
  onCancel,
}) {
  const titleId = useId()
  const descriptionId = useId()
  const cancelRef = useRef(null)

  useEffect(() => {
    cancelRef.current?.focus()
    const handleKeyDown = (event) => {
      if (event.key === 'Escape' && !busy) {
        onCancel()
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [busy, onCancel])

  const paragraphs = Array.isArray(message) ? message : [message]

  return (
    <div className="modal-backdrop" role="presentation">
      <div
        className="modal-card"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={descriptionId}
      >
        <h2 id={titleId}>{title}</h2>
        <div id={descriptionId} className="modal-body">
          {paragraphs.filter(Boolean).map((paragraph, index) => (
            <p key={`${index}-${paragraph}`}>{paragraph}</p>
          ))}
        </div>
        <div className="modal-actions">
          <button
            ref={cancelRef}
            className="secondary-button"
            type="button"
            onClick={onCancel}
            disabled={busy}
          >
            {cancelLabel}
          </button>
          <button
            className="primary-button"
            type="button"
            onClick={onConfirm}
            disabled={busy}
          >
            {busy ? busyLabel : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  )
}
