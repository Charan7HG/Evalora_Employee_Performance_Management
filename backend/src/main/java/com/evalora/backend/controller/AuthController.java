package com.evalora.backend.controller;

import com.evalora.backend.dto.*;
import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.exception.HrApiException;
import com.evalora.backend.security.EvaloraPrincipal;
import com.evalora.backend.security.SecurityErrorResponse;
import com.evalora.backend.service.AuthService;
import com.evalora.backend.service.EmployeeManagementService;
import com.evalora.backend.service.ForgotPasswordResult;
import com.evalora.backend.service.LoginFailure;
import com.evalora.backend.service.LoginResult;
import com.evalora.backend.service.PasswordOperationResult;
import com.evalora.backend.service.PasswordService;
import com.evalora.backend.util.PasswordPolicy;
import com.evalora.backend.util.PasswordResetTokenUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static com.evalora.backend.controller.AuthController.*;

/**
 * Authentication endpoints: login, current user, logout, change password,
 * forgot password with OTP, and account activation.
 *
 * <p>The controller only translates the service outcome into HTTP. The service
 * owns every security decision; no account detail is read from the request
 * except the credentials themselves.</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    static final String ACCOUNT_LOCKED_MESSAGE = "Your account is temporarily blocked. Try again after 5 minutes.";
    static final String INVALID_CREDENTIALS_MESSAGE = "Invalid email or password.";
    static final String LOGOUT_MESSAGE = "Logged out successfully.";
    static final String PASSWORD_CHANGED_MESSAGE = "Password changed successfully.";
    static final String PASSWORD_RESET_MESSAGE = "Password changed successfully.";
    static final String CURRENT_PASSWORD_INCORRECT_MESSAGE = "Current password is incorrect.";
    static final String PASSWORD_MISMATCH_MESSAGE = "New password and confirmation do not match.";
    static final String PASSWORD_REUSED_MESSAGE =
            "New password must be different from the current password.";
    static final String INVALID_RESET_TOKEN_MESSAGE = "Invalid or expired reset token.";
    static final String DEV_RESET_NOT_CONFIGURED_MESSAGE =
            "Password change is currently unavailable. Please contact your administrator.";
    static final String OTP_SENT_MESSAGE = "OTP has been sent to your email address.";
    static final String OTP_VERIFIED_MESSAGE = "OTP verified. Please set your new password.";
    static final String ACTIVATION_SENT_MESSAGE = "Activation email sent.";
    static final String ACTIVATION_LINK_SENT_MESSAGE = "Activation link sent.";
    static final String ACCOUNT_ALREADY_ACTIVE_MESSAGE = "Account is already active.";
    static final String ACCOUNT_ACTIVATED_MESSAGE = "Account activated successfully.";
    static final String RESEND_COOLDOWN_MESSAGE = "Please resend after %d seconds.";

    private final AuthService authService;
    private final PasswordService passwordService;
    private final EmployeeManagementService employeeManagementService;

    public AuthController(AuthService authService,
                          PasswordService passwordService,
                          EmployeeManagementService employeeManagementService) {
        this.authService = authService;
        this.passwordService = passwordService;
        this.employeeManagementService = employeeManagementService;
    }

    /**
     * Common login for all three roles. The body contains no role and no user
     * id: the identity is the email and the role comes from the database.
     */
    @Operation(security = @SecurityRequirement())
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request,
                                   BindingResult bindingResult,
                                   HttpServletRequest httpRequest) {
        if (bindingResult.hasErrors()) {
            return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", firstError(bindingResult),
                    httpRequest.getRequestURI());
        }

        LoginResult result = authService.login(request.email(), request.password());

        if (result.isSuccess()) {
            return ResponseEntity.ok(result.response());
        }

        if (result.failure() == LoginFailure.ACCOUNT_LOCKED) {
            // The remaining lock time is public to the holder of this email
            // password pair: it is only returned after a correct-format attempt
            // and never reveals whether the account exists beyond the lock
            // itself. No token, hash or counter is exposed.
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            long retryAfterSeconds = result.retryAfterSeconds();
            SecurityErrorResponse body = new SecurityErrorResponse(
                    now.toString(),
                    HttpStatus.LOCKED.value(),
                    "ACCOUNT_LOCKED",
                    ACCOUNT_LOCKED_MESSAGE,
                    httpRequest.getRequestURI(),
                    now.plusSeconds(retryAfterSeconds).toEpochSecond(),
                    retryAfterSeconds);
            return ResponseEntity.status(HttpStatus.LOCKED).body(body);
        }

        // Unknown email, wrong password and disabled account share one response.
        return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", INVALID_CREDENTIALS_MESSAGE,
                httpRequest.getRequestURI());
    }

    /**
     * Returns the caller's own identity. There is no id parameter: the id, the
     * email and the role all come from the database-backed principal, so a
     * client cannot ask for somebody else's account.
     */
    @GetMapping("/me")
    public AuthUserResponse currentUser(@AuthenticationPrincipal EvaloraPrincipal principal) {
        AuthUserResponse current = authService.currentUser(principal.getUserId());
        if (current != null) {
            return current;
        }
        return new AuthUserResponse(principal.getUserId(), principal.getUsername(),
                principal.getRole().name(), null);
    }

    /**
     * Stateless logout: the server keeps no session and no denylist, so there
     * is nothing to invalidate. The response only confirms the client should
     * discard its token; the token itself stays valid until it expires.
     */
    @PostMapping("/logout")
    public LogoutResponse logout() {
        return new LogoutResponse(LOGOUT_MESSAGE);
    }

    /**
     * Changes the password of the authenticated account. The user id comes only
     * from the SecurityContext, so this endpoint can never target another user.
     */
    @PutMapping("/change-password")
    public ResponseEntity<?> changePassword(@AuthenticationPrincipal EvaloraPrincipal principal,
                                            @Valid @RequestBody ChangePasswordRequest request,
                                            BindingResult bindingResult,
                                            HttpServletRequest httpRequest) {
        if (bindingResult.hasErrors()) {
            return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", firstError(bindingResult),
                    httpRequest.getRequestURI());
        }

        PasswordOperationResult result = passwordService.changePassword(
                principal.getUserId(),
                request.currentPassword(),
                request.newPassword(),
                request.confirmPassword());

        if (result == PasswordOperationResult.SUCCESS) {
            return ResponseEntity.ok(new PasswordOperationResponse(PASSWORD_CHANGED_MESSAGE));
        }

        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", failureMessage(result),
                httpRequest.getRequestURI());
    }

    /**
     * Public endpoint. Accepts an email address and sends a 6-digit OTP via
     * Brevo for password reset. The same generic response is returned whether
     * the email belongs to an account, an unknown email, or a disabled account,
     * to prevent email enumeration.
     */
    @Operation(security = @SecurityRequirement())
    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request,
                                            BindingResult bindingResult,
                                            HttpServletRequest httpRequest) {
        if (bindingResult.hasErrors()) {
            return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", firstError(bindingResult),
                    httpRequest.getRequestURI());
        }

        ForgotPasswordResult result = passwordService.forgotPassword(request.email());
        return ResponseEntity.ok(new ForgotPasswordResponse(result.message()));
    }

    /**
     * Public endpoint. Verifies the OTP and, if valid, issues a short-lived
     * single-use password-reset ticket. The ticket can be used with
     * {@code POST /api/auth/reset-password} to set a new password.
     * Invalid, expired, used and disabled-account tokens all receive the same
     * safe failure response.
     */
    @Operation(security = @SecurityRequirement())
    @PostMapping("/verify-reset-otp")
    public ResponseEntity<?> verifyResetOtp(@Valid @RequestBody VerifyOtpRequest request,
                                            BindingResult bindingResult,
                                            HttpServletRequest httpRequest) {
        if (bindingResult.hasErrors()) {
            return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", firstError(bindingResult),
                    httpRequest.getRequestURI());
        }

        // Delegate to PasswordService which handles OTP cache lookup and verification
        var result = passwordService.verifyOtp(request.email(), request.otp());

        return ResponseEntity.ok(new VerifyOtpResponse(
                result.valid(), result.ticket(), result.message()));
    }

    /**
     * Public endpoint. Sets a new password after validating the reset ticket.
     * Invalid, expired, used and disabled-account tickets all receive the same
     * safe failure response. Passwords must satisfy the existing password policy.
     */
    @Operation(security = @SecurityRequirement())
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@Valid @RequestBody ResetPasswordRequest request,
                                           BindingResult bindingResult,
                                           HttpServletRequest httpRequest) {
        if (bindingResult.hasErrors()) {
            return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", firstError(bindingResult),
                    httpRequest.getRequestURI());
        }

        PasswordOperationResult result = passwordService.resetPassword(
                request.token(), request.newPassword(), request.confirmPassword());

        if (result == PasswordOperationResult.SUCCESS) {
            return ResponseEntity.ok(new PasswordOperationResponse(PASSWORD_RESET_MESSAGE));
        }

        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", failureMessage(result),
                httpRequest.getRequestURI());
    }

    /**
     * Activate a new employee or manager account using a single-use activation
     * token sent by email, and set the password chosen by the employee.
     * <p>
     * The token is hashed before it reaches the service, verified against the
     * database, and consumed on success. Invalid, expired and already-used
     * links return the same generic client shape with a clear message, without
     * revealing whether an account exists for a given email address.
     */
    @Operation(security = @SecurityRequirement())
    @PostMapping("/activate-account")
    public ResponseEntity<?> activateAccount(@Valid @RequestBody ActivateAccountRequest request,
                                             BindingResult bindingResult,
                                             HttpServletRequest httpRequest) {
        if (bindingResult.hasErrors()) {
            return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", firstError(bindingResult),
                    httpRequest.getRequestURI());
        }

        String tokenHash = PasswordResetTokenUtil.hash(request.token());
        var result = passwordService.activateAccount(
                tokenHash, request.newPassword(), request.confirmPassword());

        if (result.isActivated()) {
            return ResponseEntity.ok(new AccountStatusResponse(ACCOUNT_ACTIVATED_MESSAGE,
                    result.isEnabled(), result.message()));
        }

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new AccountStatusResponse(result.message(), result.isEnabled(),
                        result.message()));
    }

    /**
     * HR endpoint: resend activation email for a pending employee/manager account.
     * Previous activation tokens are invalidated before sending a new one.
     */
    @Operation(security = @SecurityRequirement())
    @PatchMapping("/hr/employees/{employeeId}/resend-activation")
    public ResponseEntity<?> resendActivationEmail(@PathVariable Long employeeId) {
        // Delegate to EmployeeManagementService which handles token invalidation
        // and new token generation
        var result = employeeManagementService.resendActivationToken(employeeId);

        if (result.resent()) {
            return ResponseEntity.ok(new AccountStatusResponse(ACTIVATION_SENT_MESSAGE,
                    result.enabled(), result.message()));
        }

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new AccountStatusResponse(result.message(), result.enabled(),
                        result.message()));
    }

    /**
     * HR endpoint: check pending activation status for an employee/manager account.
     * Returns whether the account has a pending activation token.
     */
    @Operation(security = @SecurityRequirement())
    @GetMapping("/hr/employees/{employeeId}/activation-status")
    public ResponseEntity<?> checkActivationStatus(@PathVariable Long employeeId) {
        // Delegate to EmployeeManagementService
        var status = employeeManagementService.checkActivationStatus(employeeId);

        return ResponseEntity.ok(new ActivationStatusResponse(status.hasPending(),
                status.enabled(), status.email()));
    }

    // ----------------------------------------------------------------- helpers

    static ResponseEntity<SecurityErrorResponse> error(HttpStatus status,
                                                               String error,
                                                               String message,
                                                               String path) {
        SecurityErrorResponse body = new SecurityErrorResponse(
                OffsetDateTime.now(ZoneOffset.UTC).toString(),
                status.value(), error, message, path);
        return ResponseEntity.status(status).body(body);
    }

    static String firstError(BindingResult bindingResult) {
        FieldError fieldError = bindingResult.getFieldErrors().stream().findFirst().orElse(null);
        return fieldError == null ? "Invalid request." : fieldError.getDefaultMessage();
    }

    static String failureMessage(PasswordOperationResult result) {
        return switch (result) {
            case CURRENT_PASSWORD_INCORRECT -> CURRENT_PASSWORD_INCORRECT_MESSAGE;
            case CONFIRMATION_MISMATCH -> PASSWORD_MISMATCH_MESSAGE;
            case PASSWORD_REUSED -> PASSWORD_REUSED_MESSAGE;
            case PASSWORD_POLICY_VIOLATION -> PasswordPolicy.MESSAGE;
            case INVALID_RESET_TOKEN -> INVALID_RESET_TOKEN_MESSAGE;
            case DEV_RESET_NOT_CONFIGURED -> DEV_RESET_NOT_CONFIGURED_MESSAGE;
            case SUCCESS -> throw new IllegalArgumentException("SUCCESS is not a failure.");
        };
    }
}