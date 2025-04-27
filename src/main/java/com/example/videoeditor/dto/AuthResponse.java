package com.example.videoeditor.dto;

public class AuthResponse {
    private String token;
    private String email;
    private String name;
    private String message;
    private boolean isVerified;

    // Update constructor
    public AuthResponse(String token, String email, String name, String message, boolean isVerified) {
        this.token = token;
        this.email = email;
        this.name = name;
        this.message = message;
        this.isVerified = isVerified;
    }

    // Add getters/setters for new fields
    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public boolean isVerified() {
        return isVerified;
    }

    public void setVerified(boolean verified) {
        isVerified = verified;
    }
}