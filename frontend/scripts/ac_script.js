const PAYMENT_STATUS_META = {
  IMPORTED: { label: 'Не сопоставлен', className: 'payment_status_imported' },
  PARTIALLY_MATCHED: { label: 'Частично', className: 'payment_status_partial' },
  MATCHED: { label: 'Сопоставлен', className: 'payment_status_matched' },
  REJECTED: { label: 'Отклонён', className: 'payment_status_rejected' },
};

const PAYMENT_SOURCE_LABELS = {
  ONE_C: '1С',
  BANK_STATEMENT: 'Банк',
  MANUAL_EXCEL: 'Вручную',
};

let accountantClaims = [];
let accountantPayments = [];
let paymentsLoaded = false;
let accountantOrganization = null;

function escapeAccountant(value) {
  return String(value ?? '—')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}

function formatPaymentDate(value) {
  if (!value) return '—';
  const [year, month, day] = String(value).split('-');
  return year && month && day ? `${day}.${month}.${year}` : value;
}

function todayLocalIso() {
  const now = new Date();
  const offset = now.getTimezoneOffset() * 60_000;
  return new Date(now.getTime() - offset).toISOString().slice(0, 10);
}

function claimPayableAmount(claim) {
  if (claim?.totalAmount !== null && claim?.totalAmount !== undefined) {
    const explicitTotal = Number(claim.totalAmount);
    if (Number.isFinite(explicitTotal)) return explicitTotal;
  }

  if (claim?.remainingDebt !== null && claim?.remainingDebt !== undefined) {
    const legacyTotal = Number(claim.remainingDebt);
    if (Number.isFinite(legacyTotal)) return legacyTotal;
  }

  return Number(claim?.principalDebt || 0) + Number(claim?.penaltyAmount || 0);
}

function renderActionButton(claim) {
  if (!claim.claimId) {
    return `<button class="action_btn action_btn_confirm" data-claim-action="open-shipment" data-shipment-id="${escapeAccountant(claim.shipmentId)}"
      >Открыть карточку</button>`;
  }
  if (claim.status === 'DRAFT') {
    return '<span class="action_btn_done">—</span>';
  }
  if (!['PAID', 'CANCELLED', 'CANCELLED_PAID', 'CLOSED_IN_COURT'].includes(claim.status)) {
    const withdrawButton = ['PENDING_LEGAL_REVIEW', 'LEGAL_APPROVED'].includes(claim.status)
      ? `<button class="action_btn" data-claim-action="withdraw" data-id="${escapeAccountant(claim.id)}">Отозвать</button>`
      : '';
    return `
      <button class="action_btn" data-claim-action="preflight" data-id="${escapeAccountant(claim.id)}">Проверить</button>
      <button class="action_btn action_btn_paid" data-claim-action="mark-paid" data-id="${escapeAccountant(claim.id)}">Оплата поступила</button>
      ${withdrawButton}`;
  }
  return '<span class="action_btn_done">—</span>';
}

function renderOverdueRow(claim) {
  const status = claim.status
    ? mapStatus(claim.status)
    : { text: 'Требует подтверждения', className: 'status_pending' };
  return `
    <div class="overdue_row${claim.claimId ? ' overdue_row_clickable' : ''}" ${claim.claimId ? `data-claim-id="${escapeAccountant(claim.claimId)}"` : ''}>
      <div class="overdue_row_client">${escapeAccountant(claim.debtorName)}</div>
      <div class="overdue_row_inn">${escapeAccountant(claim.debtorInn)}</div>
      <div class="overdue_row_carrier">${escapeAccountant(claim.creditorName)}</div>
      <div class="overdue_row_trip">${escapeAccountant(claim.shipmentNumber)}</div>
      <div class="overdue_row_amount">${formatMoney(claim.shipmentAmount)}</div>
      <div class="overdue_row_amount">${formatMoney(claim.paidAmount)}</div>
      <div class="overdue_row_amount">${formatMoney(claimPayableAmount(claim))}</div>
      <div class="overdue_row_days">+${escapeAccountant(claim.overdueDays ?? 0)} дн.</div>
      <div class="overdue_row_status_cell"><span class="status-pill ${status.className}">${escapeAccountant(status.text)}</span></div>
      <div class="overdue_row_action_cell">${renderActionButton(claim)}</div>
    </div>`;
}

