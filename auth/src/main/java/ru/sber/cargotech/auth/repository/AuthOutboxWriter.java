package ru.sber.cargotech.auth.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.sber.cargotech.auth.exception.AuthException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class AuthOutboxWriter {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public UUID write(
        String aggregateType,
        UUID aggregateId,
        String eventType,
        UUID organizationId,
        UUID actorUserId,
        Map<String, Object> eventPayload
    ) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        if (organizationId != null) {
            payload.put("organizationId", organizationId.toString());
        }
        if (actorUserId != null) {
            payload.put("actorUserId", actorUserId.toString());
        }
        payload.putAll(eventPayload);

        try {
            jdbc.update(
                """
                insert into cargotech.outbox_events(
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
                    'AUTH',
                    :aggregateType,
                    :aggregateId,
                    :eventType,
                    1,
                    cast(:payload as jsonb),
                    'NEW',
                    :createdAt,
                    0
                )
                """,
                Map.of(
                    "id", eventId,
                    "aggregateType", aggregateType,
                    "aggregateId", aggregateId,
                    "eventType", eventType,
                    "payload", objectMapper.writeValueAsString(payload),
                    "createdAt", OffsetDateTime.now()
                )
            );
            return eventId;
        } catch (JsonProcessingException exception) {
            throw AuthException.validation(
                "Не удалось сериализовать outbox-событие"
            );
        }
    }
}
