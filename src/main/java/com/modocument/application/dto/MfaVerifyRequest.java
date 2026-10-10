package com.modocument.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * JSON body of POST /api/auth/mfa/verify: the challenge token a correct
 * password bought, plus the second factor (TOTP or recovery code).
 */
public class MfaVerifyRequest {

    @NotBlank
    @Size(max = 2048)
    private String challenge;

    @NotBlank
    @Size(max = 64)
    private String code;

    public MfaVerifyRequest() {
    }

    public MfaVerifyRequest(String challenge, String code) {
        this.challenge = challenge;
        this.code = code;
    }

    public String getChallenge() {
        return challenge;
    }

    public void setChallenge(String challenge) {
        this.challenge = challenge;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
