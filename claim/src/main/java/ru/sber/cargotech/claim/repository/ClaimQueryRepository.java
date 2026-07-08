package ru.sber.cargotech.claim.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.sber.cargotech.claim.dto.ClaimDetailsResponse;
import ru.sber.cargotech.claim.dto.ClaimListItemResponse;
import ru.sber.cargotech.claim.enums.ClaimStatus;
import ru.sber.cargotech.claim.enums.ClaimType;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ClaimQueryRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ClaimQueryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Page<ClaimListItemResponse> findClaims(
        UUID organizationId,
        ClaimStatus status,
        UUID creditorId,
        UUID debtorId,
        UUID assignedLawyerId,
        String search,
        Pageable pageable
    ) {
        StringBuilder where = new StringBuilder(" where c.organization_id = :organizationId ");
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("organizationId", organizationId)
            .addValue("limit", pageable.getPageSize())
            .addValue("offset", pageable.getOffset());

        if (status != null) {
            where.append(" and c.status = :status ");
            params.addValue("status", status.name());
        }
        if (creditorId != null) {
            where.append(" and c.creditor_id = :creditorId ");
            params.addValue("creditorId", creditorId);
        }
        if (debtorId != null) {
            where.append(" and c.debtor_id = :debtorId ");
            params.addValue("debtorId", debtorId);
        }
        if (assignedLawyerId != null) {
            where.append(" and c.assigned_lawyer_id = :assignedLawyerId ");
            params.addValue("assignedLawyerId", assignedLawyerId);
        }
        if (search != null && !search.isBlank()) {
            where.append("""
                and (
                    lower(c.claim_number) like :search
                    or lower(s.order_number) like :search
                    or lower(creditor.name) like :search
                    or lower(debtor.name) like :search
                )
            """);
            params.addValue("search", "%" + search.toLowerCase() + "%");
        }

        String from = """
            from cargotech.claim_claims c
            join cargotech.claim_shipments s on s.id = c.shipment_id
            join cargotech.claim_parties creditor on creditor.id = c.creditor_id
            join cargotech.claim_parties debtor on debtor.id = c.debtor_id
            join cargotech.claim_contracts contract on contract.id = c.contract_id
            left join lateral (
                select calculation_version, overdue_days
                from cargotech.claim_calculations calc
                where calc.claim_id = c.id
                order by calc.calculation_version desc
                limit 1
            ) latest_calc on true
            """;

        List<ClaimListItemResponse> content = jdbcTemplate.query(
            """
            select
                c.id,
                c.claim_number,
                c.claim_type,
                c.status,
                c.shipment_id,
                s.order_number as shipment_number,
                c.creditor_id,
                creditor.name as creditor_name,
                c.debtor_id,
                debtor.name as debtor_name,
                c.assigned_lawyer_id,
                c.principal_debt,
                c.penalty_amount,
                c.total_amount,
                latest_calc.overdue_days,
                contract.payment_days,
                c.created_at,
                c.updated_at
            """ + from + where + " order by c.updated_at desc limit :limit offset :offset",
            params,
            claimListMapper()
        );

        Long total = jdbcTemplate.queryForObject(
            "select count(*) " + from + where,
            params,
            Long.class
        );

        return new PageImpl<>(content, pageable, total == null ? 0L : total);
    }

    public Optional<ClaimDetailsResponse> findDetails(UUID organizationId, UUID claimId) {
        List<ClaimDetailsResponse> result = jdbcTemplate.query(
            """
            select
                c.id,
                c.organization_id,
                c.claim_number,
                c.claim_type,
                c.status,
                c.reason,
                c.shipment_id,
                s.order_number as shipment_number,
                c.contract_id,
                contract.number as contract_number,
                c.creditor_id,
                creditor.name as creditor_name,
                c.debtor_id,
                debtor.name as debtor_name,
                c.principal_debt,
                c.penalty_amount,
                c.total_amount,
                c.non_payment_confirmed,
                c.non_payment_confirmed_at,
                c.non_payment_confirmed_by,
                c.non_payment_confirmation_comment,
                c.assigned_lawyer_id,
                c.final_version_id,
                c.last_payment_check_id,
                c.approved_at,
                c.approved_by,
                c.sent_at,
                c.paid_at,
                c.cancelled_at,
                c.cancellation_reason_code,
                c.cancellation_reason,
                c.escalated_at,
                c.created_at,
                c.created_by,
                c.updated_at,
                c.updated_by
            from cargotech.claim_claims c
            join cargotech.claim_shipments s on s.id = c.shipment_id
            join cargotech.claim_contracts contract on contract.id = c.contract_id
            join cargotech.claim_parties creditor on creditor.id = c.creditor_id
            join cargotech.claim_parties debtor on debtor.id = c.debtor_id
            where c.organization_id = :organizationId
              and c.id = :claimId
            """,
            new MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("claimId", claimId),
            claimDetailsMapper()
        );
        return result.stream().findFirst();
    }

    private RowMapper<ClaimListItemResponse> claimListMapper() {
        return (rs, rowNum) -> new ClaimListItemResponse(
            getUuid(rs, "id"),
            rs.getString("claim_number"),
            ClaimType.valueOf(rs.getString("claim_type")),
            ClaimStatus.valueOf(rs.getString("status")),
            getUuid(rs, "shipment_id"),
            rs.getString("shipment_number"),
            getUuid(rs, "creditor_id"),
            rs.getString("creditor_name"),
            getUuid(rs, "debtor_id"),
            rs.getString("debtor_name"),
            getUuid(rs, "assigned_lawyer_id"),
            rs.getBigDecimal("principal_debt"),
            rs.getBigDecimal("penalty_amount"),
            rs.getBigDecimal("total_amount"),
            getInteger(rs, "overdue_days"),
            getInteger(rs, "payment_days"),
            getOffsetDateTime(rs, "created_at"),
            getOffsetDateTime(rs, "updated_at")
        );
    }

    private RowMapper<ClaimDetailsResponse> claimDetailsMapper() {
        return (rs, rowNum) -> new ClaimDetailsResponse(
            getUuid(rs, "id"),
            getUuid(rs, "organization_id"),
            rs.getString("claim_number"),
            ClaimType.valueOf(rs.getString("claim_type")),
            ClaimStatus.valueOf(rs.getString("status")),
            rs.getString("reason"),
            getUuid(rs, "shipment_id"),
            rs.getString("shipment_number"),
            getUuid(rs, "contract_id"),
            rs.getString("contract_number"),
            getUuid(rs, "creditor_id"),
            rs.getString("creditor_name"),
            getUuid(rs, "debtor_id"),
            rs.getString("debtor_name"),
            rs.getBigDecimal("principal_debt"),
            rs.getBigDecimal("penalty_amount"),
            rs.getBigDecimal("total_amount"),
            rs.getBoolean("non_payment_confirmed"),
            getOffsetDateTime(rs, "non_payment_confirmed_at"),
            getUuid(rs, "non_payment_confirmed_by"),
            rs.getString("non_payment_confirmation_comment"),
            getUuid(rs, "assigned_lawyer_id"),
            getUuid(rs, "final_version_id"),
            getUuid(rs, "last_payment_check_id"),
            getOffsetDateTime(rs, "approved_at"),
            getUuid(rs, "approved_by"),
            getOffsetDateTime(rs, "sent_at"),
            getOffsetDateTime(rs, "paid_at"),
            getOffsetDateTime(rs, "cancelled_at"),
            rs.getString("cancellation_reason_code"),
            rs.getString("cancellation_reason"),
            getOffsetDateTime(rs, "escalated_at"),
            getOffsetDateTime(rs, "created_at"),
            getUuid(rs, "created_by"),
            getOffsetDateTime(rs, "updated_at"),
            getUuid(rs, "updated_by")
        );
    }

    private static UUID getUuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : (UUID) value;
    }

    private static Integer getInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static OffsetDateTime getOffsetDateTime(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class);
    }
}
