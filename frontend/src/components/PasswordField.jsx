import { useState } from 'react'

export default function PasswordField({
  id,
  name,
  label,
  value,
  onChange,
  autoComplete,
  required = false,
  disabled = false,
  hint,
  error,
  className = '',
  inputClassName = '',
}) {
  const [visible, setVisible] = useState(false)
  const toggle = () => setVisible((v) => !v)

  const hintId = hint ? `${id}-hint` : undefined
  const errorId = error ? `${id}-error` : undefined
  const describedBy = [hintId, errorId].filter(Boolean).join(' ') || undefined

  return (
    <div className={`password-field ${className}`.trim()}>
      {label && <label className="password-label" htmlFor={id}>{label}</label>}
      <div className="password-input-wrapper">
        <input
          id={id}
          name={name}
          type={visible ? 'text' : 'password'}
          autoComplete={autoComplete}
          value={value}
          onChange={onChange}
          required={required}
          disabled={disabled}
          className={`password-input ${inputClassName}`.trim()}
          aria-describedby={describedBy}
        />
        <button
          type="button"
          className="password-toggle"
          onClick={toggle}
          aria-label={visible ? 'Hide password' : 'Show password'}
          title={visible ? 'Hide password' : 'Show password'}
        >
          {visible ? '🙈' : '👁'}
        </button>
      </div>
      {hint && <span id={hintId} className="field-hint">{hint}</span>}
      {error && <span id={errorId} className="field-error" role="alert">{error}</span>}
    </div>
  )
}
