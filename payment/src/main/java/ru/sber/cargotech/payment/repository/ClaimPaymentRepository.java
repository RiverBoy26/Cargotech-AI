package ru.sber.cargotech.payment.repository;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ClaimPaymentRepository {

    private static final String BASE_SELECT = """
        select
            c.id,
            c.shipment_id,
            c.claim_number,
            party.inn as debtor_inn,
            shipment.order_number as shipment_order_number,
            shipment.service_amount,
            c.status
        from cargotech.claim_claims c
        join cargotech.claim_parties party on party.id = c.debtor_id
        join cargotech.claim_shipments shipment on shipment.id = c.shipment_id
        """;

    private static final String OPEN_STATUSES_FILTER =
        "c.status not in ('PAID', 'CANCELLED', 'CLOSED_IN_COURT')";

    private static final RowMapper<ClaimPaymentData> ROW_MAPPER = (rs, rowNum) ->
        new ClaimPaymentData(
            rs.getObject("id", UUID.class),
            rs.getObject("shipment_id", UUID.class),
            rs.getString("claim_number"),
            rs.getString("debtor_inn"),
            rs.getString("shipment_order_number"),
            rs.getBigDecimal("service_amount"),
            rs.getString("status")
        );

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ClaimPaymentRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ClaimPaymentData> findByIdAndOrganizationId(
        UUID claimId,
        UUID organizationId
    ) {
        String sql = BASE_SELECT + """
            where c.id = :claimId
              and c.organization_id = :organizationId
            """;

        return jdbcTemplate.query(
            sql,
            new MapSqlParameterSource()
                .addValue("claimId", claimId)
                .addValue("organizationId", organizationId),
            ROW_MAPPER
        ).stream().findFirst();
    }

    public List<ClaimPaymentData> findOpenByPayerInn(
        UUID organizationId,
        String payerInn
    ) {
        if (payerInn == null || payerInn.isBlank()) {
            return List.of();
        }

        String sql = BASE_SELECT + """
            where c.organization_id = :organizationId
              and party.inn = :payerInn
              and """ + OPEN_STATUSES_FILTER + """
            order by c.created_at
            """;

        return jdbcTemplate.query(
            sql,
            new MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("payerInn", payerInn),
            ROW_MAPPER
        );
    }

    public List<ClaimPaymentData> findMentionedInPurpose(
        UUID organizationId,
        String purpose
    ) {
        if (purpose == null || purpose.isBlank()) {
            return List.of();
        }

        String sql = BASE_SELECT + """
            where c.organization_id = :organizationId
              and position(lower(c.claim_number) in lower(:purpose)) > 0
              and """ + OPEN_STATUSES_FILTER + """
            order by c.created_at
            """;

        return jdbcTemplate.query(
            sql,
            new MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("purpose", purpose),
            ROW_MAPPER
        );
    }

    public int updateLastPaymentCheck(
        UUID claimId,
        UUID organizationId,
        UUID checkId
    ) {
        return jdbcTemplate.update(
            """
            update cargotech.claim_claims
               set last_payment_check_id = :checkId
             where id = :claimId
               and organization_id = :organizationId
            """,
            new MapSqlParameterSource()
                .addValue("claimId", claimId)
                .addValue("organizationId", organizationId)
                .addValue("checkId", checkId)
        );
    }
}
