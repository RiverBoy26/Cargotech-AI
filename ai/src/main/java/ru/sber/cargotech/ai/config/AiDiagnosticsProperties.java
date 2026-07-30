package ru.sber.cargotech.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai.diagnostics")
public class AiDiagnosticsProperties {

    private boolean endpointsEnabled = false;

    public boolean isEndpointsEnabled() {
        return endpointsEnabled;
    }

    public void setEndpointsEnabled(boolean endpointsEnabled) {
        this.endpointsEnabled = endpointsEnabled;
    }
}
