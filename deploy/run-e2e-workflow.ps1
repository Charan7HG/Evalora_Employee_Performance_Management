# =============================================================================
# Evalora - isolated end-to-end workflow (Phase 4)
#
# Reads credentials from deploy/.env.e2e (git-ignored) and drives the running
# Compose stack through the public frontend proxy. Tokens, OTPs and passwords
# are never written to the console.
# =============================================================================

$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repo 'deploy\.env.e2e'
$base = 'http://localhost:15173'
$project = $env:EVALORA_E2E_PROJECT

if (-not $project) {
    throw 'Set EVALORA_E2E_PROJECT to the Compose project name before running.'
}

$envVars = @{}
Get-Content $envFile | ForEach-Object {
    if ($_ -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
        $envVars[$matches[1]] = $matches[2]
    }
}

$hrEmail = $envVars['EVALORA_E2E_HR_EMAIL']
$hrPassword = $envVars['EVALORA_E2E_HR_PASSWORD']
$stamp = Get-Date -Format 'yyyyMMddHHmmss'

$script:pass = 0
$script:fail = 0

function Check([string]$name, [bool]$ok, [string]$detail = '') {
    if ($ok) {
        $script:pass++
        Write-Output ("PASS  {0}{1}" -f $name, $(if ($detail) { "  ($detail)" } else { '' }))
    } else {
        $script:fail++
        Write-Output ("FAIL  {0}{1}" -f $name, $(if ($detail) { "  ($detail)" } else { '' }))
    }
}

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        [string]$Token = $null,
        $Body = $null
    )
    $headers = @{}
    if ($Token) { $headers['Authorization'] = "Bearer $Token" }

    $params = @{
        Method         = $Method
        Uri            = "$base$Path"
        Headers        = $headers
        UseBasicParsing = $true
        TimeoutSec     = 30
    }
    if ($null -ne $Body) {
        $params['ContentType'] = 'application/json'
        $params['Body'] = (ConvertTo-Json -InputObject $Body -Depth 10 -Compress)
    }

    try {
        $response = Invoke-WebRequest @params
        $content = $response.Content
        $parsed = $null
        if ($content) {
            try { $parsed = $content | ConvertFrom-Json } catch { $parsed = $null }
        }
        return [pscustomobject]@{ Status = [int]$response.StatusCode; Body = $parsed; Raw = $content }
    } catch {
        $status = 0
        if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        $raw = ''
        try {
            $stream = $_.Exception.Response.GetResponseStream()
            if ($stream) {
                $reader = New-Object System.IO.StreamReader($stream)
                $raw = $reader.ReadToEnd()
            }
        } catch { }
        $parsed = $null
        if ($raw) {
            try { $parsed = $raw | ConvertFrom-Json } catch { $parsed = $null }
        }
        return [pscustomobject]@{ Status = $status; Body = $parsed; Raw = $raw }
    }
}

function Login([string]$email, [string]$password) {
    $r = Invoke-Api -Method 'POST' -Path '/api/auth/login' -Body @{
        email    = $email
        password = $password
    }
    if ($r.Status -ne 200) { return $null }
    return $r.Body.token
}

# ---------------------------------------------------------------------------
# 0. Frontend reachability + SPA route
# ---------------------------------------------------------------------------
try {
    $root = Invoke-WebRequest -Uri "$base/" -UseBasicParsing -TimeoutSec 30
    Check 'Frontend SPA reachable through Nginx' ($root.StatusCode -eq 200)
} catch {
    Check 'Frontend SPA reachable through Nginx' $false $_.Exception.Message
}

try {
    $activatePage = Invoke-WebRequest -Uri "$base/activate" -UseBasicParsing -TimeoutSec 30
    Check 'Frontend /activate route serves the SPA' ($activatePage.StatusCode -eq 200)
} catch {
    Check 'Frontend /activate route serves the SPA' $false $_.Exception.Message
}

# ---------------------------------------------------------------------------
# 1. HR login through the frontend proxy (also proves /api proxy works)
# ---------------------------------------------------------------------------
$hrToken = Login $hrEmail $hrPassword
Check 'HR can log in through the frontend API proxy' ($null -ne $hrToken)

if ($null -eq $hrToken) {
    Write-Output ''
    Write-Output ("RESULT: {0} passed, {1} failed" -f $script:pass, $script:fail)
    exit 1
}

