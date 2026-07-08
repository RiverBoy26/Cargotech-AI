package ru.sber.cargotech.claim.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Repository
public class ClaimOutboxWriter {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ClaimOutboxWriter(
        NamedParameterJdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public UUID write(
        String aggregateType,
        UUID aggregateId,
        String eventType,
        UUID organizationId,
        UUID userId,
        Map<String, Object> eventData
    ) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", organizationId);
        payload.put("userId", userId);
        payload.putAll(eventData);

        jdbcTemplate.update(
            """
            insert into cargotech.outbox_events (
                id,
                module_name,
                aggregate_type,
                aggregate_id,
                event_type,
                event_version,
                payload,
                status,
                created_at,
                retry_count
            ) values (
                :id,
                'CLAIM',
                :aggregateType,
                :aggregateId,
                :eventType,
                1,
                cast(:payload as jsonb),
                'NEW',
                current_timestamp,
                0
            )
            """,
            new MapSqlParameterSource()
                .addValue("id", eventId)
                .addValue("aggregateType", aggregateType)
                .addValue("aggregateId", aggregateId)
                .addValue("eventType", eventType)
                .addValue("payload", objectMapper.writeValueAsString(payload))
        );

        return eventId;
    }
}
