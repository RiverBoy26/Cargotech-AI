let currentClaim = null;
let currentVersions = [];
let currentDocuments = [];
let selectedDocumentId = null;
let currentClaimContext = {};
let currentSendChecklist = null;
let templatePreviewRequestId = 0;
let documentGenerationInProgress = false;

function finalClaimVersion() {
  return currentVersions.find((item) => item.id === currentClaim?.finalVersionId) || null;
}

function documentClaimVersionNumber(documentItem) {
  const storedVersion = documentItem.description?.match(/Версия претензии:\s*(\d+)/i)?.[1];
  if (storedVersion) return Number(storedVersion);

  const documentCreatedAt = new Date(documentItem.createdAt || 0).getTime();
  return [...currentVersions]
    .filter((version) => new Date(version.createdAt || 0).getTime() <= documentCreatedAt)
    .sort((left, right) => new Date(right.createdAt || 0) - new Date(left.createdAt || 0))[0]
    ?.versionNumber || null;
}

function selectedClaimDocumentFormat() {
  return document.getElementById('document_format')?.value || 'CLAIM_PDF';
}

function claimDocumentFormatLabel(documentType) {
  return documentType === 'CLAIM_PDF' ? 'PDF' : 'DOCX';
}

function finalVersionDocumentExists(outputType = selectedClaimDocumentFormat()) {
  const finalVersion = finalClaimVersion();
  if (!finalVersion) return false;
  return currentDocuments.some((item) =>
    item.documentType === outputType
      && documentClaimVersionNumber(item) === finalVersion.versionNumber
  );
}

const LAST_OPENED_CLAIM_VERSION_STORAGE_PREFIX = 'cargotech.claim.lastOpenedVersion.';

function requestClaimActionText({ title, label, value = '', required = false }) {
  const dialog = document.getElementById('claim_action_dialog');
  const form = document.getElementById('claim_action_dialog_form');
  const input = document.getElementById('claim_action_dialog_text');
  const error = document.getElementById('claim_action_dialog_error');
  document.getElementById('claim_action_dialog_title').textContent = title;
  document.getElementById('claim_action_dialog_label').textContent = label;
  input.value = value;
  error.textContent = '';

  return new Promise((resolve) => {
    const finish = (result) => {
      form.removeEventListener('submit', submit);
      dialog.removeEventListener('cancel', cancel);
      document.getElementById('claim_action_dialog_cancel').removeEventListener('click', cancel);
      if (dialog.open) dialog.close();
      resolve(result);
    };
    const submit = (event) => {
      event.preventDefault();
      const result = input.value.trim();
      if (required && !result) {
        error.textContent = 'Заполните обязательное поле';
        input.focus();
        return;
      }
      finish(result);
    };
    const cancel = (event) => {
      event.preventDefault();
      finish(null);
    };
    form.addEventListener('submit', submit);
    dialog.addEventListener('cancel', cancel);
    document.getElementById('claim_action_dialog_cancel').addEventListener('click', cancel);
    dialog.showModal();
    input.focus();
    input.setSelectionRange(input.value.length, input.value.length);
  });
}

function requestClaimEdit() {
  const dialog = document.getElementById('claim_edit_dialog');
  const form = document.getElementById('claim_edit_dialog_form');
  const cancelButton = document.getElementById('claim_edit_dialog_cancel');
  const error = document.getElementById('claim_edit_dialog_error');
  const finalVersion = currentVersions.find((item) => item.id === currentClaim?.finalVersionId);
  const fields = {
    claimNumber: 'edit_claim_number',
    reason: 'edit_reason',
    recipientName: 'edit_recipient_name',
    recipientEmail: 'edit_recipient_email',
    recipientAddress: 'edit_recipient_address',
    bankDetails: 'edit_bank_details',
    responseDeadlineDays: 'edit_response_deadline_days',
    signerFullName: 'edit_signer_full_name',
    signerPosition: 'edit_signer_position',
    signerAuthority: 'edit_signer_authority',
  };
  Object.entries(fields).forEach(([name, id]) => {
    document.getElementById(id).value = currentClaim?.[name] ?? '';
  });
  document.getElementById('edit_principal_debt').value = formatMoney(currentClaim?.principalDebt);
  document.getElementById('edit_penalty_amount').value = formatMoney(currentClaim?.penaltyAmount);
  document.getElementById('edit_claim_text').value =
    finalVersion?.content || document.getElementById('claim_text_editor').value || '';
  error.textContent = '';

  return new Promise((resolve) => {
    const finish = (result) => {
      form.removeEventListener('submit', submit);
      dialog.removeEventListener('cancel', cancel);
      cancelButton.removeEventListener('click', cancel);
      if (dialog.open) dialog.close();
      resolve(result);
    };
    const submit = (event) => {
      event.preventDefault();
      if (!form.reportValidity()) return;
      const payload = Object.fromEntries(Object.entries(fields).map(([name, id]) => {
        const value = document.getElementById(id).value.trim();
        return [name, name === 'responseDeadlineDays' && value ? Number(value) : (value || null)];
      }));
      payload.text = document.getElementById('edit_claim_text').value.trim() || null;
      if (!payload.claimNumber || !payload.reason) {
        error.textContent = 'Номер и основание претензии обязательны';
        return;
      }
      finish(payload);
    };
    const cancel = (event) => {
      event?.preventDefault();
      finish(null);
    };
    form.addEventListener('submit', submit);
    dialog.addEventListener('cancel', cancel);
    cancelButton.addEventListener('click', cancel);
    dialog.showModal();
    dialog.addEventListener('click', (event) => {
        if (event.target === dialog) {
            cancel(event);
        }
    });
    document.getElementById('edit_claim_number').focus();
  });
}

