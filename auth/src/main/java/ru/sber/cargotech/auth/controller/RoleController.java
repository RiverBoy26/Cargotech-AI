package ru.sber.cargotech.auth.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequiredArgsConstructor
@Slf4j
public class RoleController {

    private final AccessService accessService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping
    @PreAuthorize("hasAuthority('USER_READ')")
    public List<RoleResponse> getAssignableRoles() {
        log.info("Вызов endpoint: getAssignableRoles");
        return accessService.assignableRoles(
            currentUserProvider.getRequiredUser()
        );
    }
}
