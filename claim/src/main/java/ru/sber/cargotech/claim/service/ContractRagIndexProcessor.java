package ru.sber.cargotech.claim.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.sber.cargotech.claim.client.ContractRagClient;
import ru.sber.cargotech.claim.client.DocumentTextClient;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class ContractRagIndexProcessor {

    private static final DateTimeFormatter CITATION_DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu");

    private final DocumentTextClient documentTextClient;
    private final ContractRagChunker chunker;
    private final ContractRagClient ragClient;
    private final ContractService contractService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void index(ContractRagIndexRequestedEvent event) {
        try {
            ContractService.ContractRagSnapshot contract = contractService.getRagSnapshot(
                event.organizationId(), event.contractId(), event.documentId()
            );
            DocumentTextClient.DocumentTextResponse document = documentTextClient.getText(event.organizationId(), event.documentId());
            List<ContractRagChunker.ContractSourceChunk> sourceChunks = chunker.chunk(document.text());
            if (sourceChunks.isEmpty()) {
                throw new IllegalStateException("Original contract has no meaningful text after PII filtering");
            }

            List<ContractRagClient.ContractChunk> chunks = new ArrayList<>();
            for (ContractRagChunker.ContractSourceChunk source : sourceChunks) {
                String chunkId = deterministicChunkId(contract, source);
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("source_kind", "original_contract");
                extra.put("chunk_index", source.chunkIndex());
                extra.put("part_index", source.partIndex());
                extra.put("source_page", source.sourcePage());
                extra.put("document_extraction_method", document.extractionMethod());
                if (source.clauseNumber() != null) extra.put("parent_clause_number", source.clauseNumber());

                chunks.add(new ContractRagClient.ContractChunk(
                    chunkId,
                    "CONTRACT_CONTEXT",
                    source.chunkType(),
                    source.claimType(),
                    contract.organizationId().toString(),
                    contract.clientId().toString(),
                    contract.contractId().toString(),
                    contract.contractNumber(),
                    contract.contractDate() == null ? null : contract.contractDate().toString(),
                    "EXPEDITOR_TO_CLIENT",
                    "CLIENT_CONTRACT",
                    contract.documentId().toString(),
                    sourceTitle(contract),
                    source.sectionTitle(),
                    source.sectionPath(),
                    source.clauseNumber(),
                    source.clauseTopic(),
                    source.text(),
                    citation(contract, source),
                    true,
                    extra
                ));
            }

            ragClient.replace(new ContractRagClient.ReplaceContractChunksRequest(
                contract.organizationId(),
                contract.clientId(),
                contract.contractId(),
                "contract:" + contract.contractId() + ":" + contract.documentId(),
                "CLAIM_SERVICE_CONTRACT_RAG",
                List.copyOf(chunks)
            ));
            contractService.markRagIndexed(event.organizationId(), event.contractId(), event.documentId());
            log.info(
                "Contract RAG indexed: organizationId={}, contractId={}, documentId={}, chunks={}",
                event.organizationId(), event.contractId(), event.documentId(), chunks.size()
            );
        } catch (Exception exception) {
            String safeError = "CONTRACT_RAG_INDEXING_FAILED:" + exception.getClass().getSimpleName();
            log.warn(
                "Contract RAG indexing failed: organizationId={}, contractId={}, documentId={}, errorType={}",
                event.organizationId(), event.contractId(), event.documentId(), exception.getClass().getSimpleName()
            );
            contractService.markRagFailed(
                event.organizationId(), event.contractId(), event.documentId(), safeError
            );
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void delete(ContractRagDeleteRequestedEvent event) {
        try {
            ragClient.delete(new ContractRagClient.DeleteContractChunksRequest(
                event.organizationId(), event.clientId(), event.contractId(), event.sourceDocumentId()
            ));
            log.info(
                "Contract RAG deleted: organizationId={}, contractId={}, sourceDocumentId={}",
                event.organizationId(), event.contractId(), event.sourceDocumentId()
            );
        } catch (Exception exception) {
            log.warn(
                "Contract RAG deletion failed: organizationId={}, contractId={}, sourceDocumentId={}, errorType={}",
                event.organizationId(), event.contractId(), event.sourceDocumentId(), exception.getClass().getSimpleName()
            );
        }
    }

    private String deterministicChunkId(
        ContractService.ContractRagSnapshot contract,
        ContractRagChunker.ContractSourceChunk source
    ) {
        return "contract:" + contract.contractId()
            + ":" + contract.documentId()
            + ":" + source.claimType()
            + ":" + source.chunkIndex()
            + ":" + source.partIndex();
    }

    private String sourceTitle(ContractService.ContractRagSnapshot contract) {
        String title = "Договор № " + contract.contractNumber();
        return contract.contractDate() == null ? title : title + " от " + CITATION_DATE.format(contract.contractDate());
    }

    private String citation(
        ContractService.ContractRagSnapshot contract,
        ContractRagChunker.ContractSourceChunk source
    ) {
        String contractTitle = sourceTitle(contract);
        if (source.clauseNumber() != null && !source.clauseNumber().isBlank()) {
            return "п. " + source.clauseNumber() + " " + contractTitle;
        }
        if (source.sectionTitle() != null && !source.sectionTitle().isBlank()) {
            return "раздел «" + source.sectionTitle() + "» " + contractTitle;
        }
        return contractTitle;
    }
}
