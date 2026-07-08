package ru.sber.cargotech.payment.controller;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.sber.cargotech.payment.dto.CreatePaymentMatchRequest;
import ru.sber.cargotech.payment.dto.PaymentDetailsResponse;
import ru.sber.cargotech.payment.dto.PaymentImportResponse;
import ru.sber.cargotech.payment.dto.PaymentMatchResponse;
import ru.sber.cargotech.payment.dto.PaymentResponse;
import ru.sber.cargotech.payment.dto.ReconciliationResponse;
import ru.sber.cargotech.payment.security.CurrentPaymentUserProvider;
import ru.sber.cargotech.payment.service.PaymentImportService;
import ru.sber.cargotech.payment.service.PaymentMatchingService;
import ru.sber.cargotech.payment.service.PaymentReconciliationService;
import ru.sber.cargotech.payment.service.PaymentService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentImportService importService;
    private final PaymentService paymentService;
    private final PaymentMatchingService matchingService;
    private final PaymentReconciliationService reconciliationService;
    private final CurrentPaymentUserProvider userProvider;

    public PaymentController(
        PaymentImportService importService,
        PaymentService paymentService,
        PaymentMatchingService matchingService,
        PaymentReconciliationService reconciliationService,
        CurrentPaymentUserProvider userProvider
    ) {
        this.importService = importService;
        this.paymentService = paymentService;
        this.matchingService = matchingService;
        this.reconciliationService = reconciliationService;
        this.userProvider = userProvider;
    }

    @PostMapping(
        value = "/import/1c",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @PreAuthorize("hasAuthority('PAYMENT_IMPORT')")
    public ResponseEntity<PaymentImportResponse> importFromOneC(
        @RequestPart("file") MultipartFile file
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
            importService.importFromOneC(
                file,
                userProvider.getRequiredUser()
            )
        );
    }

    @PostMapping(
        value = "/import/bank-statement",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @PreAuthorize("hasAuthority('PAYMENT_IMPORT')")
    public ResponseEntity<PaymentImportResponse> importBankStatement(
        @RequestPart("file") MultipartFile file
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
            importService.importBankStatement(
                file,
                userProvider.getRequiredUser()
            )
        );
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAYMENT_READ')")
    public Page<PaymentResponse> getPayments(
        @PageableDefault(
            size = 50,
            sort = "paymentDate",
            direction = Sort.Direction.DESC
        ) Pageable pageable
    ) {
        return paymentService.findAll(
            pageable,
            userProvider.getRequiredUser()
        );
    }

    @GetMapping("/{paymentId}")
    @PreAuthorize("hasAuthority('PAYMENT_READ')")
    public PaymentDetailsResponse getPayment(
        @PathVariable UUID paymentId
    ) {
        return paymentService.getDetails(
            paymentId,
            userProvider.getRequiredUser()
        );
    }

    @PostMapping("/reconcile")
    @PreAuthorize("hasAuthority('PAYMENT_RECONCILE')")
    public ReconciliationResponse reconcile() {
        return reconciliationService.reconcile(
            userProvider.getRequiredUser()
        );
    }

    @GetMapping("/reconciliations/{runId}")
    @PreAuthorize("hasAuthority('PAYMENT_RECONCILE')")
    public ReconciliationResponse getReconciliationRun(
        @PathVariable UUID runId
    ) {
        return reconciliationService.getRun(
            runId,
            userProvider.getRequiredUser()
        );
    }

    @PostMapping("/{paymentId}/matches")
    @PreAuthorize("hasAuthority('PAYMENT_UPDATE')")
    public PaymentMatchResponse matchPayment(
        @PathVariable UUID paymentId,
        @RequestBody @Valid CreatePaymentMatchRequest request
    ) {
        return matchingService.match(
            paymentId,
            request,
            userProvider.getRequiredUser()
        );
    }

    @DeleteMapping("/{paymentId}/matches/{matchId}")
    @PreAuthorize("hasAuthority('PAYMENT_UPDATE')")
    public PaymentDetailsResponse unmatchPayment(
        @PathVariable UUID paymentId,
        @PathVariable UUID matchId,
        @RequestParam String reason
    ) {
        return matchingService.unmatch(
            paymentId,
            matchId,
            reason,
            userProvider.getRequiredUser()
        );
    }
}
