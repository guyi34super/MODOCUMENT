package com.modocument.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * JSON body of POST /api/auth/mfa/activate and DELETE /api/auth/mfa: a TOTP
 * code (activate) or a TOTP/recovery code (disable). Size cap only — the
 * use case decides validity and answers the generic 401.
 */
public class MfaCodeRequest {

    @NotBlank
    @Size(max = 64)
    private String code;

    public MfaCodeRequest() {
    }

    public MfaCodeRequest(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
