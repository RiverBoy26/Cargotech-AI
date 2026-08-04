package ru.sber.cargotech.document.storage;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import ru.sber.cargotech.document.enums.StorageProvider;
import ru.sber.cargotech.document.exception.DocumentException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class LocalDocumentStorageService {

    private final DocumentStorageProperties properties;
    private final Path rootPath;

    public LocalDocumentStorageService(DocumentStorageProperties properties) {
        this.properties = properties;
        this.rootPath = Path.of(properties.localRootPath()).toAbsolutePath().normalize();
    }

    public StoredFile storeMultipart(
        UUID organizationId,
        MultipartFile file
    ) {
        if (file == null || file.isEmpty()) {
            throw DocumentException.badRequest("Файл не должен быть пустым");
        }

        try (InputStream input = file.getInputStream()) {
            return store(
                organizationId,
                input,
                file.getOriginalFilename(),
                file.getContentType(),
                file.getSize()
            );
        } catch (IOException exception) {
            throw DocumentException.unprocessable("Не удалось прочитать загруженный файл");
        }
    }

    public StoredFile storeBytes(
        UUID organizationId,
        byte[] bytes,
        String filename,
        String contentType
    ) {
        if (bytes == null || bytes.length == 0) {
            throw DocumentException.badRequest("Содержимое файла не должно быть пустым");
        }

        return store(
            organizationId,
            new ByteArrayInputStream(bytes),
            filename,
            contentType,
            bytes.length
        );
    }

    public Resource load(String storageKey) {
        try {
            Path path = resolve(storageKey);
            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                throw DocumentException.notFound("Файл не найден в локальном хранилище");
            }
            return new FileSystemResource(path);
        } catch (DocumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw DocumentException.unprocessable("Не удалось прочитать файл из хранилища");
        }
    }

    public void deleteQuietly(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (Exception ignored) {
            // Метаданные остаются источником истины; физическое удаление best-effort.
        }
    }

    private StoredFile store(
        UUID organizationId,
        InputStream input,
        String originalFilename,
        String contentType,
        long sizeBytes
    ) {
        if (properties.provider() != null && properties.provider() != StorageProvider.LOCAL) {
            throw DocumentException.unprocessable(
                "В MVP реализовано только LOCAL-хранилище документов"
            );
        }

        String safeName = safeFilename(originalFilename);
        String storageKey = buildStorageKey(organizationId, safeName);
        Path destination = resolve(storageKey);

        try {
            Files.createDirectories(destination.getParent());
            Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);

            return new StoredFile(
                StorageProvider.LOCAL,
                properties.bucketName(),
                storageKey,
                safeName,
                contentType == null || contentType.isBlank()
                    ? "application/octet-stream"
                    : contentType,
                sizeBytes,
                sha256(destination)
            );
        } catch (IOException exception) {
            throw DocumentException.unprocessable("Не удалось сохранить файл документа");
        }
    }

    private String buildStorageKey(UUID organizationId, String filename) {
        LocalDate now = LocalDate.now();
        return organizationId + "/"
            + now.getYear() + "/"
            + "%02d".formatted(now.getMonthValue()) + "/"
            + UUID.randomUUID() + "_" + filename;
    }

    private Path resolve(String storageKey) {
        Path resolved = rootPath.resolve(storageKey).normalize();
        if (!resolved.startsWith(rootPath)) {
            throw DocumentException.badRequest("Некорректный путь файла");
        }
        return resolved;
    }

    private String safeFilename(String filename) {
        String value = filename == null || filename.isBlank()
            ? "document.bin"
            : filename;

        value = Path.of(value).getFileName().toString();
        value = value.replaceAll("[^a-zA-Zа-яА-Я0-9._-]", "_");

        if (value.isBlank()) {
            return "document.bin";
        }

        return value;
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new IOException("Checksum calculation failed", exception);
        }
    }
}