function getLastOpenedClaimVersionId(claimId) {
  try {
    return localStorage.getItem(`${LAST_OPENED_CLAIM_VERSION_STORAGE_PREFIX}${claimId}`);
  } catch (error) {
    console.warn('Не удалось прочитать последнюю открытую версию претензии', error);
    return null;
  }
}

function rememberOpenedClaimVersion(claimId, versionId) {
  if (!claimId || !versionId) return;
  try {
    localStorage.setItem(
      `${LAST_OPENED_CLAIM_VERSION_STORAGE_PREFIX}${claimId}`,
      versionId
    );
  } catch (error) {
    console.warn('Не удалось сохранить последнюю открытую версию претензии', error);
  }
}

const CLAIM_TEXT_LOCKED_STATUSES = new Set([
  'SENT',
  'AWAITING_RESPONSE',
  'PAID',
  'ESCALATED_TO_COURT',
  'CANCELLED',
  'CANCELLED_PAID',
  'CLOSED_IN_COURT',
]);

function setText(id, value) {
  const element = document.getElementById(id);
  if (element) element.textContent = value ?? '—';
}

function escapeHtml(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}

function setButtonState(id, visible, enabled = true) {
  const button = document.getElementById(id);
  if (!button) return;
  button.hidden = !visible;
  button.disabled = !enabled;
  button.classList.toggle('action_btn_disabled', !enabled);
}

function isClaimTextLocked(status = currentClaim?.status) {
  return CLAIM_TEXT_LOCKED_STATUSES.has(status);
}

function updateClaimTextPanelState() {
  const locked = isClaimTextLocked();
  const panel = document.getElementById('claim_text_panel');
  const editor = document.getElementById('claim_text_editor');
  const previewButton = document.getElementById('btn_template_preview');

  if (panel) {
    panel.classList.toggle('workflow_panel_locked', locked);
    panel.setAttribute('aria-disabled', String(locked));
    panel.title = locked
      ? 'Текст претензии недоступен для изменения после отправки'
      : '';
  }

  ['template_select', 'version_select', 'btn_template_preview'].forEach((id) => {
    const control = document.getElementById(id);
    if (control) control.disabled = locked;
  });

  if (previewButton) {
    previewButton.classList.toggle('action_btn_disabled', locked);
  }
  if (editor) {
    editor.readOnly = locked;
    editor.setAttribute('aria-readonly', String(locked));
  }
}

function setRecipientEmail(email) {
  const input = document.getElementById('email_to');
  const display = document.getElementById('email_to_display');
  const editButton = document.getElementById('btn_edit_recipient_email');
  if (!input || !display || !editButton) return;

  input.value = String(email || '').trim();
  input.hidden = true;
  display.textContent = input.value || 'Email клиента не указан';
  display.hidden = false;
  editButton.textContent = 'Изменить';
  editButton.dataset.editing = 'false';
}

function formatDate(value) {
  if (!value) return '—';
  const datePart = String(value).slice(0, 10);
  const [year, month, day] = datePart.split('-');
  return year && month && day ? `${day}.${month}.${year}` : value;
}

function renderHistoryItem(item) {
  const date = item.changedAt || item.createdAt || '';
  const title = item.newStatus
    ? `${item.previousStatus || '—'} → ${item.newStatus}`
    : (item.text || 'Комментарий');
  const subtitle = item.newStatus ? (item.reason || '') : '';
  const actor = item.changedByLabel || item.authorName || item.changedBy || item.authorId || '—';
  return `
    <div class="history_item">
      <div class="history_item_title">${escapeHtml(title)}</div>
      <div class="history_item_meta">${escapeHtml(date)} · ${escapeHtml(actor)}${subtitle ? ` — ${escapeHtml(subtitle)}` : ''}</div>
    </div>`;
}

async function loadHistory(claimId) {
  const list = document.getElementById('history_list');
  list.textContent = 'Загрузка...';
  try {
    const [statusHistory, comments] = await Promise.all([
      getClaimStatusHistory(claimId),
      getClaimComments(claimId),
    ]);
    const items = [...(statusHistory || []), ...(comments || [])]
      .sort((a, b) => String(a.changedAt || a.createdAt).localeCompare(String(b.changedAt || b.createdAt)));
    list.innerHTML = items.length
      ? items.map(renderHistoryItem).join('')
      : '<div class="history_item">История пуста</div>';
  } catch (error) {
    list.textContent = `Ошибка: ${error.message}`;
  }
}