function filteredClaims() {
  const search = document.getElementById('overdue_search').value.trim().toLowerCase();
  if (!search) return accountantClaims;
  return accountantClaims.filter((claim) => [
    claim.debtorName,
    claim.debtorInn,
    claim.creditorName,
    claim.shipmentNumber,
    claim.claimNumber,
  ].some((value) => String(value || '').toLowerCase().includes(search)));
}

function renderOverdues() {
  const claims = filteredClaims();
  const createdList = document.getElementById('created_claims_list');
  createdList.innerHTML = claims.length
    ? claims.map(renderOverdueRow).join('')
    : '<div class="empty_row">Созданных претензий нет</div>';
  bindOverdueActions();
  renderPaymentShipmentOptions();
  document.querySelectorAll('.overdue_row_clickable').forEach((row) => {
    row.tabIndex = 0;
    row.addEventListener('dblclick', (event) => {
      if (event.target.closest('button, a, input, select, .overdue_row_inn')) return;
      window.location.href = `/pages/accountant/shipment_card.html?claimId=${encodeURIComponent(row.dataset.claimId)}`;
    });
    row.addEventListener('keydown', (event) => {
      if (event.key !== 'Enter' || event.target.closest('button, a, input, select')) return;
      window.location.href = `/pages/accountant/shipment_card.html?claimId=${encodeURIComponent(row.dataset.claimId)}`;
    });
  });
}

async function choosePaymentForClaim(claimId) {
  if (!paymentsLoaded) await loadPayments();
  const claim = accountantClaims.find((item) => item.id === claimId);
  const debt = claimPayableAmount(claim);
  const eligiblePayments = accountantPayments.filter((payment) =>
    payment.status !== 'REJECTED'
    && Number(payment.availableAmount || 0) >= debt
  );
  if (!eligiblePayments.length) {
    throw new Error('Нет несопоставленного платежа, достаточного для полного погашения задолженности');
  }

  const modal = document.getElementById('payment_match_modal');
  const form = document.getElementById('payment_match_form');
  const select = document.getElementById('payment_match_select');
  const closeButton = document.getElementById('close_payment_match_modal_btn');
  const cancelButton = document.getElementById('cancel_payment_match_btn');
  document.getElementById('payment_match_modal_subtitle').textContent =
    `Остаток задолженности: ${formatMoney(debt)}. Выберите платёж, который его полностью покрывает.`;
  document.getElementById('payment_match_error').textContent = '';
  select.innerHTML = eligiblePayments.map((payment) =>
    `<option value="${escapeAccountant(payment.id)}">${escapeAccountant(formatPaymentDate(payment.paymentDate))} · ${escapeAccountant(payment.paymentNumber || 'Без номера')} · доступно ${escapeAccountant(formatMoney(payment.availableAmount))}</option>`
  ).join('');
  modal.classList.add('modal_overlay_visible');
  select.focus();

  return new Promise((resolve) => {
    const finish = (value) => {
      form.removeEventListener('submit', submit);
      closeButton.removeEventListener('click', cancel);
      cancelButton.removeEventListener('click', cancel);
      modal.removeEventListener('click', backdropCancel);
      document.removeEventListener('keydown', escapeCancel);
      modal.classList.remove('modal_overlay_visible');
      resolve(value);
    };
    const submit = (event) => {
      event.preventDefault();
      if (!select.value) return;
      finish(select.value);
    };
    const cancel = () => finish(null);
    const backdropCancel = (event) => {
      if (event.target === modal) cancel();
    };
    const escapeCancel = (event) => {
      if (event.key === 'Escape') cancel();
    };
    form.addEventListener('submit', submit);
    closeButton.addEventListener('click', cancel);
    cancelButton.addEventListener('click', cancel);
    modal.addEventListener('click', backdropCancel);
    document.addEventListener('keydown', escapeCancel);
  });
}

