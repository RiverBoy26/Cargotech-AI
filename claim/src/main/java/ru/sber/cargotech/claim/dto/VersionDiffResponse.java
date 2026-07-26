package ru.sber.cargotech.claim.dto;

import java.util.List;
import java.util.UUID;

public record VersionDiffResponse(
    UUID baseVersionId,
    UUID versionId,
    List<String> addedLines,
    List<String> removedLines
) {
}