# ---------------------------------------------------------------------------
# 2. HR creates a manager
# ---------------------------------------------------------------------------
$managerEmail = "e2e.manager.$stamp@evalora.local"
$managerPassword = ([guid]::NewGuid().ToString('N').Substring(0, 12)) + 'M1!'
$managerActivationPassword = ([guid]::NewGuid().ToString('N').Substring(0, 12)) + 'MA1!'
$managerCreate = Invoke-Api -Method 'POST' -Path '/api/hr/managers' -Token $hrToken -Body @{
    firstName       = 'Mona'
    lastName        = 'Manager'
    email           = $managerEmail
    employeeCode    = "MGR-$stamp"
    department      = 'Operations'
    designation     = 'Manager'
    joiningDate     = '2026-01-01'
    initialPassword = $managerPassword
}
Check 'HR creates a manager' ($managerCreate.Status -eq 201) ("HTTP " + $managerCreate.Status)
$managerId = $managerCreate.Body.id

# ---------------------------------------------------------------------------
# 3. Capture helpers (activation/OTP emails stay in the container tmpfs)
# ---------------------------------------------------------------------------
$backendId = ((& docker compose -p $project --env-file $envFile -f (Join-Path $repo 'docker-compose.yml') -f (Join-Path $repo 'deploy\docker-compose.e2e.yml') ps -q backend) -join '').Trim()
if (-not $backendId) { throw 'Backend container id was not found.' }

function Get-Captures {
    $raw = & docker exec $backendId sh -c 'for f in /tmp/evalora-capture/messages/*.json; do cat "$f"; echo; done' 2>$null
    $items = @()
    foreach ($line in @($raw)) {
        $text = "$line".Trim()
        if ($text) {
            try { $items += ($text | ConvertFrom-Json) } catch { }
        }
    }
    return $items
}

function Find-ActivationToken([string]$recipient) {
    $capture = @(Get-Captures) | Where-Object { $_.type -eq 'activation' -and $_.recipient -eq $recipient } | Select-Object -Last 1
    if (-not $capture) { return $null }
    $parts = $capture.content -split 'token=', 2
    if ($parts.Count -ne 2) { return $null }
    return $parts[1]
}

# ---------------------------------------------------------------------------
# 4. Activate the manager first: an employee may only report to an ACTIVE
#    manager whose account is already enabled.
# ---------------------------------------------------------------------------
$managerActivation = Find-ActivationToken $managerEmail
Check 'Manager activation email captured (safe E2E capture)' ($null -ne $managerActivation)

$managerActivate = Invoke-Api -Method 'POST' -Path '/api/auth/activate-account' -Body @{
    token           = $managerActivation
    newPassword     = $managerActivationPassword
    confirmPassword = $managerActivationPassword
}
Check 'Manager account activates' ($managerActivate.Status -eq 200) ("HTTP " + $managerActivate.Status)

# ---------------------------------------------------------------------------
# 5. HR creates an employee assigned to that (now active) manager
# ---------------------------------------------------------------------------
$employeeEmail = "e2e.employee.$stamp@evalora.local"
$employeePassword = ([guid]::NewGuid().ToString('N').Substring(0, 12)) + 'E1!'
$employeeActivationPassword = ([guid]::NewGuid().ToString('N').Substring(0, 12)) + 'EA1!'
$employeeCreate = Invoke-Api -Method 'POST' -Path '/api/hr/employees' -Token $hrToken -Body @{
    firstName       = 'Asha'
    lastName        = 'Employee'
    email           = $employeeEmail
    employeeCode    = "EMP-$stamp"
    department      = 'Engineering'
    designation     = 'Engineer'
    joiningDate     = '2026-02-01'
    initialPassword = $employeePassword
    managerId       = $managerId
}
Check 'HR creates an employee' ($employeeCreate.Status -eq 201) ("HTTP " + $employeeCreate.Status)
$employeeId = $employeeCreate.Body.id

$employeeRead = Invoke-Api -Method 'GET' -Path "/api/hr/employees/$employeeId" -Token $hrToken
Check 'Employee is assigned to the manager' (
    $employeeRead.Status -eq 200 -and [int64]$employeeRead.Body.managerId -eq [int64]$managerId
)

# ---------------------------------------------------------------------------
# 6. Activate the employee account
# ---------------------------------------------------------------------------
$employeeActivation = Find-ActivationToken $employeeEmail
Check 'Employee activation email captured (safe E2E capture)' ($null -ne $employeeActivation)

