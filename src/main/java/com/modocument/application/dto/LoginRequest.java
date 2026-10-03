package com.modocument.application.dto;

import jakarta.validation.constraints.Size;

/** JSON body of POST /api/auth/login. */
public class LoginRequest {

    // Size caps only (AppUsers.Email is varchar(256)): a missing or blank
    // credential must keep answering 401 through AuthUseCase, not 400.
    @Size(max = 256)
    private String email;

    @Size(max = 200)
    private String password;

    public LoginRequest() {
    }

    public LoginRequest(String email, String password) {
        this.email = email;
        this.password = password;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
