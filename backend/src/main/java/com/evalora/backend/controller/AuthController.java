package com.evalora.backend.controller;

import com.evalora.backend.dto.AuthUserResponse;
import com.evalora.backend.dto.ChangePasswordRequest;
import com.evalora.backend.dto.ForgotPasswordDevResponse;
import com.evalora.backend.dto.ForgotPasswordRequest;
import com.evalora.backend.dto.ForgotPasswordResponse;
import com.evalora.backend.dto.LoginRequest;
import com.evalora.backend.dto.LoginResponse;
import com.evalora.backend.dto.LogoutResponse;
import com.evalora.backend.dto.PasswordOperationResponse;
import com.evalora.backend.dto.ResetPasswordRequest;
import com.evalora.backend.security.EvaloraPrincipal;
import com.evalora.backend.security.SecurityErrorResponse;
import com.evalora.backend.service.AuthService;
import com.evalora.backend.service.ForgotPasswordResult;
import com.evalora.backend.service.LoginFailure;
import com.evalora.backend.service.LoginResult;
import com.evalora.backend.service.PasswordOperationResult;
import com.evalora.backend.service.PasswordService;
import com.evalora.backend.util.PasswordPolicy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Authentication endpoints: login, current user and stateless logout.
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

    private final AuthService authService;
    private final PasswordService passwordService;

    public AuthController(AuthService authService, PasswordService passwordService) {
        this.authService = authService;
        this.passwordService = passwordService;
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
        return new AuthUserResponse(principal.getUserId(), principal.getUsername(),
                principal.getRole().name());
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
     * Public endpoint. The same generic response is returned for an eligible
     * account, an unknown email and a disabled account. The raw token is
     * present only when the {@code dev} profile is active.
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

        if (result.resetToken() == null) {
            return ResponseEntity.ok(new ForgotPasswordResponse(result.message()));
        }
        return ResponseEntity.ok(new ForgotPasswordDevResponse(result.message(), result.resetToken()));
    }

    /**
     * Public endpoint. Invalid, expired, used and disabled-account tokens all
     * receive the same safe failure.
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

    static String firstError(BindingResult bindingResult) {
        FieldError fieldError = bindingResult.getFieldErrors().stream().findFirst().orElse(null);
        return fieldError == null ? "Invalid request." : fieldError.getDefaultMessage();
    }

    static ResponseEntity<SecurityErrorResponse> error(HttpStatus status,
                                                               String error,
                                                               String message,
                                                               String path) {
        SecurityErrorResponse body = new SecurityErrorResponse(
                OffsetDateTime.now(ZoneOffset.UTC).toString(),
                status.value(), error, message, path);
        return ResponseEntity.status(status).body(body);
    }
}
