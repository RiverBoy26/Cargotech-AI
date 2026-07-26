package ru.sber.cargotech.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gigachat")
public class GigaChatProperties {

    private String authUrl;
    private String chatUrl;
    private String scope;
    private String clientId;
    private String authKey;
    private String chatModel;
    private Double temperature = 0.1;
    private Integer maxTokens = 1000;
    private long tokenRefreshSkewSeconds = 60;
    private String embeddingsUrl;
    private String embeddingsModel;

    public String getAuthUrl() {
        return authUrl;
    }

    public void setAuthUrl(String authUrl) {
        this.authUrl = authUrl;
    }

    public String getChatUrl() {
        return chatUrl;
    }

    public void setChatUrl(String chatUrl) {
        this.chatUrl = chatUrl;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getAuthKey() {
        return authKey;
    }

    public void setAuthKey(String authKey) {
        this.authKey = authKey;
    }

    public String getChatModel() {
        return chatModel;
    }

    public void setChatModel(String chatModel) {
        this.chatModel = chatModel;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public long getTokenRefreshSkewSeconds() {
        return tokenRefreshSkewSeconds;
    }

    public void setTokenRefreshSkewSeconds(long tokenRefreshSkewSeconds) {
        this.tokenRefreshSkewSeconds = tokenRefreshSkewSeconds;
    }
    
    public String getEmbeddingsUrl() {
        return embeddingsUrl;
    }

    public void setEmbeddingsUrl(String embeddingsUrl) {
        this.embeddingsUrl = embeddingsUrl;
    }

    public String getEmbeddingsModel() {
        return embeddingsModel;
    }

    public void setEmbeddingsModel(String embeddingsModel) {
        this.embeddingsModel = embeddingsModel;
    }
}