function bindOverdueActions() {
  document.querySelectorAll('[data-claim-action]').forEach((button) => {
    button.addEventListener('click', async () => {
      const { claimAction: action, id } = button.dataset;
      button.disabled = true;
      try {
        if (action === 'open-shipment') {
          window.location.href = `/pages/accountant/shipment_card.html?shipmentId=${encodeURIComponent(button.dataset.shipmentId)}`;
          return;
        } else if (action === 'preflight') {
          const result = await preflightClaimPayment(id, 'Ручная проверка бухгалтером');
          showToast(
            `Статус: ${result.paymentStatus}\n`
            + `Оплачено: ${formatMoney(result.paidAmount)}\n`
            + `Остаток: ${formatMoney(result.remainingAmount)}`,
            'info'
          );
        } else if (action === 'mark-paid') {
          const paymentId = await choosePaymentForClaim(id);
          if (!paymentId) return;
          await markClaimPaidByPayment(id, paymentId, 'Полная оплата подтверждена бухгалтером');
        } else if (action === 'withdraw') {
          await claimAction(
            id,
            'withdraw',
            'Претензия отозвана бухгалтером до отправки'
          );
          showToast('Претензия отозвана и сохранена в истории', 'success');
        }
        await loadOverdues();
      } catch (error) {
        showToast(error.message, 'error');
      } finally {
        button.disabled = false;
      }
    });
  });
}

async function loadOverdues() {
  const createdList = document.getElementById('created_claims_list');
  createdList.textContent = 'Загрузка...';
  try {
    const overdues = await getOverdueShipments();
    accountantClaims = (overdues || []).map((item) => ({
      ...item,
      id: item.claimId,
      status: item.claimStatus,
      debtorName: item.clientName,
      debtorInn: item.clientInn,
      creditorName: item.expeditorName,
      principalDebt: item.remainingPrincipalDebt ?? item.remainingDebt,
      penaltyAmount: item.penaltyAmount ?? 0,
      totalAmount: item.totalAmount ?? item.remainingDebt,
    })).sort((a, b) => new Date(b.paymentDeadline) - new Date(a.paymentDeadline));
    renderOverdues();
  } catch (error) {
    createdList.textContent = `Ошибка: ${error.message}`;
  }
}

function paymentShipmentClaims() {
  return accountantClaims.filter((claim) =>
    claim.shipmentId
    && claim.claimId
    && claimPayableAmount(claim) > 0
    && !['PAID', 'CANCELLED', 'CANCELLED_PAID', 'CLOSED_IN_COURT'].includes(claim.status)
  );
}

function renderPaymentShipmentOptions() {
  const select = document.getElementById('payment_shipment_id');
  if (!select) return;

  const selectedShipmentId = select.value;
  const options = paymentShipmentClaims().map((claim) => {
    const total = claimPayableAmount(claim).toLocaleString('ru-RU', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    });
    const penalty = Number(claim.penaltyAmount || 0).toLocaleString('ru-RU', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    });
    const penaltyPart = Number(claim.penaltyAmount || 0) > 0
      ? `, включая неустойку ${penalty}`
      : '';
    const label = `${claim.shipmentNumber || 'Без номера'} — ${claim.debtorName || 'Клиент'} — к оплате ${total}${penaltyPart} ${claim.currency || 'RUB'}`;
    return `<option value="${escapeAccountant(claim.shipmentId)}">${escapeAccountant(label)}</option>`;
  });

  select.innerHTML = `<option value="">${options.length ? 'Выберите рейс' : 'Нет рейсов с задолженностью'}</option>${options.join('')}`;
  if (paymentShipmentClaims().some((claim) => claim.shipmentId === selectedShipmentId)) {
    select.value = selectedShipmentId;
  }
}

