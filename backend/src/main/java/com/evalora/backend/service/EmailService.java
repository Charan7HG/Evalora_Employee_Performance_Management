package com.evalora.backend.service;

public interface EmailService {

    boolean isEmailEnabled();

    void sendActivationEmail(String recipientEmail, String recipientName,
                             Long userId, String activationUrl, String templateType);

    void sendOtpEmail(String recipientEmail, String recipientName, String otp);
}
