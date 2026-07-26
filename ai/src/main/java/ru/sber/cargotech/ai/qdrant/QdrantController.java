package ru.sber.cargotech.ai.qdrant;

import org.springframework.web.bind.annotation.*;
import ru.sber.cargotech.ai.config.QdrantProperties;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/qdrant")
public class QdrantController {

    private final QdrantRestClient qdrantRestClient;
    private final QdrantProperties properties;

    public QdrantController(QdrantRestClient qdrantRestClient, QdrantProperties properties) {
        this.qdrantRestClient = qdrantRestClient;
        this.properties = properties;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("qdrant_url", properties.getUrl());
        result.put("raw", qdrantRestClient.healthRaw());
        result.put("checkedAt", Instant.now().toString());
        return result;
    }

    @PostMapping("/collections/ensure")
    public Map<String, Object> ensureCollection() {
        Object qdrantResponse = qdrantRestClient.ensureCollection();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("collection_name", properties.getCollectionName());
        result.put("vector_size", properties.getVectorSize());
        result.put("distance", properties.getDistance());
        result.put("qdrant_response", qdrantResponse);
        result.put("checkedAt", Instant.now().toString());
        return result;
    }

    @GetMapping("/collections/current")
    public Map<String, Object> getCurrentCollection() {
        Object collection = qdrantRestClient.getCollection();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("collection_name", properties.getCollectionName());
        result.put("collection", collection);
        result.put("checkedAt", Instant.now().toString());
        return result;
    }
}