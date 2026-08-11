package ru.sber.cargotech.document.crypto;

import org.springframework.stereotype.Service;
import ru.sber.cargotech.document.exception.DocumentException;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class DocumentTemplateCryptoService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final DocumentTemplateCryptoProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public DocumentTemplateCryptoService(
        DocumentTemplateCryptoProperties properties
    ) {
        this.properties = properties;
    }

    public String encrypt(String plainText) {
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(
                Cipher.ENCRYPT_MODE,
                secretKey(),
                new GCMParameterSpec(TAG_LENGTH_BITS, iv)
            );

            byte[] encrypted = cipher.doFinal(
                plainText.getBytes(StandardCharsets.UTF_8)
            );

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + encrypted.length);
            buffer.put(iv);
            buffer.put(encrypted);

            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (DocumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw DocumentException.unprocessable(
                "Не удалось зашифровать шаблон документа"
            );
        }
    }

    public String decrypt(String encryptedContent) {
        try {
            byte[] combined = Base64.getDecoder().decode(encryptedContent);

            if (combined.length <= IV_LENGTH_BYTES) {
                throw DocumentException.unprocessable(
                    "Зашифрованное содержимое шаблона повреждено"
                );
            }

            ByteBuffer buffer = ByteBuffer.wrap(combined);
            byte[] iv = new byte[IV_LENGTH_BYTES];
            buffer.get(iv);

            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                new GCMParameterSpec(TAG_LENGTH_BITS, iv)
            );

            return new String(
                cipher.doFinal(encrypted),
                StandardCharsets.UTF_8
            );
        } catch (DocumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw DocumentException.unprocessable(
                "Не удалось расшифровать шаблон документа. Проверьте DOCUMENT_TEMPLATE_ENCRYPTION_KEY_BASE64"
            );
        }
    }

    public String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (Exception exception) {
            throw DocumentException.unprocessable(
                "Не удалось вычислить hash шаблона"
            );
        }
    }

    public String keyId() {
        if (properties.encryptionKeyId() == null
            || properties.encryptionKeyId().isBlank()) {
            return "default";
        }
        return properties.encryptionKeyId();
    }

    private SecretKey secretKey() {
        String keyBase64 = properties.encryptionKeyBase64();

        if (keyBase64 == null || keyBase64.isBlank()) {
            throw DocumentException.unprocessable(
                "DOCUMENT_TEMPLATE_ENCRYPTION_KEY_BASE64 должен быть задан для работы с конфиденциальными шаблонами"
            );
        }

        byte[] key;
        try {
            key = Base64.getDecoder().decode(keyBase64.trim());
        } catch (IllegalArgumentException exception) {
            throw DocumentException.unprocessable(
                "DOCUMENT_TEMPLATE_ENCRYPTION_KEY_BASE64 должен быть корректной Base64-строкой"
            );
        }

        if (key.length != 32) {
            throw DocumentException.unprocessable(
                "Ключ шифрования шаблонов должен содержать 32 байта после Base64-декодирования"
            );
        }

        return new SecretKeySpec(key, KEY_ALGORITHM);
    }
}
