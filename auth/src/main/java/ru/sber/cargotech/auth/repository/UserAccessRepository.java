package ru.sber.cargotech.auth.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Repository
public class UserAccessRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public UserAccessRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Set<String> findRoleCodes(UUID userId) {
        return new TreeSet<>(jdbc.queryForList(
            """
            select role.code
              from cargotech.auth_user_roles user_role
              join cargotech.auth_roles role on role.id = user_role.role_id
             where user_role.user_id = :userId
             order by role.code
            """,
            Map.of("userId", userId),
            String.class
        ));
    }

    public Set<String> findPermissionCodes(UUID userId) {
        return new TreeSet<>(jdbc.queryForList(
            """
            select distinct permission.code
              from cargotech.auth_user_roles user_role
              join cargotech.auth_role_permissions role_permission
                on role_permission.role_id = user_role.role_id
              join cargotech.auth_permissions permission
                on permission.id = role_permission.permission_id
             where user_role.user_id = :userId
             order by permission.code
            """,
            Map.of("userId", userId),
            String.class
        ));
    }

    public Set<UUID> findUserIdsByRole(
        String roleCode,
        UUID organizationId
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("roleCode", roleCode)
            .addValue("organizationId", organizationId, Types.OTHER);

        return new LinkedHashSet<>(jdbc.query(
            """
            select user_role.user_id
              from cargotech.auth_user_roles user_role
              join cargotech.auth_roles role on role.id = user_role.role_id
              join cargotech.auth_users app_user on app_user.id = user_role.user_id
             where role.code = :roleCode
               and (:organizationId is null or app_user.organization_id = :organizationId)
            """,
            parameters,
            (resultSet, rowNum) -> resultSet.getObject("user_id", UUID.class)
        ));
    }

    public void replaceRoles(
        UUID userId,
        Collection<UUID> roleIds,
        UUID assignedBy
    ) {
        jdbc.update(
            "delete from cargotech.auth_user_roles where user_id = :userId",
            Map.of("userId", userId)
        );

        if (roleIds.isEmpty()) {
            return;
        }

        OffsetDateTime assignedAt = OffsetDateTime.now();
        SqlParameterSource[] batch = roleIds.stream()
            .map(roleId -> new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("roleId", roleId)
                .addValue("assignedAt", assignedAt)
                .addValue("assignedBy", assignedBy, Types.OTHER))
            .toArray(SqlParameterSource[]::new);

        jdbc.batchUpdate(
            """
            insert into cargotech.auth_user_roles(
                user_id,
                role_id,
                assigned_at,
                assigned_by
            ) values (
                :userId,
                :roleId,
                :assignedAt,
                :assignedBy
            )
            """,
            batch
        );
    }
}