function fillPaymentFromShipment() {
  const shipmentId = document.getElementById('payment_shipment_id').value;
  const claim = accountantClaims.find((item) => item.shipmentId === shipmentId);
  if (!claim) return;

  const amountInput = document.getElementById('payment_amount');
  const debt = claimPayableAmount(claim);
  amountInput.value = debt > 0 ? debt.toFixed(2) : '';
  amountInput.max = debt > 0 ? debt.toFixed(2) : '';
  document.getElementById('payment_currency').value = claim.currency || 'RUB';
  document.getElementById('payment_payer_inn').value = claim.debtorInn || '';
  document.getElementById('payment_payer_name').value = claim.debtorName || '';
  document.getElementById('payment_purpose').value = [
    `Оплата по рейсу ${claim.shipmentNumber || claim.shipmentId}`,
    claim.claimNumber ? `претензия ${claim.claimNumber}` : '',
  ].filter(Boolean).join(', ');
  document.getElementById('payment_create_message').textContent = '';
}

function paymentMatchesSearch(payment, search) {
  return [
    payment.paymentNumber,
    payment.externalPaymentId,
    payment.payerInn,
    payment.payerName,
    payment.recipientInn,
    payment.recipientName,
    payment.purpose,
  ].some((value) => String(value || '').toLowerCase().includes(search));
}

function filteredPayments() {
  const search = document.getElementById('payment_search').value.trim().toLowerCase();
  const status = document.getElementById('payment_status_filter').value;
  return accountantPayments.filter((payment) =>
    (!status || payment.status === status)
    && (!search || paymentMatchesSearch(payment, search))
  );
}

function renderPaymentRow(payment) {
  const status = PAYMENT_STATUS_META[payment.status] || {
    label: payment.status || '—',
    className: 'payment_status_rejected',
  };
  return `
    <div class="payment_row" data-payment-id="${escapeAccountant(payment.id)}" tabindex="0">
      <div>${formatPaymentDate(payment.paymentDate)}</div>
      <div>
        <div class="payment_row_primary">${escapeAccountant(payment.paymentNumber)}</div>
        <div class="payment_row_secondary">${escapeAccountant(PAYMENT_SOURCE_LABELS[payment.sourceSystem] || payment.sourceSystem)}</div>
      </div>
      <div>
        <div>${escapeAccountant(payment.payerName)}</div>
        <div class="payment_row_secondary">${escapeAccountant(payment.payerInn)}</div>
      </div>
      <div>
        <div>${escapeAccountant(payment.recipientName)}</div>
        <div class="payment_row_secondary">${escapeAccountant(payment.recipientInn)}</div>
      </div>
      <div class="payment_row_primary">${formatMoney(payment.amount)}</div>
      <div>${formatMoney(payment.matchedAmount)}</div>
      <div>${formatMoney(payment.availableAmount)}</div>
      <div><span class="payment_status ${status.className}">${escapeAccountant(status.label)}</span></div>
      <div>
        <button class="action_btn action_btn_delete" data-payment-delete="${escapeAccountant(payment.id)}">Удалить</button>
      </div>
    </div>`;
}

function renderPayments() {
  const list = document.getElementById('payments_list');
  const payments = filteredPayments();
  list.innerHTML = payments.length
    ? payments.map(renderPaymentRow).join('')
    : '<div class="empty_row">Платежи не найдены</div>';
  document.querySelectorAll('[data-payment-id]').forEach((row) => {
    const open = () => openPaymentDetails(row.dataset.paymentId);
    row.addEventListener('click', open);
    row.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' || event.key === ' ') open();
    });
  });
  document.querySelectorAll('[data-payment-delete]').forEach((button) => {
    button.addEventListener('click', async (event) => {
      event.stopPropagation();
      const reason = window.prompt(
        'Укажите причину удаления платежа. Задолженность и статус претензии будут пересчитаны:'
      );
      if (reason === null) return;
      if (!reason.trim()) {
        showToast('Причина удаления обязательна', 'error');
        return;
      }
      button.disabled = true;
      try {
        await deletePayment(button.dataset.paymentDelete, reason.trim());
        showToast('Платёж удалён, задолженность и статус претензии пересчитаны', 'success');
        await Promise.all([loadPayments(), loadOverdues()]);
      } catch (error) {
        showToast(error.message, 'error');
      } finally {
        button.disabled = false;
      }
    });
  });
}

