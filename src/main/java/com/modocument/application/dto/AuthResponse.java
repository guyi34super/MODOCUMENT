package com.modocument.application.dto;

/** Response of POST /api/auth/login and /api/auth/register. */
public record AuthResponse(String token, UserDto user) {
}
