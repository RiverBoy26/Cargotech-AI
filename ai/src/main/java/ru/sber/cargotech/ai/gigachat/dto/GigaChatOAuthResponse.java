package ru.sber.cargotech.ai.gigachat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class GigaChatOAuthResponse {

    @JsonProperty("access_token")
    private String accessToken;

    @JsonProperty("expires_at")
    private Long expiresAt;

    @JsonProperty("token_type")
    private String tokenType;

    public String getAccessToken() {
        return accessToken;
    }

    public Long getExpiresAt() {
        return expiresAt;
    }

    public String getTokenType() {
        return tokenType;
    }
}