async function loadPayments() {
  const list = document.getElementById('payments_list');
  list.textContent = 'Загрузка...';
  try {
    const page = await getPayments({ size: 100, sort: 'paymentDate,desc' });
    accountantPayments = page.content || [];
    paymentsLoaded = true;
    renderPayments();
  } catch (error) {
    list.textContent = `Ошибка: ${error.message}`;
  }
}

function paymentDetail(label, value) {
  return `
    <div class="payment_detail">
      <div class="payment_detail_label">${escapeAccountant(label)}</div>
      <div class="payment_detail_value">${escapeAccountant(value)}</div>
    </div>`;
}

async function openPaymentDetails(paymentId) {
  const modal = document.getElementById('payment_modal');
  const body = document.getElementById('payment_modal_body');
  modal.classList.add('modal_overlay_visible');
  body.textContent = 'Загрузка...';
  try {
    const details = await getPayment(paymentId);
    const payment = details.payment;
    document.getElementById('payment_modal_title').textContent =
      `Платёж ${payment.paymentNumber || payment.id}`;
    document.getElementById('payment_modal_subtitle').textContent =
      `${formatPaymentDate(payment.paymentDate)} · ${PAYMENT_SOURCE_LABELS[payment.sourceSystem] || payment.sourceSystem}`;
    const matches = details.matches || [];
    body.innerHTML = `
      <div class="payment_details_grid">
        ${paymentDetail('Плательщик', payment.payerName)}
        ${paymentDetail('ИНН плательщика', payment.payerInn)}
        ${paymentDetail('Получатель', payment.recipientName)}
        ${paymentDetail('Сумма', formatMoney(payment.amount))}
        ${paymentDetail('Сопоставлено', formatMoney(payment.matchedAmount))}
        ${paymentDetail('Доступно', formatMoney(payment.availableAmount))}
        ${paymentDetail('Назначение', payment.purpose)}
        ${paymentDetail('Внешний ID', payment.externalPaymentId)}
        ${paymentDetail('Статус', PAYMENT_STATUS_META[payment.status]?.label || payment.status)}
      </div>
      <div class="matches_title">Сопоставления</div>
      ${matches.length ? matches.map((match) => `
        <div class="match_row">
          <div>${escapeAccountant(match.targetType)}</div>
          <div>${escapeAccountant(match.targetId)}</div>
          <div>${formatMoney(match.matchedAmount)}</div>
          <div>${match.active ? 'Активно' : 'Отменено'}</div>
        </div>`).join('') : '<div class="form_message">Сопоставлений пока нет</div>'}
    `;
  } catch (error) {
    body.textContent = `Ошибка: ${error.message}`;
  }
}

function closePaymentModal() {
  document.getElementById('payment_modal').classList.remove('modal_overlay_visible');
}

function setOptional(payload, key, value) {
  if (value !== '') payload[key] = value;
}

async function loadPaymentFormReferences() {
  const user = getStoredUser();
  accountantOrganization = await getOrganization(user.organizationId);
}

function resetCreatePaymentForm() {
  const form = document.getElementById('payment_create_form');
  form.classList.remove('payment_form_visible');
  [
    'payment_number',
    'payment_amount',
    'payment_payer_inn',
    'payment_payer_name',
    'payment_purpose',
  ].forEach((id) => { document.getElementById(id).value = ''; });
  document.getElementById('payment_shipment_id').value = '';
  document.getElementById('payment_amount').removeAttribute('max');
  document.getElementById('payment_date').value = todayLocalIso();
  document.getElementById('payment_currency').value = 'RUB';
  document.getElementById('payment_create_message').textContent = '';
}

