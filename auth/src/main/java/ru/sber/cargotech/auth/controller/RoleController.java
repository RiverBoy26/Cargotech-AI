package ru.sber.cargotech.auth.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.sber.cargotech.auth.dto.RoleResponse;
import ru.sber.cargotech.auth.security.CurrentUserProvider;
import ru.sber.cargotech.auth.service.AccessService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/roles")
public class RoleController {

    private final AccessService accessService;
    private final CurrentUserProvider currentUserProvider;

    public RoleController(
        AccessService accessService,
        CurrentUserProvider currentUserProvider
    ) {
        this.accessService = accessService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('USER_READ')")
    public List<RoleResponse> getAssignableRoles() {
        return accessService.assignableRoles(
            currentUserProvider.getRequiredUser()
        );
    }
}
