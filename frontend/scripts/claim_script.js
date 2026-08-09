let currentClaim = null;
let currentVersions = [];
let currentDocuments = [];
let selectedDocumentId = null;
let currentClaimContext = {};
let templatePreviewRequestId = 0;

const LAST_OPENED_CLAIM_VERSION_STORAGE_PREFIX = 'cargotech.claim.lastOpenedVersion.';

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
  const subtitle = item.reason || item.text || '';
  return `
    <div class="history_item">
      <div class="history_item_title">${escapeHtml(title)}</div>
      <div class="history_item_meta">${escapeHtml(date)}${subtitle ? ` — ${escapeHtml(subtitle)}` : ''}</div>
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
    'btn_approve',
    status === 'PENDING_LEGAL_REVIEW' && hasPermission('CLAIM_UPDATE'),
    Boolean(currentClaim?.finalVersionId)
  );
  setButtonState(
    'btn_cancel',
    !['PAID', 'CANCELLED', 'CLOSED_IN_COURT'].includes(status) && hasPermission('CLAIM_UPDATE')
  );
  setButtonState(
    'btn_court_package',
    ['SENT', 'AWAITING_RESPONSE'].includes(status) && hasPermission('CLAIM_UPDATE')
  );
  setButtonState(
    'btn_generate_document',
    status === 'LEGAL_APPROVED' && hasPermission('DOCUMENT_GENERATE'),
    Boolean(currentClaim?.finalVersionId)
  );
  setButtonState(
    'btn_download_claim',
    approved && hasPermission('DOCUMENT_GENERATE') && hasPermission('DOCUMENT_DOWNLOAD'),
    Boolean(currentClaim?.finalVersionId)
  );
  setButtonState(
    'btn_send_document',
    status === 'LEGAL_APPROVED' && hasPermission('DOCUMENT_SEND'),
    Boolean(selectedDocumentId)
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
  }
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
  if (!currentDocuments.length) {
    selectedDocumentId = null;
    list.textContent = 'Документы ещё не сформированы';
  } else {
    selectedDocumentId = currentDocuments[0].id;
    list.innerHTML = currentDocuments.map((document) => `
      <div class="document_item">
        <label>
          <input type="radio" name="document_to_send" value="${document.id}"
                 ${document.id === selectedDocumentId ? 'checked' : ''}>
          ${escapeHtml(document.documentNumber || document.documentType)} · ${escapeHtml(document.status)}
        </label>
        <button class="action_btn document_download" data-id="${document.id}">Скачать</button>
      </div>`).join('');
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
  await Promise.allSettled([
    loadHistory(claimId),
    loadVersions(claimId),
    loadDocuments(claimId),
  ]);
  return claim;
}

async function generateSelectedClaimDocument(claimId, downloadAfterGeneration) {
  const template = document.getElementById('template_select');
  const templateId = downloadAfterGeneration ? null : template.value || null;
  const templateVersionId = templateId
    ? template.selectedOptions[0]?.dataset.versionId || null
    : null;
  const finalVersion = currentVersions.find((item) => item.id === currentClaim.finalVersionId);

  if (!finalVersion) {
    throw new Error('Назначьте финальную версию текста');
  }

  const data = await buildClaimTemplateData(claimId, finalVersion.content);
  const generated = await generateClaimDocument({
    templateId,
    templateVersionId,
    claimId,
    outputType: document.getElementById('document_format').value,
    documentNumber: currentClaim.claimNumber,
    documentDate: new Date().toISOString().slice(0, 10),
    description: `Претензия ${currentClaim.claimNumber}`,
    claimText: finalVersion.content,
    data,
  });

  await loadDocuments(claimId);

  if (downloadAfterGeneration) {
    await downloadDocument(generated.documentId);
  }

  return generated;
}

function showError(error) {
  alert(error?.message || String(error));
}

async function initClaimCardPage() {
  if (!requireRole('LAWYER', 'SUPER_ADMIN')) return;
  fillUserHeader();
  await syncUserProfile().catch((error) => console.warn(error.message));

  const claimId = getQueryParam('id');
  if (!claimId) {
    alert('Не указан id претензии');
    window.location.href = '/pages/lawyer/claims.html';
    return;
  }

  document.getElementById('back_link').addEventListener('click', () => {
    window.location.href = '/pages/lawyer/claims.html';
  });

  document.getElementById('version_select').addEventListener('change', (event) => {
    const version = currentVersions.find((item) => item.id === event.target.value);
    if (version) {
      document.getElementById('claim_text_editor').value = version.content || '';
      rememberOpenedClaimVersion(claimId, version.id);
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
      alert('Расчёт обновлён');
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
      return alert('Выберите шаблон');
    }
    try {
      await applySelectedClaimTemplate(claimId);
    } catch (error) { showError(error); }
  });

  document.getElementById('btn_save_version').addEventListener('click', async () => {
    if (isClaimTextLocked()) return alert('Текст этой претензии уже нельзя изменять');
    const content = document.getElementById('claim_text_editor').value.trim();
    if (!content) return alert('Введите текст претензии');
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
    if (isClaimTextLocked()) return alert('Финальную версию этой претензии уже нельзя изменять');
    const versionId = document.getElementById('version_select').value;
    if (!versionId) return alert('Выберите сохранённую версию');
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
    const reason = prompt('Новое основание претензии:', currentClaim?.reason || '');
    if (reason == null) return;
    try {
      await updateClaim(claimId, { reason });
      await reloadClaim(claimId);
    } catch (error) { showError(error); }
  });

  document.getElementById('btn_cancel').addEventListener('click', async () => {
    const reason = prompt('Причина отмены:');
    if (!reason) return;
    try {
      await claimAction(claimId, 'cancel', reason);
      await reloadClaim(claimId);
    } catch (error) { showError(error); }
  });

  document.getElementById('btn_generate_document').addEventListener('click', async () => {
    try {
      await generateSelectedClaimDocument(claimId, false);
    } catch (error) { showError(error); }
  });

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
      await generateSelectedClaimDocument(claimId, true);
    } catch (error) {
      showError(error);
    } finally {
      updateAvailableActions();
    }
  });

  document.getElementById('btn_send_document').addEventListener('click', async () => {
    const to = document.getElementById('email_to').value.trim();
    if (!selectedDocumentId || !to) return alert('Выберите документ и укажите email клиента');
    const status = document.getElementById('delivery_status');
    try {
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
    if (!text) return alert('Введите комментарий');
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
