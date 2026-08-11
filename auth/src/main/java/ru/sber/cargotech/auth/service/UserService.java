package ru.sber.cargotech.auth.service;

import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.auth.dto.ChangeUserRolesRequest;
import ru.sber.cargotech.auth.dto.CreateUserRequest;
import ru.sber.cargotech.auth.dto.PageResponse;
import ru.sber.cargotech.auth.dto.UpdateUserRequest;
import ru.sber.cargotech.auth.dto.UserResponse;
import ru.sber.cargotech.auth.entity.AuthRole;
import ru.sber.cargotech.auth.entity.AuthUser;
import ru.sber.cargotech.auth.exception.AuthException;
import ru.sber.cargotech.auth.repository.AuthOutboxWriter;
import ru.sber.cargotech.auth.repository.AuthUserRepository;
import ru.sber.cargotech.auth.repository.UserAccessRepository;
import ru.sber.cargotech.auth.security.CurrentUser;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private static final Set<String> ALLOWED_SORT_PROPERTIES = Set.of(
        "id",
        "firstName",
        "lastName",
        "middleName",
        "email",
        "active",
        "createdAt",
        "updatedAt",
        "lastLoginAt"
    );

    private final AuthUserRepository userRepository;
    private final UserAccessRepository accessRepository;
    private final AccessService accessService;
    private final UserAccessPolicy policy;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final AuthOutboxWriter outboxWriter;

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> findUsers(
        UUID organizationId,
        String search,
        Boolean active,
        String role,
        Pageable pageable,
        CurrentUser actor
    ) {
        log.debug("Поиск пользователей: actorUserId={}, actorOrganizationId={}, requestedOrganizationId={}, active={}, searchPresent={}, page={}, size={}", actor.userId(), actor.organizationId(), organizationId, active, search != null && !search.isBlank(), pageable.getPageNumber(), pageable.getPageSize());

        Pageable normalizedPageable = normalizePageable(pageable);

        UUID effectiveOrganization = resolveListOrganization(
            organizationId,
            actor
        );

        Set<UUID> roleUserIds = null;
        if (role != null && !role.isBlank()) {
            roleUserIds = accessRepository.findUserIdsByRole(
                role.trim().toUpperCase(Locale.ROOT),
                effectiveOrganization
            );
            if (roleUserIds.isEmpty()) {
                return PageResponse.from(
                    new PageImpl<>(List.of(), normalizedPageable, 0)
                );
            }
        }

        Set<UUID> finalRoleUserIds = roleUserIds;
        Specification<AuthUser> specification = (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (effectiveOrganization != null) {
                predicates.add(builder.equal(
                    root.get("organizationId"),
                    effectiveOrganization
                ));
            }
            if (active != null) {
                predicates.add(builder.equal(root.get("active"), active));
            }
            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.trim()
                    .toLowerCase(Locale.ROOT) + "%";
                predicates.add(builder.or(
                    builder.like(
                        builder.lower(root.get("firstName")),
                        pattern
                    ),
                    builder.like(
                        builder.lower(root.get("lastName")),
                        pattern
                    ),
                    builder.like(
                        builder.lower(root.get("middleName")),
                        pattern
                    ),
                    builder.like(
                        builder.lower(root.get("email")),
                        pattern
                    )
                ));
            }
            if (finalRoleUserIds != null) {
                predicates.add(root.get("id").in(finalRoleUserIds));
            }

            return builder.and(predicates.toArray(Predicate[]::new));
        };

        Page<UserResponse> page = userRepository
            .findAll(specification, normalizedPageable)
            .map(this::toResponse);
        return PageResponse.from(page);
    }

    @Transactional(readOnly = true)
    public UserResponse getUser(UUID userId, CurrentUser actor) {
        log.debug("Получение пользователя: targetUserId={}, actorUserId={}, actorOrganizationId={}", userId, actor.userId(), actor.organizationId());

        AuthUser user = requireUser(userId);
        Set<String> roles = accessService.roleCodes(userId);
        policy.checkTargetUserRead(actor, user);
        return toResponse(user, roles);
    }

    @Transactional
    public UserResponse createUser(
        CreateUserRequest request,
        CurrentUser actor
    ) {
        log.debug("Создание пользователя: actorUserId={}, targetOrganizationId={}, requestedRoles={}", actor.userId(), request.organizationId(), request.roles());

        UUID organizationId = policy.resolveTargetOrganization(
            actor,
            request.organizationId()
        );
        accessService.requireActiveOrganization(organizationId);

        Set<String> roleCodes = accessService.normalizeRoles(request.roles());
        policy.checkRoleAssignment(actor, roleCodes);
        List<AuthRole> roles = accessService.requireRoles(roleCodes);

        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw AuthException.conflict(
                "Пользователь с таким email уже существует"
            );
        }

        AuthUser user = new AuthUser();
        user.setOrganizationId(organizationId);
        user.setFirstName(normalizeRequiredName(request.firstName(), "Имя"));
        user.setLastName(normalizeRequiredName(request.lastName(), "Фамилия"));
        user.setMiddleName(blankToNull(request.middleName()));
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setActive(true);
        user = userRepository.saveAndFlush(user);

        accessRepository.replaceRoles(
            user.getId(),
            roles.stream().map(AuthRole::getId).toList(),
            actor.userId()
        );

        outboxWriter.write(
            "USER",
            user.getId(),
            "AUTH_USER_CREATED",
            organizationId,
            actor.userId(),
            Map.of("email", email, "roles", roleCodes)
        );

        return toResponse(user, roleCodes);
    }

    @Transactional
    public UserResponse updateUser(
        UUID userId,
        UpdateUserRequest request,
        CurrentUser actor
    ) {
        log.debug("Обновление пользователя: targetUserId={}, actorUserId={}, firstNameChanged={}, lastNameChanged={}, middleNameChanged={}, emailChanged={}", userId, actor.userId(), request.firstName() != null, request.lastName() != null, request.middleName() != null, request.email() != null);

        AuthUser user = requireUser(userId);
        Set<String> roles = accessService.roleCodes(userId);
        policy.checkTargetUser(actor, user, roles);

        Map<String, Object> changes = new LinkedHashMap<>();

        if (request.firstName() != null) {
            String firstName = normalizeRequiredName(request.firstName(), "Имя");
            user.setFirstName(firstName);
            changes.put("firstName", firstName);
        }

        if (request.lastName() != null) {
            String lastName = normalizeRequiredName(request.lastName(), "Фамилия");
            user.setLastName(lastName);
            changes.put("lastName", lastName);
        }

        if (request.middleName() != null) {
            String middleName = blankToNull(request.middleName());
            user.setMiddleName(middleName);
            changes.put("middleName", middleName);
        }

        if (request.email() != null) {
            String email = normalizeEmail(request.email());
            if (userRepository.existsByEmailIgnoreCaseAndIdNot(
                email,
                userId
            )) {
                throw AuthException.conflict(
                    "Пользователь с таким email уже существует"
                );
            }
            user.setEmail(email);
            changes.put("email", email);
        }

        user = userRepository.save(user);
        outboxWriter.write(
            "USER",
            userId,
            "AUTH_USER_UPDATED",
            user.getOrganizationId(),
            actor.userId(),
            changes
        );
        return toResponse(user, roles);
    }

    @Transactional
    public UserResponse changeRoles(
        UUID userId,
        ChangeUserRolesRequest request,
        CurrentUser actor
    ) {
        log.debug("Изменение ролей: targetUserId={}, actorUserId={}, requestedRoles={}", userId, actor.userId(), request.roles());

        policy.checkCanChangeRoles(actor, userId);

        AuthUser user = requireUser(userId);
        Set<String> currentRoles = accessService.roleCodes(userId);
        policy.checkTargetUser(actor, user, currentRoles);

        Set<String> newRoles = accessService.normalizeRoles(request.roles());
        policy.checkRoleAssignment(actor, newRoles);
        List<AuthRole> roleEntities = accessService.requireRoles(newRoles);

        accessRepository.replaceRoles(
            userId,
            roleEntities.stream().map(AuthRole::getId).toList(),
            actor.userId()
        );
        tokenService.revokeAll(userId);

        outboxWriter.write(
            "USER",
            userId,
            "AUTH_USER_ROLES_CHANGED",
            user.getOrganizationId(),
            actor.userId(),
            Map.of("oldRoles", currentRoles, "newRoles", newRoles)
        );
        return toResponse(user, newRoles);
    }

    @Transactional
    public UserResponse block(UUID userId, CurrentUser actor) {
        log.debug("Блокировка пользователя: targetUserId={}, actorUserId={}", userId, actor.userId());

        policy.checkCanBlock(actor, userId);

        AuthUser user = requireUser(userId);
        Set<String> roles = accessService.roleCodes(userId);
        policy.checkTargetUser(actor, user, roles);

        if (!user.isActive()) {
            throw AuthException.conflict("Пользователь уже заблокирован");
        }

        user.setActive(false);
        user.setBlockedAt(OffsetDateTime.now());
        user = userRepository.save(user);
        tokenService.revokeAll(userId);

        outboxWriter.write(
            "USER",
            userId,
            "AUTH_USER_BLOCKED",
            user.getOrganizationId(),
            actor.userId(),
            Map.of()
        );
        return toResponse(user, roles);
    }

    @Transactional
    public UserResponse unblock(UUID userId, CurrentUser actor) {
        log.debug("Разблокировка пользователя: targetUserId={}, actorUserId={}", userId, actor.userId());

        AuthUser user = requireUser(userId);
        Set<String> roles = accessService.roleCodes(userId);
        policy.checkTargetUser(actor, user, roles);

        if (user.isActive() && user.getBlockedAt() == null) {
            throw AuthException.conflict("Пользователь уже активен");
        }

        user.setActive(true);
        user.setBlockedAt(null);
        user = userRepository.save(user);

        outboxWriter.write(
            "USER",
            userId,
            "AUTH_USER_UNBLOCKED",
            user.getOrganizationId(),
            actor.userId(),
            Map.of()
        );
        return toResponse(user, roles);
    }

    private UUID resolveListOrganization(
        UUID requestedOrganizationId,
        CurrentUser actor
    ) {
        if (actor.hasRole("SUPER_ADMIN")) {
            return requestedOrganizationId;
        }

        if (requestedOrganizationId != null
            && !requestedOrganizationId.equals(actor.organizationId())) {
            throw AuthException.forbidden(
                "Нельзя просматривать пользователей другой организации"
            );
        }
        return actor.organizationId();
    }

    private Pageable normalizePageable(Pageable pageable) {
        List<Sort.Order> orders = new ArrayList<>();

        pageable.getSort().forEach(order -> {
            if ("fullName".equals(order.getProperty())) {
                orders.add(new Sort.Order(order.getDirection(), "lastName"));
                orders.add(new Sort.Order(order.getDirection(), "firstName"));
                return;
            }

            if (!ALLOWED_SORT_PROPERTIES.contains(order.getProperty())) {
                throw AuthException.validation(
                    "Недопустимое поле сортировки: " + order.getProperty()
                );
            }
            orders.add(order);
        });

        if (orders.isEmpty()) {
            orders.add(Sort.Order.asc("lastName"));
            orders.add(Sort.Order.asc("firstName"));
        }

        return PageRequest.of(
            pageable.getPageNumber(),
            pageable.getPageSize(),
            Sort.by(orders)
        );
    }

    private AuthUser requireUser(UUID userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> AuthException.notFound(
                "Пользователь %s не найден".formatted(userId)
            ));
    }

    private UserResponse toResponse(AuthUser user) {
        return toResponse(
            user,
            accessService.roleCodes(user.getId())
        );
    }

    private UserResponse toResponse(
        AuthUser user,
        Set<String> roles
    ) {
        return new UserResponse(
            user.getId(),
            user.getOrganizationId(),
            user.getFirstName(),
            user.getLastName(),
            user.getMiddleName(),
            user.getEmail(),
            user.isActive(),
            user.getBlockedAt(),
            user.getLastLoginAt(),
            Collections.unmodifiableSet(new TreeSet<>(roles)),
            user.getCreatedAt(),
            user.getUpdatedAt()
        );
    }

    private String normalizeEmail(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeRequiredName(String value, String fieldName) {
        String normalized = value.trim();
        if (normalized.isBlank()) {
            throw AuthException.validation(fieldName + " не может быть пустым");
        }
        return normalized;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
