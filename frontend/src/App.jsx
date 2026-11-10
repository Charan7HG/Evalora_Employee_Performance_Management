import { Navigate, Route, Routes, useLocation } from 'react-router-dom'
import * as employeeApi from './api/employeeApi.js'
import * as managerApi from './api/managerApi.js'
import CycleDetailPage from './pages/cycle/CycleDetailPage.jsx'
import CycleFormPage from './pages/cycle/CycleFormPage.jsx'
import CycleListPage from './pages/cycle/CycleListPage.jsx'
import DashboardLayout from './components/DashboardLayout.jsx'
import { useAuth } from './context/useAuth.js'
import AccessDeniedPage from './pages/AccessDeniedPage.jsx'
import ChangePasswordPage from './pages/auth/ChangePasswordPage.jsx'
import ActivateAccountPage from './pages/auth/ActivateAccountPage.jsx'
import ForgotPasswordPage from './pages/auth/ForgotPasswordPage.jsx'
import ResetPasswordPage from './pages/auth/ResetPasswordPage.jsx'
import HrResultBrowsePage from './pages/results/HrResultBrowsePage.jsx'
import EmployeeDashboardPage from './pages/dashboard/EmployeeDashboardPage.jsx'
import HrDashboardPage from './pages/dashboard/HrDashboardPage.jsx'
import LoginPage from './pages/auth/LoginPage.jsx'
import ManagerDashboardPage from './pages/dashboard/ManagerDashboardPage.jsx'
import NotFoundPage from './pages/NotFoundPage.jsx'
import KpiDetailPage from './pages/kpi/KpiDetailPage.jsx'
import KpiFormPage from './pages/kpi/KpiFormPage.jsx'
import KpiListPage from './pages/kpi/KpiListPage.jsx'
import StaffDetailPage from './pages/staff/StaffDetailPage.jsx'
import StaffFormPage from './pages/staff/StaffFormPage.jsx'
import StaffListPage from './pages/staff/StaffListPage.jsx'
import EmployeeSelfAssessmentListPage from './pages/self-assessment/EmployeeSelfAssessmentListPage.jsx'
import EmployeeSelfAssessmentPage from './pages/self-assessment/EmployeeSelfAssessmentPage.jsx'
import ManagerAssessmentListPage from './pages/manager-assessment/ManagerAssessmentListPage.jsx'
import ManagerAssessmentPage from './pages/manager-assessment/ManagerAssessmentPage.jsx'
import HrAssessmentListPage from './pages/hr-assessment/HrAssessmentListPage.jsx'
import HrAssessmentPage from './pages/hr-assessment/HrAssessmentPage.jsx'
import HrResultListPage from './pages/results/HrResultListPage.jsx'
import HrResultDetailPage from './pages/results/HrResultDetailPage.jsx'
import EmployeeResultListPage from './pages/results/EmployeeResultListPage.jsx'
import EmployeeResultDetailPage from './pages/results/EmployeeResultDetailPage.jsx'
import ManagerResultListPage from './pages/results/ManagerResultListPage.jsx'
import ManagerResultDetailPage from './pages/results/ManagerResultDetailPage.jsx'
import AssessmentStatusListPage from './pages/assessment-status/AssessmentStatusListPage.jsx'
import ManagerDirectReportsPage from './pages/assessment-status/ManagerDirectReportsPage.jsx'
import { dashboardPathForRole, hasAnyRole } from './utils/roles.js'
import './App.css'

function LoadingMessage() {
  return (
    <main className="loading-screen" role="status" aria-live="polite">
      Checking your sign-in…
    </main>
  )
}

export function RequireAuth({ children, allowedRoles }) {
  const { isLoading, user } = useAuth()
  const location = useLocation()

  if (isLoading) {
    return <LoadingMessage />
  }

  if (!user) {
    return <Navigate to="/login" replace state={{ from: location }} />
  }

  if (allowedRoles && !hasAnyRole(user, allowedRoles)) {
    return <AccessDeniedPage />
  }

  return children
}

function GuestRoute({ children }) {
  const { isLoading, user } = useAuth()

  if (isLoading) {
    return <LoadingMessage />
  }

  return user ? <Navigate to={dashboardPathForRole(user.role)} replace /> : children
}

function RoleDashboardRedirect() {
  const { user } = useAuth()
  return <Navigate to={dashboardPathForRole(user.role)} replace />
}

