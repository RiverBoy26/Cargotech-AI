package ru.sber.cargotech.payment.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.sber.cargotech.payment.enums.PaymentTargetType;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PaymentTargetRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PaymentTargetRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean exists(
        UUID organizationId,
        PaymentTargetType targetType,
        UUID targetId
    ) {
        String sql = switch (targetType) {
            case CLAIM -> """
                select count(*)
                from cargotech.claim_claims
                where id = :targetId and organization_id = :organizationId
                """;
            case SHIPMENT -> """
                select count(*)
                from cargotech.claim_shipments
                where id = :targetId and organization_id = :organizationId
                """;
            case ACT -> """
                select count(*)
                from cargotech.document_documents
                where id = :targetId
                  and organization_id = :organizationId
                  and document_type = 'ACT'
                  and status = 'ACTIVE'
                """;
            case INVOICE -> """
                select count(*)
                from cargotech.document_documents
                where id = :targetId
                  and organization_id = :organizationId
                  and document_type = 'INVOICE'
                  and status = 'ACTIVE'
                """;
        };

        Integer count = jdbcTemplate.queryForObject(
            sql,
            new MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("targetId", targetId),
            Integer.class
        );
        return count != null && count > 0;
    }

    public Optional<BigDecimal> remainingAmount(
        UUID organizationId,
        PaymentTargetType targetType,
        UUID targetId
    ) {
        return switch (targetType) {
            case CLAIM -> queryAmount(
                """
                select greatest(
                    shipment.service_amount - coalesce((
                        select sum(pm.matched_amount)
                        from cargotech.payment_matches pm
                        where pm.active = true
                          and (
                              (pm.target_type = 'CLAIM' and pm.target_id = claim.id)
                              or
                              (pm.target_type = 'SHIPMENT' and pm.target_id = shipment.id)
                          )
                    ), 0),
                    0
                )
                from cargotech.claim_claims claim
                join cargotech.claim_shipments shipment on shipment.id = claim.shipment_id
                where claim.id = :targetId
                  and claim.organization_id = :organizationId
                """,
                organizationId,
                targetId
            );
            case SHIPMENT -> queryAmount(
                """
                select greatest(
                    shipment.service_amount - coalesce((
                        select sum(pm.matched_amount)
                        from cargotech.payment_matches pm
                        where pm.active = true
                          and (
                              (pm.target_type = 'SHIPMENT' and pm.target_id = shipment.id)
                              or
                              (pm.target_type = 'CLAIM' and pm.target_id in (
                                  select claim.id
                                  from cargotech.claim_claims claim
                                  where claim.shipment_id = shipment.id
                              ))
                          )
                    ), 0),
                    0
                )
                from cargotech.claim_shipments shipment
                where shipment.id = :targetId
                  and shipment.organization_id = :organizationId
                """,
                organizationId,
                targetId
            );
            case ACT, INVOICE -> Optional.empty();
        };
    }

    public List<PaymentTargetCandidate> findShipmentsMentionedInPurpose(
        UUID organizationId,
        String purpose
    ) {
        if (purpose == null || purpose.isBlank()) {
            return List.of();
        }
        return queryShipmentCandidates(
            """
            and position(lower(shipment.order_number) in lower(:purpose)) > 0
            """,
            organizationId,
            null,
            purpose
        );
    }

    public List<PaymentTargetCandidate> findShipmentsByPayerInn(
        UUID organizationId,
        String payerInn
    ) {
        if (payerInn == null || payerInn.isBlank()) {
            return List.of();
        }
        return queryShipmentCandidates(
            """
            and client.inn = :payerInn
            """,
            organizationId,
            payerInn,
            null
        );
    }

    private List<PaymentTargetCandidate> queryShipmentCandidates(
        String extraFilter,
        UUID organizationId,
        String payerInn,
        String purpose
    ) {
        String sql = """
            select
                shipment.id,
                shipment.order_number,
                shipment.service_amount,
                greatest(
                    shipment.service_amount - coalesce((
                        select sum(pm.matched_amount)
                        from cargotech.payment_matches pm
                        where pm.active = true
                          and (
                              (pm.target_type = 'SHIPMENT' and pm.target_id = shipment.id)
                              or
                              (pm.target_type = 'CLAIM' and pm.target_id in (
                                  select c.id
                                  from cargotech.claim_claims c
                                  where c.shipment_id = shipment.id
                              ))
                          )
                    ), 0),
                    0
                ) as remaining_amount
            from cargotech.claim_shipments shipment
            join cargotech.claim_parties client on client.id = shipment.client_id
            where shipment.organization_id = :organizationId
              and shipment.status <> 'CANCELLED'
              and not exists (
                  select 1
                  from cargotech.claim_claims active_claim
                  where active_claim.shipment_id = shipment.id
                    and active_claim.status not in ('PAID', 'CANCELLED', 'CLOSED_IN_COURT')
              )
            """ + extraFilter + """
            order by shipment.created_at
            """;

        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("organizationId", organizationId)
            .addValue("payerInn", payerInn)
            .addValue("purpose", purpose);

        return jdbcTemplate.query(
            sql,
            parameters,
            (rs, rowNum) -> new PaymentTargetCandidate(
                PaymentTargetType.SHIPMENT,
                rs.getObject("id", UUID.class),
                rs.getString("order_number"),
                rs.getBigDecimal("service_amount"),
                rs.getBigDecimal("remaining_amount")
            )
        );
    }

    private Optional<BigDecimal> queryAmount(
        String sql,
        UUID organizationId,
        UUID targetId
    ) {
        List<BigDecimal> result = jdbcTemplate.query(
            sql,
            new MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("targetId", targetId),
            (rs, rowNum) -> rs.getBigDecimal(1)
        );
        return result.stream().findFirst();
    }
}