$employeeActivate = Invoke-Api -Method 'POST' -Path '/api/auth/activate-account' -Body @{
    token           = $employeeActivation
    newPassword     = $employeeActivationPassword
    confirmPassword = $employeeActivationPassword
}
Check 'Employee account activates' ($employeeActivate.Status -eq 200) ("HTTP " + $employeeActivate.Status)

# ---------------------------------------------------------------------------
# 5. Activated accounts can log in
# ---------------------------------------------------------------------------
$managerToken = Login $managerEmail $managerActivationPassword
Check 'Manager can log in after activation' ($null -ne $managerToken)

$employeeToken = Login $employeeEmail $employeeActivationPassword
Check 'Employee can log in after activation' ($null -ne $employeeToken)

# ---------------------------------------------------------------------------
# 6. Manager sees the direct report
# ---------------------------------------------------------------------------
$directReports = Invoke-Api -Method 'GET' -Path '/api/manager/direct-reports' -Token $managerToken
$directReportCount = @($directReports.Body | Where-Object { [int64]$_.employeeId -eq [int64]$employeeId }).Count
Check 'Manager sees the employee as a direct report' (
    $directReports.Status -eq 200 -and $directReportCount -eq 1
)

# ---------------------------------------------------------------------------
# 7. HR creates KPIs and a performance cycle
# ---------------------------------------------------------------------------
$managerKpi = Invoke-Api -Method 'POST' -Path '/api/hr/kpis' -Token $hrToken -Body @{
    name              = "Delivery $stamp"
    description       = 'Deliver committed work on time.'
    category          = 'Delivery'
    defaultWeightage  = 70
    kpiType           = 'MANAGER'
}
Check 'HR creates a manager-assessed KPI' ($managerKpi.Status -eq 201) ("HTTP " + $managerKpi.Status)

$hrKpi = Invoke-Api -Method 'POST' -Path '/api/hr/kpis' -Token $hrToken -Body @{
    name              = "Values $stamp"
    description       = 'Demonstrate company values.'
    category          = 'Values'
    defaultWeightage  = 30
    kpiType           = 'HR'
}
Check 'HR creates an HR-assessed KPI' ($hrKpi.Status -eq 201) ("HTTP " + $hrKpi.Status)

$today = Get-Date
$cycleCreate = Invoke-Api -Method 'POST' -Path '/api/hr/cycles' -Token $hrToken -Body @{
    name                   = "E2E Cycle $stamp"
    description            = 'Isolated end-to-end workflow cycle.'
    startDate              = $today.AddDays(-1).ToString('yyyy-MM-dd')
    endDate                = $today.AddDays(30).ToString('yyyy-MM-dd')
    selfAssessmentDeadline = $today.AddDays(7).ToString('yyyy-MM-dd')
    managerReviewDeadline  = $today.AddDays(14).ToString('yyyy-MM-dd')
    hrReviewDeadline       = $today.AddDays(21).ToString('yyyy-MM-dd')
}
Check 'HR creates a performance cycle' ($cycleCreate.Status -eq 201) ("HTTP " + $cycleCreate.Status)
$cycleId = $cycleCreate.Body.id

# ---------------------------------------------------------------------------
# 8. HR assigns KPIs and the employee, then publishes the cycle
# ---------------------------------------------------------------------------
$kpiConfig = Invoke-Api -Method 'POST' -Path "/api/hr/cycles/$cycleId/kpis" -Token $hrToken -Body @{
    kpis = @(
        @{ kpiId = $managerKpi.Body.id; weightage = 70 },
        @{ kpiId = $hrKpi.Body.id; weightage = 30 }
    )
}
Check 'HR assigns KPIs to the cycle' ($kpiConfig.Status -eq 200) ("HTTP " + $kpiConfig.Status)

$employeeConfig = Invoke-Api -Method 'PUT' -Path "/api/hr/cycles/$cycleId/employees" -Token $hrToken -Body @{
    employees = @(
        @{ employeeId = $employeeId; managerId = $managerId }
    )
}
Check 'HR assigns the employee to the cycle' ($employeeConfig.Status -eq 200) ("HTTP " + $employeeConfig.Status)

$cyclePublish = Invoke-Api -Method 'POST' -Path "/api/hr/cycles/$cycleId/publish" -Token $hrToken
Check 'HR publishes the cycle' (
    $cyclePublish.Status -eq 200 -and $cyclePublish.Body.status -eq 'PUBLISHED'
)

