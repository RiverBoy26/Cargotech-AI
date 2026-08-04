package ru.sber.cargotech.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag.search")
public class RagSearchProperties {

    private double contractMinScore = 0.45;
    private double legalMinScore = 0.45;
    private double templateMinScore = 0.40;
    private double exampleMinScore = 0.40;

    public double getContractMinScore() {
        return contractMinScore;
    }

    public void setContractMinScore(double contractMinScore) {
        this.contractMinScore = validateScore(contractMinScore, "contract-min-score");
    }

    public double getLegalMinScore() {
        return legalMinScore;
    }

    public void setLegalMinScore(double legalMinScore) {
        this.legalMinScore = validateScore(legalMinScore, "legal-min-score");
    }

    public double getTemplateMinScore() {
        return templateMinScore;
    }

    public void setTemplateMinScore(double templateMinScore) {
        this.templateMinScore = validateScore(templateMinScore, "template-min-score");
    }

    public double getExampleMinScore() {
        return exampleMinScore;
    }

    public void setExampleMinScore(double exampleMinScore) {
        this.exampleMinScore = validateScore(exampleMinScore, "example-min-score");
    }

    private double validateScore(double value, String field) {
        if (value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException("rag.search." + field + " must be between 0 and 1");
        }
        return value;
    }
}
