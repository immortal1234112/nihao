package com.platform.security.jwt;

public record TokenPair(String accessToken, String refreshToken, long expiresIn) {
}
