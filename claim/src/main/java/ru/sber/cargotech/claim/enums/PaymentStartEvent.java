package ru.sber.cargotech.claim.enums;

public enum PaymentStartEvent {
    ACT_SIGNED,
    UNLOADING_DATE,
    TTN_SIGNED,
    INVOICE_DATE,
    REGISTRY_INCLUDED,
    DOCUMENT_PACKAGE_RECEIVED,

    /** Payment term starts from the later of act signing and document-package receipt. */
    LATEST_ACT_OR_DOCUMENT_PACKAGE,

    /** Act is the date anchor, but document-package receipt is an additional prerequisite. */
    ACT_SIGNED_REQUIRES_DOCUMENT_PACKAGE
}
