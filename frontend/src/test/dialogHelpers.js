import { screen, within } from '@testing-library/react'

/**
 * The application confirms important actions with its own in-app dialog
 * instead of window.confirm. These helpers drive that dialog the way a user
 * would, so tests never depend on native browser dialogs.
 */
export async function confirmDialog(user, label) {
  const dialog = await screen.findByRole('dialog')
  await user.click(within(dialog).getByRole('button', { name: label }))
}

export async function cancelDialog(user) {
  const dialog = await screen.findByRole('dialog')
  await user.click(within(dialog).getByRole('button', { name: 'Cancel' }))
}

export function dialogText() {
  return screen.getByRole('dialog').textContent
}
