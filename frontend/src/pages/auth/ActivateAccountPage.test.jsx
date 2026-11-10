import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { activateAccount } from '../../api/authApi.js'
import ActivateAccountPage from './ActivateAccountPage.jsx'

vi.mock('../../api/authApi.js', () => ({
  activateAccount: vi.fn(),
}))

function renderPage() {
  return render(
    <BrowserRouter>
      <Routes>
        <Route path="/activate" element={<ActivateAccountPage />} />
      </Routes>
    </BrowserRouter>,
  )
}

describe('account activation page', () => {
  afterEach(() => {
    window.history.replaceState({}, '', '/')
    vi.clearAllMocks()
  })

  it('shows a password form and keeps the token in the URL without activating on mount', async () => {
    window.history.replaceState({}, '', '/activate?token=activation-link-test-value')
    renderPage()

    expect(await screen.findByLabelText('New password')).toBeTruthy()
    expect(screen.getByLabelText('Confirm new password')).toBeTruthy()
    expect(activateAccount).not.toHaveBeenCalled()
    expect(window.location.search).toContain('token=activation-link-test-value')
  })

  it('submits token, new password and confirmation and shows success', async () => {
    const user = userEvent.setup()
    window.history.replaceState({}, '', '/activate?token=activation-link-test-value')
    activateAccount.mockResolvedValue({ message: 'Account activated successfully.' })
    renderPage()

    await user.type(await screen.findByLabelText('New password'), 'Valid@2026!')
    await user.type(screen.getByLabelText('Confirm new password'), 'Valid@2026!')
    await user.click(screen.getByRole('button', { name: 'Activate account' }))

    expect(await screen.findByText('Account activated successfully.')).toBeTruthy()
    expect(activateAccount).toHaveBeenCalledWith({
      token: 'activation-link-test-value',
      newPassword: 'Valid@2026!',
      confirmPassword: 'Valid@2026!',
    })
    expect(screen.getByRole('link', { name: 'Continue to sign in' }).getAttribute('href')).toBe('/login')
  })

  it('reports a missing token and never calls the backend', async () => {
    window.history.replaceState({}, '', '/activate')
    renderPage()

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('invalid or incomplete')
    expect(activateAccount).not.toHaveBeenCalled()
  })

  it('rejects a mismatched confirmation without calling the backend', async () => {
    const user = userEvent.setup()
    window.history.replaceState({}, '', '/activate?token=activation-link-test-value')
    renderPage()

    await user.type(await screen.findByLabelText('New password'), 'Valid@2026!')
    await user.type(screen.getByLabelText('Confirm new password'), 'Different@2026!')
    await user.click(screen.getByRole('button', { name: 'Activate account' }))

    expect(await screen.findByText('New password and confirmation do not match.')).toBeTruthy()
    expect(activateAccount).not.toHaveBeenCalled()
  })

  it('rejects a password that breaks the policy without calling the backend', async () => {
    const user = userEvent.setup()
    window.history.replaceState({}, '', '/activate?token=activation-link-test-value')
    renderPage()

    await user.type(await screen.findByLabelText('New password'), 'weak')
    await user.type(screen.getByLabelText('Confirm new password'), 'weak')
    await user.click(screen.getByRole('button', { name: 'Activate account' }))

    expect(await screen.findByText(
      'Password must be at least 8 characters long and contain a letter, a number and a special character.',
    )).toBeTruthy()
    expect(activateAccount).not.toHaveBeenCalled()
  })

  it('shows the backend message for an expired or reused link', async () => {
    const user = userEvent.setup()
    window.history.replaceState({}, '', '/activate?token=used-token-value')
    activateAccount.mockRejectedValue({
      response: {
        status: 400,
        data: {
          message:
            'This activation link is invalid or has already been used. '
            + 'Ask your HR administrator to resend the activation email.',
        },
      },
    })
    renderPage()

    await user.type(await screen.findByLabelText('New password'), 'Valid@2026!')
    await user.type(screen.getByLabelText('Confirm new password'), 'Valid@2026!')
    await user.click(screen.getByRole('button', { name: 'Activate account' }))

    expect(await screen.findByRole('alert')).toHaveProperty(
      'textContent',
      'This activation link is invalid or has already been used. '
        + 'Ask your HR administrator to resend the activation email.',
    )
    expect(window.location.search).toContain('token=used-token-value')
  })

  it('still shows the form after a refresh while the token remains in the URL', async () => {
    window.history.replaceState({}, '', '/activate?token=refresh-token-value')
    const first = renderPage()
    expect(await screen.findByLabelText('New password')).toBeTruthy()
    first.unmount()

    renderPage()
    expect(await screen.findByLabelText('New password')).toBeTruthy()
    expect(activateAccount).not.toHaveBeenCalled()
  })
})