# ---------------------------------------------------------------------------
# 9. Employee submits the self-assessment
# ---------------------------------------------------------------------------
$employeeCycles = Invoke-Api -Method 'GET' -Path '/api/employee/self-assessments/cycles' -Token $employeeToken
$assignment = @($employeeCycles.Body) | Where-Object { [int64]$_.cycleId -eq [int64]$cycleId } | Select-Object -First 1
Check 'Employee sees the published assignment' ($null -ne $assignment)
$assignmentId = $assignment.assignmentId

$selfDetail = Invoke-Api -Method 'GET' -Path "/api/employee/self-assessments/$assignmentId" -Token $employeeToken
$selfDraft = @{
    kpis = @($selfDetail.Body.kpis | ForEach-Object {
        @{ employeeKpiId = $_.employeeKpiId; rating = 4; comment = 'Employee self rating' }
    })
}
$selfSave = Invoke-Api -Method 'PUT' -Path "/api/employee/self-assessments/$assignmentId/draft" -Token $employeeToken -Body $selfDraft
Check 'Employee saves the self-assessment draft' ($selfSave.Status -eq 200) ("HTTP " + $selfSave.Status)

$selfSubmit = Invoke-Api -Method 'POST' -Path "/api/employee/self-assessments/$assignmentId/submit" -Token $employeeToken
Check 'Employee submits the self-assessment' (
    $selfSubmit.Status -eq 200 -and $selfSubmit.Body.submitted -eq $true
)

# ---------------------------------------------------------------------------
# 10. Manager submits the assessment
# ---------------------------------------------------------------------------
$managerDetail = Invoke-Api -Method 'GET' -Path "/api/manager/assessments/$assignmentId" -Token $managerToken
$managerDraft = @{
    kpis = @($managerDetail.Body.kpis | ForEach-Object {
        @{ employeeKpiId = $_.employeeKpiId; rating = 4; comment = 'Manager rating' }
    })
}
$managerSave = Invoke-Api -Method 'PUT' -Path "/api/manager/assessments/$assignmentId/draft" -Token $managerToken -Body $managerDraft
Check 'Manager saves the assessment draft' ($managerSave.Status -eq 200) ("HTTP " + $managerSave.Status)

$managerSubmit = Invoke-Api -Method 'POST' -Path "/api/manager/assessments/$assignmentId/submit" -Token $managerToken
Check 'Manager submits the assessment' (
    $managerSubmit.Status -eq 200 -and $managerSubmit.Body.submitted -eq $true
)

# ---------------------------------------------------------------------------
# 11. HR completes the assessment
# ---------------------------------------------------------------------------
$hrDetail = Invoke-Api -Method 'GET' -Path "/api/hr/assessments/$assignmentId" -Token $hrToken
$hrKpis = @($hrDetail.Body.kpis | Where-Object { $_.kpiType -eq 'HR' })
$hrDraft = @{
    kpis = @($hrKpis | ForEach-Object {
        @{ employeeKpiId = $_.employeeKpiId; rating = 5; comment = 'HR rating' }
    })
}
$hrSave = Invoke-Api -Method 'PUT' -Path "/api/hr/assessments/$assignmentId/draft" -Token $hrToken -Body $hrDraft
Check 'HR saves the HR-assessed KPI ratings' ($hrSave.Status -eq 200) ("HTTP " + $hrSave.Status)

$hrSubmit = Invoke-Api -Method 'POST' -Path "/api/hr/assessments/$assignmentId/submit" -Token $hrToken
Check 'HR completes the assessment' (
    $hrSubmit.Status -eq 200 -and $hrSubmit.Body.assignmentStatus -eq 'COMPLETED'
)

$cycleResults = Invoke-Api -Method 'GET' -Path "/api/hr/results/cycles/$cycleId" -Token $hrToken
$result = @($cycleResults.Body) | Select-Object -First 1
Check 'HR sees the generated DRAFT result' (
    $cycleResults.Status -eq 200 -and $null -ne $result -and $result.status -eq 'DRAFT'
)
$resultId = $result.resultId

# ---------------------------------------------------------------------------
# 12. Unpublished results remain inaccessible to employee and manager
# ---------------------------------------------------------------------------
$employeeUnpublished = Invoke-Api -Method 'GET' -Path "/api/employee/results/$resultId" -Token $employeeToken
Check 'Employee cannot read an unpublished result' ($employeeUnpublished.Status -eq 404) ("HTTP " + $employeeUnpublished.Status)

