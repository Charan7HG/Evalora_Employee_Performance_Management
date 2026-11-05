export const USER_ROLES = Object.freeze(['HR', 'MANAGER', 'EMPLOYEE'])

const DASHBOARD_PATHS = Object.freeze({
  HR: '/hr/dashboard',
  MANAGER: '/manager/dashboard',
  EMPLOYEE: '/employee/dashboard',
})

export function dashboardPathForRole(role) {
  return DASHBOARD_PATHS[role] || '/login'
}

export function hasAnyRole(user, allowedRoles) {
  if (!user || !USER_ROLES.includes(user.role)) {
    return false
  }

  return allowedRoles.includes(user.role)
}
