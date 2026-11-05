import { Navigate } from 'react-router-dom'

/**
 * The old token-based reset screen has been replaced by the direct reset form
 * on /forgot-password. Keep the URL working for old bookmarks and links by
 * redirecting instead of showing a dead form.
 */
export default function ResetPasswordPage() {
  return <Navigate to="/forgot-password" replace />
}
