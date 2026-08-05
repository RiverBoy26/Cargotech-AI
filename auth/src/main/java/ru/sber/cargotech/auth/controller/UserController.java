package ru.sber.cargotech.auth.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.sber.cargotech.auth.dto.ChangeUserRolesRequest;
import ru.sber.cargotech.auth.dto.CreateUserRequest;
import ru.sber.cargotech.auth.dto.PageResponse;
import ru.sber.cargotech.auth.dto.UpdateUserRequest;
import ru.sber.cargotech.auth.dto.UserResponse;
import ru.sber.cargotech.auth.security.CurrentUserProvider;
import ru.sber.cargotech.auth.service.UserService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Slf4j
public class UserController {

    private final UserService userService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping
    @PreAuthorize("hasAuthority('USER_READ')")
    public PageResponse<UserResponse> findUsers(
        @RequestParam(required = false) UUID organizationId,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) Boolean active,
        @RequestParam(required = false) String role,
        @PageableDefault(
            size = 50,
            sort = "lastName",
            direction = Sort.Direction.ASC
        ) Pageable pageable
    ) {
        log.info("Вызов endpoint: findUsers");
        return userService.findUsers(
            organizationId,
            search,
            active,
            role,
            pageable,
            currentUserProvider.getRequiredUser()
        );
    }

    @GetMapping("/{userId}")
    @PreAuthorize("hasAuthority('USER_READ')")
    public UserResponse getUser(@PathVariable UUID userId) {
        log.info("Вызов endpoint: getUser");
        return userService.getUser(
            userId,
            currentUserProvider.getRequiredUser()
        );
    }

    @PostMapping
    @PreAuthorize("hasAuthority('USER_CREATE')")
    public UserResponse createUser(
        @RequestBody @Valid CreateUserRequest request
    ) {
        log.info("Вызов endpoint: createUser");
        return userService.createUser(
            request,
            currentUserProvider.getRequiredUser()
        );
    }

    @PatchMapping("/{userId}")
    @PreAuthorize("hasAuthority('USER_UPDATE')")
    public UserResponse updateUser(
        @PathVariable UUID userId,
        @RequestBody @Valid UpdateUserRequest request
    ) {
        log.info("Вызов endpoint: updateUser");
        return userService.updateUser(
            userId,
            request,
            currentUserProvider.getRequiredUser()
        );
    }

    @PatchMapping("/{userId}/roles")
    @PreAuthorize("hasAuthority('USER_ROLE_ASSIGN')")
    public UserResponse changeRoles(
        @PathVariable UUID userId,
        @RequestBody @Valid ChangeUserRolesRequest request
    ) {
        log.info("Вызов endpoint: changeRoles");
        return userService.changeRoles(
            userId,
            request,
            currentUserProvider.getRequiredUser()
        );
    }

    @PostMapping("/{userId}/block")
    @PreAuthorize("hasAuthority('USER_UPDATE')")
    public UserResponse block(@PathVariable UUID userId) {
        log.info("Вызов endpoint: block");
        return userService.block(
            userId,
            currentUserProvider.getRequiredUser()
        );
    }

    @PostMapping("/{userId}/unblock")
    @PreAuthorize("hasAuthority('USER_UPDATE')")
    public UserResponse unblock(@PathVariable UUID userId) {
        log.info("Вызов endpoint: unblock");
        return userService.unblock(
            userId,
            currentUserProvider.getRequiredUser()
        );
    }
}
