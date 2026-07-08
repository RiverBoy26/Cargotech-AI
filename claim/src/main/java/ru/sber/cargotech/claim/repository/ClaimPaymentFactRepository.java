package ru.sber.cargotech.claim.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.UUID;

@Repository
public class ClaimPaymentFactRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ClaimPaymentFactRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public BigDecimal sumPaidForClaimOrShipment(UUID organizationId, UUID claimId, UUID shipmentId) {
        BigDecimal value = jdbcTemplate.queryForObject(
            """
            select coalesce(sum(pm.matched_amount), 0) as paid_amount
            from cargotech.payment_matches pm
            join cargotech.payment_payments p on p.id = pm.payment_id
            where p.organization_id = :organizationId
              and pm.active = true
              and (
                    (pm.target_type = 'CLAIM' and pm.target_id = :claimId)
                 or (pm.target_type = 'SHIPMENT' and pm.target_id = :shipmentId)
              )
            """,
            new MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("claimId", claimId)
                .addValue("shipmentId", shipmentId),
            BigDecimal.class
        );
        return value == null ? BigDecimal.ZERO : value;
    }
}
