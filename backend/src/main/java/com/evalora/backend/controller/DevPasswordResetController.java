package com.evalora.backend.controller;

import com.evalora.backend.dto.DevResetPasswordRequest;
import com.evalora.backend.dto.PasswordOperationResponse;
import com.evalora.backend.service.PasswordOperationResult;
import com.evalora.backend.service.PasswordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Development-only direct password reset.
 *
 * <p>The bean exists only while the {@code dev} profile is active, so the route
 * is absent in every other profile. The request contains only the new password
 * and its confirmation; the account is fixed by
 * {@code evalora.dev-reset.email}, never by the caller. No email or token step
 * is involved, but the normal password policy, reuse check and BCrypt hashing
 * still apply.</p>
 */
@RestController
@Profile("dev")
@RequestMapping("/api/auth")
public class DevPasswordResetController {

    static final String DEV_RESET_MESSAGE = "Password changed successfully.";

    private final PasswordService passwordService;

    public DevPasswordResetController(PasswordService passwordService) {
        this.passwordService = passwordService;
    }

    @Operation(security = @SecurityRequirement())
    @PostMapping("/dev-reset-password")
    public ResponseEntity<?> reset(@Valid @RequestBody DevResetPasswordRequest request,
                                   BindingResult bindingResult,
                                   HttpServletRequest httpRequest) {
        if (bindingResult.hasErrors()) {
            return AuthController.error(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                    AuthController.firstError(bindingResult), httpRequest.getRequestURI());
        }

        PasswordOperationResult result = passwordService.devResetPassword(
                request.newPassword(), request.confirmPassword());

        if (result == PasswordOperationResult.SUCCESS) {
            return ResponseEntity.ok(new PasswordOperationResponse(DEV_RESET_MESSAGE));
        }

        return AuthController.error(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                AuthController.failureMessage(result), httpRequest.getRequestURI());
    }
}