function resetImportForm(clearMessage = true) {
  document.getElementById('payment_import_form').classList.remove('payment_form_visible');
  document.getElementById('payment_import_file').value = '';
  if (clearMessage) document.getElementById('payment_import_message').textContent = '';
}

async function submitPayment() {
  const message = document.getElementById('payment_create_message');
  const button = document.getElementById('create_payment_btn');
  const paymentDate = document.getElementById('payment_date').value;
  const amountRaw = document.getElementById('payment_amount').value;
  const amount = Number(amountRaw);
  const shipmentId = document.getElementById('payment_shipment_id').value;
  const selectedClaim = accountantClaims.find((claim) => claim.shipmentId === shipmentId);

  if (!selectedClaim) {
    message.textContent = 'Выберите рейс, по которому поступил платёж.';
    return;
  }
  if (!paymentDate || amountRaw === '') {
    message.textContent = 'Заполните дату и сумму платежа.';
    return;
  }
  if (!Number.isFinite(amount) || amount <= 0) {
    message.textContent = 'Сумма должна быть положительным числом.';
    return;
  }
  if (amount > claimPayableAmount(selectedClaim)) {
    message.textContent = 'Сумма платежа не может превышать итоговую сумму долга с учётом неустойки.';
    return;
  }

  const payload = {
    paymentDate,
    amount,
    currency: document.getElementById('payment_currency').value,
  };
  setOptional(payload, 'paymentNumber', document.getElementById('payment_number').value.trim());
  setOptional(payload, 'payerInn', document.getElementById('payment_payer_inn').value.trim());
  setOptional(payload, 'payerName', document.getElementById('payment_payer_name').value.trim());
  setOptional(payload, 'recipientInn', accountantOrganization?.inn || '');
  setOptional(payload, 'recipientName', accountantOrganization?.name || '');
  setOptional(payload, 'purpose', document.getElementById('payment_purpose').value.trim());

  button.disabled = true;
  button.textContent = 'Создание...';
  message.textContent = '';
  try {
    const payment = await createPayment(payload);
    try {
      await createPaymentMatch(payment.id, {
        targetType: 'CLAIM',
        targetId: selectedClaim.claimId,
        matchedAmount: amount,
        comment: `Платёж создан для рейса ${selectedClaim.shipmentNumber || selectedClaim.shipmentId}`,
      });
    } catch (matchError) {
      await deletePayment(
        payment.id,
        'Автоматическое удаление: не удалось сопоставить новый платёж с рейсом'
      ).catch((cleanupError) => {
        console.error('Не удалось удалить платёж после ошибки сопоставления', cleanupError);
      });
      throw matchError;
    }
    resetCreatePaymentForm();
    await Promise.all([loadPayments(), loadOverdues()]);
  } catch (error) {
    message.textContent = error.message || 'Не удалось создать платёж';
  } finally {
    button.disabled = false;
    button.textContent = 'Создать платёж';
  }
}

async function submitPaymentImport() {
  const message = document.getElementById('payment_import_message');
  const button = document.getElementById('import_payment_btn');
  const source = document.getElementById('payment_import_source').value;
  const file = document.getElementById('payment_import_file').files[0];
  if (!file) {
    message.className = 'form_message form_error';
    message.textContent = 'Выберите XLSX-файл.';
    return;
  }
  if (!file.name.toLowerCase().endsWith('.xlsx')) {
    message.className = 'form_message form_error';
    message.textContent = 'Поддерживаются только файлы XLSX.';
    return;
  }

  button.disabled = true;
  button.textContent = 'Импорт...';
  message.className = 'form_message';
  message.textContent = '';
  try {
    const result = await importPayments(source, file);
    message.className = 'form_message form_message_success';
    message.textContent =
      `Импорт завершён: загружено ${result.importedRows}, дубликатов ${result.duplicateRows}, ошибок ${result.failedRows}.`;
    document.getElementById('payment_import_file').value = '';
    await loadPayments();
  } catch (error) {
    message.className = 'form_message form_error';
    message.textContent = error.message || 'Не удалось импортировать платежи';
  } finally {
    button.disabled = false;
    button.textContent = 'Загрузить';
  }
}

