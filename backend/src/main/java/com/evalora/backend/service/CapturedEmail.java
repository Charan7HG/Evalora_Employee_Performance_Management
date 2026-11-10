package com.evalora.backend.service;

public record CapturedEmail(String type, String recipient, Long accountId, String content) {
}
