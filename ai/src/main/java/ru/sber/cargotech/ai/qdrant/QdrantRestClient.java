package ru.sber.cargotech.ai.qdrant;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ru.sber.cargotech.ai.config.QdrantProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class QdrantRestClient {

    private final QdrantProperties properties;
    private final RestClient restClient;

    public QdrantRestClient(QdrantProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClient = restClientBuilder.build();
    }

    public String healthRaw() {
        return restClient.get()
                .uri(properties.getUrl())
                .retrieve()
                .body(String.class);
    }

    public Object ensureCollection() {
        try {
            Object existing = getCollection();
            validateExistingCollection(existing);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("action", "EXISTS");
            result.put("collection_name", properties.getCollectionName());
            result.put("vector_size", properties.getVectorSize());
            result.put("distance", properties.getDistance());
            result.put("collection", existing);
            return result;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 404) {
                throw e;
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("action", "CREATED");
            result.put("collection_name", properties.getCollectionName());
            result.put("vector_size", properties.getVectorSize());
            result.put("distance", properties.getDistance());
            result.put("qdrant_response", createCollection());
            return result;
        }
    }

    public Object getCollection() {
        return restClient.get()
                .uri(properties.getUrl() + "/collections/" + properties.getCollectionName())
                .retrieve()
                .body(Object.class);
    }

    public Object upsertPoints(List<Map<String, Object>> points) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("points", points);

        return restClient.put()
                .uri(properties.getUrl() + "/collections/" + properties.getCollectionName() + "/points?wait=true")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Object.class);
    }

    public Object queryPoints(
            List<Double> queryVector,
            Map<String, Object> exactFilters,
            int limit
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", queryVector);
        body.put("limit", limit);
        body.put("with_payload", true);
        body.put("with_vector", false);

        Map<String, Object> filter = buildFilter(exactFilters);

        if (!filter.isEmpty()) {
            body.put("filter", filter);
        }

        return restClient.post()
                .uri(properties.getUrl() + "/collections/" + properties.getCollectionName() + "/points/query")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Object.class);
    }

    private Object createCollection() {
        Map<String, Object> vectors = new LinkedHashMap<>();
        vectors.put("size", properties.getVectorSize());
        vectors.put("distance", properties.getDistance());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vectors", vectors);

        return restClient.put()
                .uri(properties.getUrl() + "/collections/" + properties.getCollectionName())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Object.class);
    }

    @SuppressWarnings("unchecked")
    private void validateExistingCollection(Object collectionResponse) {
        if (!(collectionResponse instanceof Map<?, ?> root)) {
            throw new IllegalStateException("Qdrant collection response has unexpected format");
        }

        Object resultObject = root.get("result");
        if (!(resultObject instanceof Map<?, ?> result)) {
            throw new IllegalStateException("Qdrant collection response does not contain result");
        }

        Object configObject = result.get("config");
        if (!(configObject instanceof Map<?, ?> config)) {
            throw new IllegalStateException("Qdrant collection response does not contain config");
        }

        Object paramsObject = config.get("params");
        if (!(paramsObject instanceof Map<?, ?> params)) {
            throw new IllegalStateException("Qdrant collection response does not contain params");
        }

        Object vectorsObject = params.get("vectors");
        if (!(vectorsObject instanceof Map<?, ?> vectors)) {
            throw new IllegalStateException("Qdrant collection response does not contain vectors config");
        }

        Integer actualSize = intValue(vectors.get("size"));
        String actualDistance = stringValue(vectors.get("distance"));

        if (!properties.getVectorSize().equals(actualSize)) {
            throw new IllegalStateException("Qdrant collection vector size mismatch. Expected "
                    + properties.getVectorSize() + ", actual " + actualSize);
        }

        if (actualDistance == null || !actualDistance.equalsIgnoreCase(properties.getDistance())) {
            throw new IllegalStateException("Qdrant collection distance mismatch. Expected "
                    + properties.getDistance() + ", actual " + actualDistance);
        }
    }

    private Map<String, Object> buildFilter(Map<String, Object> exactFilters) {
        Map<String, Object> filter = new LinkedHashMap<>();

        if (exactFilters == null || exactFilters.isEmpty()) {
            return filter;
        }

        List<Map<String, Object>> must = exactFilters.entrySet()
                .stream()
                .filter(entry -> entry.getValue() != null)
                .map(entry -> {
                    Map<String, Object> match = new LinkedHashMap<>();
                    match.put("value", entry.getValue());

                    Map<String, Object> condition = new LinkedHashMap<>();
                    condition.put("key", entry.getKey());
                    condition.put("match", match);

                    return condition;
                })
                .toList();

        if (!must.isEmpty()) {
            filter.put("must", must);
        }

        return filter;
    }

    private Integer intValue(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Number number) {
            return number.intValue();
        }

        return Integer.parseInt(String.valueOf(value));
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