async function runReconciliation() {
  const message = document.getElementById('reconciliation_message');
  const button = document.getElementById('reconcile_btn');
  button.disabled = true;
  message.textContent = 'Выполняется сверка...';
  try {
    const result = await reconcilePayments();
    message.className = 'form_message form_message_success';
    message.textContent =
      `Сверка завершена: проверено ${result.paymentsChecked}, сопоставлено ${result.matchesCreated}, без совпадения ${result.unmatchedCount}.`;
    await Promise.all([loadPayments(), loadOverdues()]);
  } catch (error) {
    message.className = 'form_message form_error';
    message.textContent = error.message;
  } finally {
    button.disabled = false;
  }
}

function bindTabs() {
  document.querySelectorAll('.tab_btn').forEach((button) => {
    button.addEventListener('click', async () => {
      document.querySelectorAll('.tab_btn').forEach((item) => item.classList.remove('tab_btn_active'));
      document.querySelectorAll('.tab_panel').forEach((item) => item.classList.remove('tab_panel_active'));
      button.classList.add('tab_btn_active');
      document.getElementById(`tab_panel_${button.dataset.tab}`).classList.add('tab_panel_active');
      if (button.dataset.tab === 'payments' && !paymentsLoaded) await loadPayments();
    });
  });
}

async function initAccountantPage() {
  if (!requireRole('ACCOUNTANT', 'SUPER_ADMIN')) return;
  fillUserHeader();
  await syncUserProfile().catch((error) => console.warn(error.message));
  await loadPaymentFormReferences().catch((error) => console.warn(error.message));

  bindTabs();
  document.getElementById('overdue_search').addEventListener('input', renderOverdues);
  document.getElementById('payment_search').addEventListener('input', renderPayments);
  document.getElementById('payment_status_filter').addEventListener('change', renderPayments);
  document.getElementById('refresh_overdues_btn').addEventListener('click', loadOverdues);
  document.getElementById('refresh_payments_btn').addEventListener('click', loadPayments);
  document.getElementById('reconcile_btn').addEventListener('click', runReconciliation);

  document.getElementById('show_create_payment_btn').addEventListener('click', () => {
    resetImportForm();
    document.getElementById('payment_create_form').classList.add('payment_form_visible');
    document.getElementById('payment_date').value ||= todayLocalIso();
    renderPaymentShipmentOptions();
    document.getElementById('payment_shipment_id').focus();
  });
  document.getElementById('payment_shipment_id').addEventListener('change', fillPaymentFromShipment);
  document.getElementById('cancel_create_payment_btn').addEventListener('click', resetCreatePaymentForm);
  document.getElementById('create_payment_btn').addEventListener('click', submitPayment);

  document.getElementById('show_import_btn').addEventListener('click', () => {
    resetCreatePaymentForm();
    document.getElementById('payment_import_form').classList.add('payment_form_visible');
  });
  document.getElementById('cancel_import_btn').addEventListener('click', () => resetImportForm());
  document.getElementById('import_payment_btn').addEventListener('click', submitPaymentImport);

  document.getElementById('close_payment_modal_btn').addEventListener('click', closePaymentModal);
  document.getElementById('payment_modal').addEventListener('click', (event) => {
    if (event.target.id === 'payment_modal') closePaymentModal();
  });
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') closePaymentModal();
  });

  document.getElementById('payment_date').value = todayLocalIso();
  await loadOverdues();
}

initAccountantPage();