$managerUnpublished = Invoke-Api -Method 'GET' -Path "/api/manager/results/$resultId" -Token $managerToken
Check 'Manager cannot read an unpublished result' ($managerUnpublished.Status -eq 404) ("HTTP " + $managerUnpublished.Status)

$employeeListBefore = Invoke-Api -Method 'GET' -Path '/api/employee/results' -Token $employeeToken
Check 'Unpublished result is absent from the employee result list' (@($employeeListBefore.Body).Count -eq 0)

$managerListBefore = Invoke-Api -Method 'GET' -Path '/api/manager/results' -Token $managerToken
Check 'Unpublished result is absent from the manager result list' (@($managerListBefore.Body).Count -eq 0)

# ---------------------------------------------------------------------------
# 13. HR publishes the results; employee and manager can then see them
# ---------------------------------------------------------------------------
$publish = Invoke-Api -Method 'POST' -Path "/api/hr/results/cycles/$cycleId/publish" -Token $hrToken
Check 'HR publishes the cycle results' ($publish.Status -eq 200) ("HTTP " + $publish.Status)

$employeeResult = Invoke-Api -Method 'GET' -Path "/api/employee/results/$resultId" -Token $employeeToken
Check 'Employee can read the published result' (
    $employeeResult.Status -eq 200 -and $employeeResult.Body.status -eq 'PUBLISHED'
)

$managerResult = Invoke-Api -Method 'GET' -Path "/api/manager/results/$resultId" -Token $managerToken
Check 'Manager can read the published result' (
    $managerResult.Status -eq 200 -and $managerResult.Body.status -eq 'PUBLISHED'
)

$employeeListAfter = Invoke-Api -Method 'GET' -Path '/api/employee/results' -Token $employeeToken
$employeeHasResult = @($employeeListAfter.Body | Where-Object { [int64]$_.resultId -eq [int64]$resultId }).Count -eq 1
Check 'Published result appears in the employee result list' $employeeHasResult

$managerListAfter = Invoke-Api -Method 'GET' -Path '/api/manager/results' -Token $managerToken
$managerHasResult = @($managerListAfter.Body | Where-Object { [int64]$_.resultId -eq [int64]$resultId }).Count -eq 1
Check 'Published result appears in the manager result list' $managerHasResult

# ---------------------------------------------------------------------------
# 14. Password recovery through the captured OTP email
# ---------------------------------------------------------------------------
$forgot = Invoke-Api -Method 'POST' -Path '/api/auth/forgot-password' -Body @{ email = $employeeEmail }
Check 'Forgot-password request is accepted' ($forgot.Status -eq 200) ("HTTP " + $forgot.Status)

$otpCapture = @(Get-Captures) | Where-Object { $_.type -eq 'otp' -and $_.recipient -eq $employeeEmail } | Select-Object -Last 1
Check 'OTP email captured (safe E2E capture)' ($null -ne $otpCapture)

$verify = Invoke-Api -Method 'POST' -Path '/api/auth/verify-reset-otp' -Body @{ otp = $otpCapture.content }
Check 'OTP verification returns a reset ticket' (
    $verify.Status -eq 200 -and $verify.Body.valid -eq $true -and $verify.Body.ticket
)

$newPassword = ([guid]::NewGuid().ToString('N').Substring(0, 12)) + 'R1!'
$reset = Invoke-Api -Method 'POST' -Path '/api/auth/reset-password' -Body @{
    token           = $verify.Body.ticket
    newPassword     = $newPassword
    confirmPassword = $newPassword
}
Check 'Password reset succeeds with the ticket' ($reset.Status -eq 200) ("HTTP " + $reset.Status)

$relogin = Login $employeeEmail $newPassword
Check 'Employee logs in with the recovered password' ($null -ne $relogin)

# The pre-reset JWT must no longer be accepted (token version invalidation).
$staleCall = Invoke-Api -Method 'GET' -Path '/api/auth/me' -Token $employeeToken
Check 'Pre-reset JWT is rejected after the password reset' ($staleCall.Status -eq 401) ("HTTP " + $staleCall.Status)

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
Write-Output ''
Write-Output ("RESULT: {0} passed, {1} failed" -f $script:pass, $script:fail)
if ($script:fail -gt 0) { exit 1 }