function updateAvailableActions() {
  const status = currentClaim?.status;
  const editable = ['DRAFT', 'PENDING_LEGAL_REVIEW', 'LEGAL_APPROVED'].includes(status);
  const approved = Boolean(currentClaim?.approvedAt)
    || ['LEGAL_APPROVED', 'SENT', 'AWAITING_RESPONSE', 'PAID', 'ESCALATED_TO_COURT', 'CLOSED_IN_COURT']
      .includes(status);
  const documentDeliveryPanel = document.getElementById('document_delivery_panel');
  if (documentDeliveryPanel) documentDeliveryPanel.hidden = !approved;
  setButtonState(
    'btn_compose',
    ['DRAFT', 'PENDING_LEGAL_REVIEW'].includes(status)
      && Boolean(currentClaim?.nonPaymentConfirmed)
      && hasPermission('CLAIM_UPDATE')
  );
  setButtonState('btn_recalculate', editable && hasPermission('CALCULATION_GENERATE'));
  setButtonState('btn_edit', editable && hasPermission('CLAIM_UPDATE'));
  setButtonState(
    'btn_request_confirmation',
    status === 'DRAFT' && !currentClaim?.nonPaymentConfirmed && hasPermission('CLAIM_UPDATE'),
    !currentClaim?.nonPaymentConfirmationRequestedAt
  );
  setButtonState(
    'btn_approve',
    (status === 'PENDING_LEGAL_REVIEW'
      || (status === 'DRAFT' && Boolean(currentClaim?.nonPaymentConfirmed)))
      && hasPermission('CLAIM_UPDATE'),
    Boolean(currentClaim?.finalVersionId)
  );
  setButtonState(
    'btn_cancel',
    !['PAID', 'CANCELLED', 'CANCELLED_PAID', 'CLOSED_IN_COURT'].includes(status) && hasPermission('CLAIM_UPDATE')
  );
  setButtonState(
    'btn_court_package',
    ['SENT', 'AWAITING_RESPONSE'].includes(status) && hasPermission('CLAIM_UPDATE')
  );
  setButtonState(
    'btn_delete_claim',
    ['DRAFT', 'PAID', 'CANCELLED'].includes(status) && hasPermission('CLAIM_DELETE')
  );
  const canGenerateDocument = status === 'LEGAL_APPROVED' && hasPermission('DOCUMENT_GENERATE');
  setButtonState(
    'btn_generate_document',
    canGenerateDocument,
    Boolean(currentClaim?.finalVersionId)
      && !finalVersionDocumentExists()
      && !documentGenerationInProgress
  );
  const generateButton = document.getElementById('btn_generate_document');
  if (generateButton) {
    generateButton.title = finalVersionDocumentExists()
      ? `Документ ${claimDocumentFormatLabel(selectedClaimDocumentFormat())} для текущей финальной версии уже сформирован.`
      : '';
  }
  setButtonState(
    'btn_download_claim',
    approved && hasPermission('DOCUMENT_DOWNLOAD'),
    Boolean(selectedDocumentId)
  );
  setButtonState(
    'btn_send_document',
    status === 'LEGAL_APPROVED' && hasPermission('DOCUMENT_SEND'),
    Boolean(selectedDocumentId) && Boolean(currentSendChecklist?.readyToSend)
  );
  setButtonState(
    'btn_validation_override',
    editable && hasPermission('CLAIM_UPDATE')
      && ['FAILED', 'PENDING'].includes(currentClaim?.documentValidationStatus)
  );
  setButtonState('btn_save_version', editable && hasPermission('CLAIM_UPDATE'));
  setButtonState('btn_mark_final', editable && hasPermission('CLAIM_UPDATE'));
  updateClaimTextPanelState();
}

function fillClaimCard(claim) {
  currentClaim = claim;
  const status = mapStatus(claim.status);
  setText('claim_card_title', `Претензия ${claim.claimNumber || claim.id}`);
  setText('claim_card_subtitle', `${claim.creditorName || '—'} — ${claim.shipmentNumber || '—'}`);
  setText('claim_card_overdue_badge', `Дней просрочки ${claim.overdueDays ?? '—'}`);
  setText('claim_card_status_badge', status.text);
  setText('info_debt_amount', formatMoney(claim.principalDebt));
  setText('info_penalty', formatMoney(claim.penaltyAmount));
  setText('info_payment_term', '—');
  setText('info_completion_date', '—');
  setText('info_overdue_date', '—');
  setText('info_lawyer_name', '—');
  const validationLabels = {
    PENDING: 'Ожидает проверки',
    PASSED: 'Проверка пройдена',
    FAILED: 'Найдены ошибки',
    OVERRIDDEN: 'Подтверждено юристом',
  };
  setText('validation_status', validationLabels[claim.documentValidationStatus] || 'Ожидает проверки');
  setText(
    'validation_errors',
    claim.documentValidationErrors
      || (claim.manualReviewRequired ? claim.manualReviewReason : 'Ошибок нет')
  );
  renderUsedSources(claim, null);
  const stripe = document.getElementById('claim_card_stripe');
  if (stripe) stripe.className = `claim_card_stripe ${status.className}`;
  updateAvailableActions();
}

