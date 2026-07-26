package ru.sber.cargotech.ai.claim.api;

import org.springframework.web.bind.annotation.*;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimResponse;
import ru.sber.cargotech.ai.claim.parser.ClaimResponseParser;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/claims")
public class ClaimParserTestController {

    private final ClaimResponseParser claimResponseParser;

    public ClaimParserTestController(ClaimResponseParser claimResponseParser) {
        this.claimResponseParser = claimResponseParser;
    }

    @PostMapping("/parser/test")
    public Map<String, Object> testParser(@RequestBody String rawModelResponse) {
        GenerateClaimResponse parsed = claimResponseParser.parse(rawModelResponse);

        return Map.of(
                "success", true,
                "parsed", parsed,
                "checkedAt", Instant.now().toString()
        );
    }
}