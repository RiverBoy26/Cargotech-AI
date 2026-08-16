package ru.sber.cargotech.ai.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.sber.cargotech.ai.rag.dto.DeleteContractRagRequest;
import ru.sber.cargotech.ai.rag.dto.ReplaceContractRagRequest;
import ru.sber.cargotech.ai.rag.dto.ReplaceContractRagResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/internal/api/ai/rag/contracts")
public class ContractRagInternalController {

    private final ContractRagLifecycleService lifecycleService;
    private final byte[] internalApiKey;

    public ContractRagInternalController(
        ContractRagLifecycleService lifecycleService,
        @Value("${ai.internal-api-key}") String internalApiKey
    ) {
        this.lifecycleService = lifecycleService;
        this.internalApiKey = internalApiKey.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/replace")
    public ReplaceContractRagResponse replace(
        @RequestHeader("X-Internal-Api-Key") String suppliedKey,
        @RequestBody ReplaceContractRagRequest request
    ) {
        validateKey(suppliedKey);
        return lifecycleService.replace(request);
    }

    @PostMapping("/delete")
    public Object delete(
        @RequestHeader("X-Internal-Api-Key") String suppliedKey,
        @RequestBody DeleteContractRagRequest request
    ) {
        validateKey(suppliedKey);
        return lifecycleService.delete(request);
    }

    private void validateKey(String suppliedKey) {
        byte[] supplied = suppliedKey == null ? new byte[0] : suppliedKey.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(internalApiKey, supplied)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid internal API key");
        }
    }
}
