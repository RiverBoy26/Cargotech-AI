package ru.sber.cargotech.claim.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record VersionDiffResponse(
    UUID baseVersionId,
    UUID versionId,
    List<String> addedLines,
    List<String> removedLines,
    List<String> changedLines,
    Map<String, List<String>> categories
) {
}