async function loadClaimContext(claim) {
  const lawyerId = claim.assignedLawyerId || claim.createdBy;
  const requests = await Promise.allSettled([
    getContract(claim.contractId),
    getShipment(claim.shipmentId),
    getClaimCalculation(claim.id),
    getParty(claim.debtorId),
    lawyerId ? getUser(lawyerId) : Promise.resolve(null),
  ]);

  const valueOrNull = (result) => result.status === 'fulfilled' ? result.value : null;
  const contract = valueOrNull(requests[0]);
  const shipment = valueOrNull(requests[1]);
  const calculation = valueOrNull(requests[2]);
  const debtor = valueOrNull(requests[3]);
  const assignedLawyer = valueOrNull(requests[4]);
  const storedUser = getStoredUser() || {};

  currentClaimContext = {
    contract,
    shipment,
    calculation,
    debtor,
    assignedLawyer,
  };

  renderUsedSources(claim, contract);

  setText(
    'info_payment_term',
    contract?.paymentDays != null ? `${contract.paymentDays} дней` : '—'
  );
  setText(
    'info_completion_date',
    formatDate(shipment?.actSignedAt || shipment?.unloadingDate)
  );
  setText('info_overdue_date', formatDate(calculation?.overdueStartDate));
  setText(
    'info_lawyer_name',
    (assignedLawyer ? formatUserFullName(assignedLawyer) : null)
      || (lawyerId === storedUser.userId ? formatUserFullName(storedUser) : null)
      || '—'
  );

  if (calculation?.overdueDays != null) {
    setText('claim_card_overdue_badge', `Дней просрочки ${calculation.overdueDays}`);
  }

  setRecipientEmail(debtor?.email);
}

function renderUsedSources(claim, contract) {
  const contractSource = document.getElementById('contract_source');
  const contractTitle = document.getElementById('contract_source_title');
  const contractMeta = document.getElementById('contract_source_meta');
  const contractDownload = document.getElementById('btn_download_contract_source');
  const sourcesText = document.getElementById('used_sources');
  const sourcesEmpty = document.getElementById('used_sources_empty');
  const hasContractDocument = Boolean(contract?.documentId);
  const rawSources = claim?.usedSources?.trim() || '';

  if (contractSource) contractSource.hidden = !hasContractDocument;
  if (hasContractDocument) {
    contractTitle.textContent = contract.number ? `Договор № ${contract.number}` : 'Договор без номера';
    contractMeta.textContent = contract.signedAt
      ? `Дата договора: ${formatDate(contract.signedAt)}`
      : 'Дата договора не указана';
    contractDownload.dataset.documentId = contract.documentId;
    contractDownload.hidden = !hasPermission('DOCUMENT_DOWNLOAD');
  } else if (contractDownload) {
    delete contractDownload.dataset.documentId;
    contractDownload.hidden = true;
  }

  if (sourcesText) {
    if (rawSources) {
      try {
        sourcesText.textContent = JSON.stringify(JSON.parse(rawSources), null, 2);
      } catch (_) {
        sourcesText.textContent = rawSources;
      }
    } else {
      sourcesText.textContent = '';
    }
    sourcesText.hidden = !rawSources;
  }
  if (sourcesEmpty) sourcesEmpty.hidden = hasContractDocument || Boolean(rawSources);
}

async function loadVersions(claimId) {
  const select = document.getElementById('version_select');
  try {
    currentVersions = await getClaimVersions(claimId);
  } catch (error) {
    currentVersions = [];
    select.innerHTML = '<option value="">Версии временно недоступны</option>';
    document.getElementById('ai_warning').textContent = error.message;
    return;
  }
  select.innerHTML = '<option value="">— версии текста —</option>' +
    currentVersions.map((version) =>
      `<option value="${version.id}">v${version.versionNumber} · ${escapeHtml(version.source)}${version.finalVersion ? ' · финальная' : ''}</option>`
    ).join('');
  const lastOpenedVersionId = getLastOpenedClaimVersionId(claimId);
  const selected = currentVersions.find((item) => item.id === currentClaim?.finalVersionId)
    || currentVersions.find((item) => item.id === lastOpenedVersionId)
    || currentVersions.at(-1);
  if (selected) {
    select.value = selected.id;
    document.getElementById('claim_text_editor').value = selected.content || '';
    rememberOpenedClaimVersion(claimId, selected.id);
    await renderVersionDiff(claimId, selected.id);
  }
}

