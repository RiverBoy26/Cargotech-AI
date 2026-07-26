package ru.sber.cargotech.payment.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.sber.cargotech.payment.exception.PaymentException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Repository
public class PaymentOutboxWriter {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PaymentOutboxWriter(
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

        String json;
        json = objectMapper.writeValueAsString(payload);

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
                'PAYMENT',
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
                .addValue("payload", json)
        );

        return eventId;
    }
}
