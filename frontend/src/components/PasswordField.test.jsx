import { useState } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import PasswordField from './PasswordField.jsx'

function Harness() {
  const [password, setPassword] = useState('Secret1!')
  const [confirmPassword, setConfirmPassword] = useState('Secret1!')
  return (
    <>
      <PasswordField
        id="password"
        name="password"
        label="Password"
        autoComplete="current-password"
        value={password}
        onChange={(event) => setPassword(event.target.value)}
      />
      <PasswordField
        id="confirmPassword"
        name="confirmPassword"
        label="Confirm password"
        autoComplete="new-password"
        value={confirmPassword}
        onChange={(event) => setConfirmPassword(event.target.value)}
      />
    </>
  )
}

describe('PasswordField visibility control', () => {
  it('toggles one field, keeps its value and leaves the other field hidden', async () => {
    const user = userEvent.setup()
    render(<Harness />)

    expect(screen.getByLabelText('Password').type).toBe('password')
    expect(screen.getByLabelText('Confirm password').type).toBe('password')
    expect(screen.getAllByRole('button', { name: 'Show password' })).toHaveLength(2)

    await user.click(screen.getAllByRole('button', { name: 'Show password' })[0])

    expect(screen.getByLabelText('Password').type).toBe('text')
    expect(screen.getByLabelText('Password').value).toBe('Secret1!')
    expect(screen.getByLabelText('Confirm password').type).toBe('password')
    expect(screen.getAllByRole('button', { name: 'Hide password' })).toHaveLength(1)

    await user.click(screen.getByRole('button', { name: 'Hide password' }))

    expect(screen.getByLabelText('Password').type).toBe('password')
    expect(screen.getByLabelText('Password').value).toBe('Secret1!')
  })

  it('keeps the typed value when visibility is toggled after editing', async () => {
    const user = userEvent.setup()
    render(<Harness />)

    await user.clear(screen.getByLabelText('Password'))
    await user.type(screen.getByLabelText('Password'), 'N3w-Pass!')
    await user.click(screen.getAllByRole('button', { name: 'Show password' })[0])

    expect(screen.getByLabelText('Password').value).toBe('N3w-Pass!')
    expect(screen.getByLabelText('Password').type).toBe('text')
  })
})