async function renderVersionDiff(claimId, versionId) {
  const container = document.getElementById('version_diff');
  if (!versionId) {
    container.textContent = 'Выберите версию для сравнения.';
    return;
  }
  try {
    const diff = await getClaimVersionDiff(claimId, versionId);
    const groups = [
      ['Добавлено', diff.addedLines, 'diff_added'],
      ['Удалено', diff.removedLines, 'diff_removed'],
      ['Изменено', diff.changedLines, 'diff_changed'],
    ];
    const totalChanges = groups.reduce((sum, [, lines]) => sum + (lines || []).length, 0);
    const importantChanges = groups
      .flatMap(([title, lines]) => (lines || []).map((line) => `${title}: ${line}`))
      .slice(0, 3);
    const fullDiffHtml = groups.map(([title, lines, className]) => `
      <section class="${className}"><strong>${title}: ${(lines || []).length}</strong>
        ${(lines || []).length
          ? `<ul>${lines.map((line) => `<li>${escapeHtml(line)}</li>`).join('')}</ul>`
          : '<div class="version_diff_section_empty">Нет изменений</div>'}
      </section>`).join('');
    container.innerHTML = `
      <div class="version_diff_summary">
        <div class="version_diff_summary_title">Изменений: ${totalChanges}</div>
        <div class="version_diff_counters">
          ${groups.map(([title, lines, className]) => `
            <span class="version_diff_counter ${className}">${title}: ${(lines || []).length}</span>`).join('')}
        </div>
        ${importantChanges.length ? `
          <ul class="version_diff_preview">
            ${importantChanges.map((line) => `<li>${escapeHtml(line)}</li>`).join('')}
          </ul>` : '<div class="version_diff_empty">Версии не отличаются</div>'}
        ${totalChanges ? `
          <details class="version_diff_details">
            <summary>Показать все изменения</summary>
            <div class="version_diff_full">${fullDiffHtml}</div>
          </details>` : ''}
      </div>
    `;
  } catch (error) {
    container.textContent = `Сравнение недоступно: ${error.message}`;
  }
}

const CHECK_ORDER = [
    
    'partyDetails',
    'paymentConfirmed',
    'debt',
    
    'penaltyCalculation',
    'contractReferences',
    
    'finalVersion',
    'legalBasis',
    'attachments',
    'validation',
];

const CHECK_LABELS = {
  debt: 'Есть непогашенный долг',
  penaltyCalculation: 'Неустойка рассчитана',
  partyDetails: 'Реквизиты сторон заполнены',
  contractReferences: 'Договор и пункты указаны',
  legalBasis: 'Правовое основание проверено',
  attachments: 'Сформирован документ претензии с расчетами',
  paymentConfirmed: 'Бухгалтер подтвердил неуплату',
  finalVersion: 'Финальная версия назначена',
};

async function loadSendChecklist(claimId) {
  const list = document.getElementById('send_checklist');
  const HIDDEN_CHECKS = ['validation'];
  try {
    currentSendChecklist = await getClaimSendChecklist(claimId);
    list.innerHTML = CHECK_ORDER.filter(name => name in (currentSendChecklist.checks || {})).map(name => [name, currentSendChecklist.checks[name]])
      .filter(([name]) => !HIDDEN_CHECKS.includes(name))
      .map(([name, passed]) =>
        `<li class="${passed ? 'check_passed' : 'check_failed'}">${passed ? '✓' : '✕'} ${escapeHtml(CHECK_LABELS[name] || name)}</li>`
      ).join('');
  } catch (error) {
    currentSendChecklist = null;
    list.innerHTML = `<li class="check_failed">Ошибка проверки: ${escapeHtml(error.message)}</li>`;
  }
  updateAvailableActions();
}

async function loadTemplates(claimId) {
  const select = document.getElementById('template_select');
  let templates;
  try {
    templates = await getAvailableClaimTemplates(
      currentClaim.claimType,
      currentClaim.debtorId
    );
  } catch (error) {
    select.innerHTML = '<option value="">Шаблоны временно недоступны</option>';
    document.getElementById('delivery_status').textContent =
      `Document-service недоступен: ${error.message}`;
    return;
  }
  select.innerHTML = '<option value="">— выберите шаблон —</option>' +
    templates.map((template) =>
      `<option value="${template.templateId}" data-version-id="${template.activeVersionId}">${escapeHtml(template.name)}${template.recommended ? ' · рекомендован' : ''}</option>`
    ).join('');
  select.value = '';
  updateAvailableActions();
}