function RoleDashboardRoute({ role, children }) {
  return (
    <RequireAuth allowedRoles={[role]}>
      <DashboardLayout>{children}</DashboardLayout>
    </RequireAuth>
  )
}

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/activate" element={<ActivateAccountPage />} />
      <Route
        path="/login"
        element={
          <GuestRoute>
            <LoginPage />
          </GuestRoute>
        }
      />
      <Route
        path="/forgot-password"
        element={
          <GuestRoute>
            <ForgotPasswordPage />
          </GuestRoute>
        }
      />
      <Route
        path="/reset-password"
        element={
          <GuestRoute>
            <ResetPasswordPage />
          </GuestRoute>
        }
      />
      <Route
        path="/change-password"
        element={
          <RequireAuth>
            <DashboardLayout>
              <ChangePasswordPage />
            </DashboardLayout>
          </RequireAuth>
        }
      />
      <Route
        path="/hr/dashboard"
        element={<RoleDashboardRoute role="HR"><HrDashboardPage /></RoleDashboardRoute>}
      />
      <Route
        path="/hr/assessments"
        element={<RoleDashboardRoute role="HR"><HrAssessmentListPage /></RoleDashboardRoute>}
      />
      <Route
        path="/hr/assessments/:assignmentId"
        element={<RoleDashboardRoute role="HR"><HrAssessmentPage /></RoleDashboardRoute>}
      />
      <Route path="/hr/assessment-status/:status" element={<RoleDashboardRoute role="HR"><AssessmentStatusListPage /></RoleDashboardRoute>} />
      <Route
        path="/hr/results"
        element={<RoleDashboardRoute role="HR"><HrResultListPage /></RoleDashboardRoute>}
      />
      <Route
        path="/hr/results/browse"
        element={<RoleDashboardRoute role="HR"><HrResultBrowsePage /></RoleDashboardRoute>}
      />
      <Route
        path="/hr/results/:resultId"
        element={<RoleDashboardRoute role="HR"><HrResultDetailPage /></RoleDashboardRoute>}
      />
      <Route
        path="/manager/dashboard"
        element={<RoleDashboardRoute role="MANAGER"><ManagerDashboardPage /></RoleDashboardRoute>}
      />
      <Route
        path="/manager/assessments"
        element={<RoleDashboardRoute role="MANAGER"><ManagerAssessmentListPage /></RoleDashboardRoute>}
      />
      <Route
        path="/manager/assessments/:assignmentId"
        element={<RoleDashboardRoute role="MANAGER"><ManagerAssessmentPage /></RoleDashboardRoute>}
      />
      <Route path="/manager/assessment-status/:status" element={<RoleDashboardRoute role="MANAGER"><AssessmentStatusListPage /></RoleDashboardRoute>} />
      <Route path="/manager/direct-reports" element={<RoleDashboardRoute role="MANAGER"><ManagerDirectReportsPage /></RoleDashboardRoute>} />
      <Route
        path="/manager/results"
        element={<RoleDashboardRoute role="MANAGER"><ManagerResultListPage /></RoleDashboardRoute>}
      />
      <Route
        path="/manager/results/:resultId"
        element={<RoleDashboardRoute role="MANAGER"><ManagerResultDetailPage /></RoleDashboardRoute>}
      />
      <Route
        path="/employee/dashboard"
        element={<RoleDashboardRoute role="EMPLOYEE"><EmployeeDashboardPage /></RoleDashboardRoute>}
      />
      <Route
        path="/employee/self-assessments"
        element={<RoleDashboardRoute role="EMPLOYEE"><EmployeeSelfAssessmentListPage /></RoleDashboardRoute>}
      />
      <Route
        path="/employee/self-assessments/:assignmentId"
        element={<RoleDashboardRoute role="EMPLOYEE"><EmployeeSelfAssessmentPage /></RoleDashboardRoute>}
      />
      <Route
        path="/employee/results"
        element={<RoleDashboardRoute role="EMPLOYEE"><EmployeeResultListPage /></RoleDashboardRoute>}
      />
      <Route
        path="/employee/results/:resultId"
        element={<RoleDashboardRoute role="EMPLOYEE"><EmployeeResultDetailPage /></RoleDashboardRoute>}
      />
      <Route
        path="/hr/employees"
        element={(
          <RoleDashboardRoute role="HR">
            <StaffListPage
              resource="employees"
              title="Employees"
              listStaff={employeeApi.listEmployees}
              deactivateStaff={employeeApi.deactivateEmployee}
              reactivateStaff={employeeApi.reactivateEmployee}
            />
          </RoleDashboardRoute>
        )}
      />
      <Route
        path="/hr/employees/new"
        element={(
          <RoleDashboardRoute role="HR">
            <StaffFormPage
              resource="employees"
              title="Employees"
              getStaff={employeeApi.getEmployee}
              createStaff={employeeApi.createEmployee}
              updateStaff={employeeApi.updateEmployee}
              listManagers={managerApi.listManagers}
            />
          </RoleDashboardRoute>
        )}
      />
      <Route
        path="/hr/employees/:id"
        element={(
          <RoleDashboardRoute role="HR">
            <StaffDetailPage
              resource="employees"
              title="Employees"
              getStaff={employeeApi.getEmployee}
              deactivateStaff={employeeApi.deactivateEmployee}
              reactivateStaff={employeeApi.reactivateEmployee}
              assignStaffManager={employeeApi.assignEmployeeManager}
              listManagers={managerApi.listManagers}
            />
          </RoleDashboardRoute>
        )}
      />
      <Route
        path="/hr/employees/:id/edit"
        element={(
          <RoleDashboardRoute role="HR">
            <StaffFormPage
              resource="employees"
              title="Employees"
              getStaff={employeeApi.getEmployee}
              createStaff={employeeApi.createEmployee}
              updateStaff={employeeApi.updateEmployee}
              listManagers={managerApi.listManagers}
            />
          </RoleDashboardRoute>
        )}
      />
      <Route
        path="/hr/managers"
        element={(
          <RoleDashboardRoute role="HR">
            <StaffListPage
              resource="managers"
              title="Managers"
              listStaff={managerApi.listManagers}
              deactivateStaff={managerApi.deactivateManager}
              reactivateStaff={managerApi.reactivateManager}
            />
          </RoleDashboardRoute>
        )}
      />
      <Route
        path="/hr/managers/new"
        element={(
          <RoleDashboardRoute role="HR">
            <StaffFormPage
              resource="managers"
              title="Managers"
              getStaff={managerApi.getManager}
              createStaff={managerApi.createManager}
              updateStaff={managerApi.updateManager}
              listManagers={managerApi.listManagers}
            />
          </RoleDashboardRoute>
        )}
      />
      <Route
        path="/hr/managers/:id"
        element={(
          <RoleDashboardRoute role="HR">
            <StaffDetailPage
              resource="managers"
              title="Managers"
              getStaff={managerApi.getManager}
              deactivateStaff={managerApi.deactivateManager}
              reactivateStaff={managerApi.reactivateManager}
            />
          </RoleDashboardRoute>
        )}
      />
      <Route
        path="/hr/managers/:id/edit"
        element={(
          <RoleDashboardRoute role="HR">
            <StaffFormPage
              resource="managers"
              title="Managers"
              getStaff={managerApi.getManager}
              createStaff={managerApi.createManager}
              updateStaff={managerApi.updateManager}
              listManagers={managerApi.listManagers}
            />
          </RoleDashboardRoute>
        )}
      />
      <Route
        path="/hr/kpis"
        element={(
          <RoleDashboardRoute role="HR">
            <KpiListPage />
          </RoleDashboardRoute>
        )}
      />
      <Route
        path="/hr/kpis/new"
        element={(
          <RoleDashboardRoute role="HR">
            <KpiFormPage />
          </RoleDashboardRoute>
        )}
      />
      <Route
        path="/hr/kpis/:id"
        element={(
          <RoleDashboardRoute role="HR">
            <KpiDetailPage />
          </RoleDashboardRoute>
        )}
      />
      <Route
        path="/hr/kpis/:id/edit"
        element={(
          <RoleDashboardRoute role="HR">
            <KpiFormPage />
          </RoleDashboardRoute>
        )}
      />
      <Route
        path="/hr/cycles"
        element={(
          <RoleDashboardRoute role="HR">
            <CycleListPage />
          </RoleDashboardRoute>
        )}
      />
      <Route
        path="/hr/cycles/new"
        element={(
          <RoleDashboardRoute role="HR">
            <CycleFormPage />
          </RoleDashboardRoute>
        )}
      />
      <Route
        path="/hr/cycles/:id"
        element={(
          <RoleDashboardRoute role="HR">
            <CycleDetailPage />
          </RoleDashboardRoute>
        )}
      />
      <Route
        path="/hr/cycles/:id/edit"
        element={(
          <RoleDashboardRoute role="HR">
            <CycleFormPage />
          </RoleDashboardRoute>
        )}
      />
      <Route path="/app" element={<Navigate to="/" replace />} />
      <Route path="/" element={<RequireAuth><RoleDashboardRedirect /></RequireAuth>} />
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  )
}

export default function App() {
  return <AppRoutes />
}