async function loadDocuments(claimId) {
  const list = document.getElementById('document_list');
  let page;
  try {
    page = await getClaimDocuments(claimId);
  } catch (error) {
    currentDocuments = [];
    selectedDocumentId = null;
    list.textContent = `Документы временно недоступны: ${error.message}`;
    updateAvailableActions();
    return;
  }
  currentDocuments = page.content || [];
  const displayedDocuments = currentDocuments
    .filter((item) => item.documentType !== 'CALCULATION_APPENDIX')
    .sort((left, right) => new Date(right.createdAt || 0) - new Date(left.createdAt || 0));
  if (!displayedDocuments.length) {
    selectedDocumentId = null;
    list.textContent = 'Документы ещё не сформированы';
  } else {
    const claimDocuments = displayedDocuments.filter((item) =>
      ['CLAIM_PDF', 'CLAIM_DOCX'].includes(item.documentType)
    );
    selectedDocumentId = claimDocuments[0]?.id || null;
    const typeLabels = {
      CLAIM_PDF: 'Претензия',
      CLAIM_DOCX: 'Претензия',
      CALCULATION_PDF: 'Расчёт задолженности',
      CALCULATION_XLSX: 'Расчёт задолженности',
    };
    const formatLabels = {
      CLAIM_PDF: 'PDF',
      CLAIM_DOCX: 'DOCX',
      CALCULATION_PDF: 'PDF',
      CALCULATION_XLSX: 'XLSX',
    };
    list.innerHTML = displayedDocuments.map((document) => {
      const versionNumber = documentClaimVersionNumber(document);
      const versionLabel = versionNumber ? `Версия претензии №${versionNumber}` : 'Версия претензии не определена';
      const formatLabel = formatLabels[document.documentType] || document.documentType;
      return `
      <div class="document_item">
        ${['CLAIM_PDF', 'CLAIM_DOCX'].includes(document.documentType) ? `<label>
          <input type="radio" name="document_to_send" value="${document.id}"
                 ${document.id === selectedDocumentId ? 'checked' : ''}>
          ${escapeHtml(document.documentNumber || typeLabels[document.documentType])} · ${escapeHtml(versionLabel)} · ${escapeHtml(formatLabel)}
        </label>` : `<span>${escapeHtml(typeLabels[document.documentType] || document.documentType)} · ${escapeHtml(versionLabel)} · ${escapeHtml(formatLabel)}</span>`}
        <button class="action_btn document_download" data-id="${document.id}">Скачать</button>
      </div>`;
    }).join('');
    list.querySelectorAll('input[name="document_to_send"]').forEach((radio) => {
      radio.addEventListener('change', () => {
        selectedDocumentId = radio.value;
        updateAvailableActions();
      });
    });
    list.querySelectorAll('.document_download').forEach((button) => {
      button.addEventListener('click', () => downloadDocument(button.dataset.id).catch(showError));
    });
  }
  updateAvailableActions();
}

async function buildClaimTemplateData(claimId, generatedText = '') {
  const [creditor, debtor, contract, calculation] = await Promise.all([
    getParty(currentClaim.creditorId),
    getParty(currentClaim.debtorId),
    getContract(currentClaim.contractId),
    getClaimCalculation(claimId),
  ]);
  const user = getStoredUser() || {};

  return {
    claim: {
      ...currentClaim,
      number: currentClaim.claimNumber,
      date: new Date().toISOString().slice(0, 10),
      generatedText,
      attachments: 'Согласно материалам претензии',
    },
    creditor,
    debtor,
    contract: {
      ...contract,
      date: contract.signedAt,
    },
    calculation: {
      ...calculation,
      currency: 'RUB',
    },
    signer: {
      position: 'Юрист',
      fullName: formatUserFullName(user),
    },
  };
}

async function applySelectedClaimTemplate(claimId) {
  if (isClaimTextLocked()) return;

  const select = document.getElementById('template_select');
  const editor = document.getElementById('claim_text_editor');
  const templateId = select.value;
  if (!templateId) return;

  const requestId = ++templatePreviewRequestId;
  const templateVersionId =
    select.selectedOptions[0]?.dataset.versionId || null;
  const sourceText = editor.value.trim();
  const data = await buildClaimTemplateData(claimId, sourceText);
  const preview = await previewClaimTemplate(
    templateId,
    data,
    templateVersionId
  );

  if (requestId !== templatePreviewRequestId) return;
  editor.value = preview.content || '';

  const missingVariables = preview.missingVariables || [];
  setText(
    'ai_warning',
    missingVariables.length
      ? `Шаблон вставлен. Не заполнены поля: ${missingVariables.join(', ')}`
      : 'Готовый текст шаблона вставлен в претензию'
  );
}

async function reloadClaim(claimId) {
  const claim = await getClaim(claimId);
  fillClaimCard(claim);
  await loadClaimContext(claim);
  await loadVersions(claimId);
  await Promise.allSettled([
    loadHistory(claimId),
    loadDocuments(claimId),
    loadSendChecklist(claimId),
  ]);
  return claim;
}

async function generateSelectedClaimDocument(claimId) {
  const template = document.getElementById('template_select');
  const templateId = template.value || null;
  const templateVersionId = templateId
    ? template.selectedOptions[0]?.dataset.versionId || null
    : null;
  const finalVersion = finalClaimVersion();

  if (!finalVersion) {
    throw new Error('Назначьте финальную версию текста');
  }
  if (finalVersionDocumentExists()) {
    throw new Error(`Документ ${claimDocumentFormatLabel(selectedClaimDocumentFormat())} для этой версии претензии уже сформирован.`);
  }

  const data = await buildClaimTemplateData(claimId, finalVersion.content);
  const generated = await generateClaimDocument({
    templateId,
    templateVersionId,
    claimId,
    claimVersionId: finalVersion.id,
    outputType: document.getElementById('document_format').value,
    documentNumber: currentClaim.claimNumber,
    documentDate: new Date().toISOString().slice(0, 10),
    description: `Претензия ${currentClaim.claimNumber}. Версия претензии: ${finalVersion.versionNumber}`,
    claimText: finalVersion.content,
    data,
  });

  await Promise.all([
    loadDocuments(claimId),
    loadSendChecklist(claimId),
  ]);

  return generated;
}

function showError(error) {
  showToast(error?.message || String(error), 'error');
}

async function initClaimCardPage() {
  if (!requireRole('LAWYER', 'SUPER_ADMIN')) return;
  fillUserHeader();
  await syncUserProfile().catch((error) => console.warn(error.message));

  const claimId = getQueryParam('id');
  if (!claimId) {
    showToast('Не указан id претензии', 'error');
    window.location.href = '/pages/lawyer/claims.html';
    return;
  }

  document.getElementById('back_link').addEventListener('click', () => {
    window.location.href = '/pages/lawyer/claims.html';
  });

  document.getElementById('btn_download_contract_source').addEventListener('click', async (event) => {
    const button = event.currentTarget;
    const documentId = button.dataset.documentId;
    if (!documentId) return;

    button.disabled = true;
    try {
      await downloadDocument(documentId);
    } catch (error) {
      showError(error);
    } finally {
      button.disabled = false;
    }
  });

  document.getElementById('version_select').addEventListener('change', (event) => {
    const version = currentVersions.find((item) => item.id === event.target.value);
    if (version) {
      document.getElementById('claim_text_editor').value = version.content || '';
      rememberOpenedClaimVersion(claimId, version.id);
      renderVersionDiff(claimId, version.id);
    }
  });

  document.getElementById('template_select').addEventListener('change', async () => {
    try {
      await applySelectedClaimTemplate(claimId);
    } catch (error) {
      showError(error);
    } finally {
      updateAvailableActions();
    }
  });

  document.getElementById('btn_recalculate').addEventListener('click', async () => {
    try {
      await recalculateClaim(claimId);
      await reloadClaim(claimId);
      showToast('Расчёт обновлён', 'success');
    } catch (error) { showError(error); }
  });

  document.getElementById('btn_compose').addEventListener('click', async () => {
    const button = document.getElementById('btn_compose');
    button.disabled = true;
    try {
      const result = await generateClaimText(claimId);
      const generatedText = result?.version?.content?.trim();
      if (!generatedText) {
        throw new Error('AI-модуль не вернул текст претензии');
      }
      setText('ai_warning', result.manualReviewRequired
        ? 'Требуется ручная юридическая проверка'
        : 'AI-черновик сформирован');
      await reloadClaim(claimId);
      document.getElementById('version_select').value = result.version.id;
      document.getElementById('claim_text_editor').value = generatedText;
      rememberOpenedClaimVersion(claimId, result.version.id);
    } catch (error) { showError(error); }
    finally { updateAvailableActions(); }
  });

  document.getElementById('btn_template_preview').addEventListener('click', async () => {
    if (isClaimTextLocked()) return;
    if (!document.getElementById('template_select').value) {
      return showToast('Выберите шаблон', 'error');
    }
    try {
      await applySelectedClaimTemplate(claimId);
    } catch (error) { showError(error); }
  });

  document.getElementById('btn_save_version').addEventListener('click', async () => {
    if (isClaimTextLocked()) return showToast('Текст этой претензии уже нельзя изменять', 'error');
    const content = document.getElementById('claim_text_editor').value.trim();
    if (!content) return showToast('Введите текст претензии', 'error');
    try {
      const createdVersion = await createClaimVersion(claimId, {
        source: 'LAWYER',
        baseVersionId: document.getElementById('version_select').value || null,
        content,
        comment: 'Версия сохранена юристом',
        finalVersion: false,
      });
      rememberOpenedClaimVersion(claimId, createdVersion.id);
      await reloadClaim(claimId);
    } catch (error) { showError(error); }
  });

  document.getElementById('btn_mark_final').addEventListener('click', async () => {
    if (isClaimTextLocked()) return showToast('Финальную версию этой претензии уже нельзя изменять', 'error');
    const versionId = document.getElementById('version_select').value;
    if (!versionId) return showToast('Выберите сохранённую версию', 'error');
    try {
      await markClaimVersionFinal(claimId, versionId);
      await reloadClaim(claimId);
    } catch (error) { showError(error); }
  });

  document.getElementById('btn_approve').addEventListener('click', async () => {
    try {
      await claimAction(claimId, 'approve', 'Текст и расчёт проверены юристом');
      await reloadClaim(claimId);
    } catch (error) { showError(error); }
  });

  document.getElementById('btn_edit').addEventListener('click', async () => {
    const payload = await requestClaimEdit();
    if (payload == null) return;
    try {
      await updateClaim(claimId, payload);
      await reloadClaim(claimId);
      showToast('Претензия и новая версия текста сохранены', 'success');
    } catch (error) { showError(error); }
  });

  document.getElementById('btn_request_confirmation').addEventListener('click', async () => {
    try {
      await requestNonPaymentConfirmation(claimId);
      await reloadClaim(claimId);
      showToast('Запрос передан бухгалтеру', 'success');
    } catch (error) { showError(error); }
  });

  document.getElementById('btn_validation_override').addEventListener('click', async () => {
    const reason = await requestClaimActionText({
      title: 'Ручное подтверждение',
      label: 'Что проверено и почему документ можно отправить',
      required: true,
    });
    if (reason == null) return;
    try {
      await overrideClaimValidation(claimId, reason);
      await reloadClaim(claimId);
      showToast('Ручная проверка зафиксирована', 'success');
    } catch (error) { showError(error); }
  });

  document.getElementById('btn_cancel').addEventListener('click', async () => {
    const reason = await requestClaimActionText({
      title: 'Отмена претензии',
      label: 'Причина отмены',
      required: true,
    });
    if (reason == null) return;
    try {
      await claimAction(claimId, 'cancel', reason);
      await reloadClaim(claimId);
    } catch (error) { showError(error); }
  });

  document.getElementById('btn_delete_claim').addEventListener('click', async () => {
    if (!window.confirm('Удалить претензию, связанный рейс и все его платежи без возможности восстановления?')) return;
    const button = document.getElementById('btn_delete_claim');
    button.disabled = true;
    try {
      await deleteClaim(claimId);
      window.location.href = '/pages/lawyer/claims.html';
    } catch (error) {
      showError(error);
      updateAvailableActions();
    }
  });

  document.getElementById('btn_generate_document').addEventListener('click', async () => {
    if (documentGenerationInProgress) return;
    const button = document.getElementById('btn_generate_document');
    documentGenerationInProgress = true;
    button.textContent = 'Формирование...';
    updateAvailableActions();
    try {
      await generateSelectedClaimDocument(claimId);
    } catch (error) {
      showError(error);
    } finally {
      documentGenerationInProgress = false;
      button.textContent = 'Сформировать документ';
      updateAvailableActions();
    }
  });
  document.getElementById('document_format').addEventListener('change', updateAvailableActions);

  for (const format of ['pdf', 'xlsx']) {
    document.getElementById(`btn_download_calculation_${format}`).addEventListener('click', async () => {
      try {
        await downloadClaimCalculation(claimId, format);
      } catch (error) { showError(error); }
    });
  }

  document.getElementById('btn_edit_recipient_email').addEventListener('click', () => {
    const input = document.getElementById('email_to');
    const display = document.getElementById('email_to_display');
    const button = document.getElementById('btn_edit_recipient_email');
    const editing = button.dataset.editing === 'true';

    if (!editing) {
      display.hidden = true;
      input.hidden = false;
      button.textContent = 'Готово';
      button.dataset.editing = 'true';
      input.focus();
      input.select();
      return;
    }

    if (input.value && !input.checkValidity()) {
      input.reportValidity();
      return;
    }
    setRecipientEmail(input.value);
  });

  document.getElementById('btn_download_claim').addEventListener('click', async () => {
    const button = document.getElementById('btn_download_claim');
    button.disabled = true;
    try {
      if (!selectedDocumentId) {
        throw new Error('Сначала сформируйте и выберите документ');
      }
      await downloadDocument(selectedDocumentId);
    } catch (error) {
      showError(error);
    } finally {
      updateAvailableActions();
    }
  });

  document.getElementById('btn_send_document').addEventListener('click', async () => {
    const to = document.getElementById('email_to').value.trim();
    if (!selectedDocumentId || !to) return showToast('Выберите документ и укажите email клиента', 'error');
    const status = document.getElementById('delivery_status');
    try {
      const checklist = await getClaimSendChecklist(claimId);
      if (!checklist.readyToSend) {
        throw new Error(`Отправка заблокирована: ${(checklist.warnings || []).join('; ')}`);
      }
      status.textContent = 'Проверка оплаты...';
      const preflight = await preflightClaimPayment(claimId, 'Проверка перед email-отправкой');
      if (!preflight.canSend) throw new Error('Отправка заблокирована: задолженность погашена');
      status.textContent = 'Отправка документа...';
      const delivery = await sendDocumentEmail(selectedDocumentId, {
        to,
        cc: document.getElementById('email_cc').value.trim() || null,
        subject: document.getElementById('email_subject').value.trim() || `Претензия ${currentClaim.claimNumber}`,
        message: document.getElementById('email_message').value.trim(),
      });
      if (delivery.status !== 'SENT') {
        throw new Error(delivery.errorMessage || 'Почтовый сервер не подтвердил отправку');
      }
      status.textContent = 'Фиксация статуса претензии...';
      await claimAction(claimId, 'send', `Документ ${selectedDocumentId} отправлен клиенту`);
      status.textContent = 'Документ успешно отправлен';
      await reloadClaim(claimId);
    } catch (error) {
      status.textContent = `Ошибка: ${error.message}`;
      showError(error);
    }
  });

  document.getElementById('btn_court_package').addEventListener('click', async () => {
    try {
      await claimAction(claimId, 'escalate-to-court', 'Передача в судебную работу');
      await reloadClaim(claimId);
    } catch (error) { showError(error); }
  });

  document.getElementById('add_comment_btn').addEventListener('click', async () => {
    const input = document.getElementById('comment_input');
    const text = input.value.trim();
    if (!text) return showToast('Введите комментарий', 'error');
    try {
      await addClaimComment(claimId, text);
      input.value = '';
      await loadHistory(claimId);
    } catch (error) { showError(error); }
  });

  try {
    await reloadClaim(claimId);
    await loadTemplates(claimId);
  } catch (error) {
    showError(error);
    window.location.href = '/pages/lawyer/claims.html';
  }
}

initClaimCardPage();
