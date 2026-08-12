let lawyerClaims = [];

function claimMatchesSearch(claim, search) {
  return [
    claim.debtorName,
    claim.creditorName,
    claim.shipmentNumber,
    claim.claimNumber,
  ].some((value) => String(value || '').toLowerCase().includes(search));
}

function filteredClaims() {
  const search = document.getElementById('claim_search').value.trim().toLowerCase();
  const status = document.getElementById('claim_status_filter').value;

  return lawyerClaims.filter((claim) =>
    (!status || claim.status === status)
    && (!search || claimMatchesSearch(claim, search))
  );
}

function renderClaimRow(claim) {
  const status = mapStatus(claim.status);

  return `
    <div class="claim_row claim_row_clickable" data-id="${claim.id}">
      <div class="claim_row_client">${claim.debtorName || '—'}</div>
      <div class="claim_row_carrier">${claim.creditorName || '—'}</div>
      <div class="claim_row_trip">${claim.shipmentNumber || '—'}</div>
      <div class="claim_row_overdue_cell">
        <div class="claim_row_overdue">+${claim.overdueDays ?? 0} дней</div>
        <div class="claim_row_overdue_sub">${claim.paymentDays ?? '—'} дней по договору</div>
      </div>
      <div class="claim_row_amount">${formatMoney(claim.principalDebt)}</div>
      <div class="claim_row_penalty">${formatMoney(claim.penaltyAmount)}</div>
      <div>
        <span class="status-pill ${status.className}">${status.text}</span>
      </div>
    </div>
  `;
}

function bindClaimRowClicks() {
  document.querySelectorAll('.claim_row_clickable').forEach((row) => {
    row.style.cursor = 'pointer';
    row.addEventListener('click', () => {
      window.location.href = `/pages/lawyer/claim_card.html?id=${row.dataset.id}`;
    });
  });
}

function renderClaims() {
  const listEl = document.getElementById('claims_list');
  const claims = filteredClaims();

  if (claims.length === 0) {
    listEl.innerHTML = '<div class="claim_row">Ничего не найдено</div>';
    return;
  }

  listEl.innerHTML = claims.map(renderClaimRow).join('');
  bindClaimRowClicks();
}

async function loadClaims() {
  const listEl = document.getElementById('claims_list');
  listEl.innerHTML = '<div class="claim_row">Загрузка...</div>';

  try {
    const page = await getClaims({ size: 100 });
    lawyerClaims = page.content || [];

    if (lawyerClaims.length === 0) {
      listEl.innerHTML = '<div class="claim_row">Претензий пока нет</div>';
      return;
    }

    renderClaims();
  } catch (err) {
    lawyerClaims = [];
    listEl.innerHTML = `<div class="claim_row">Ошибка: ${err.message}</div>`;
  }
}

async function initClaimsPage() {
  if (!requireRole('LAWYER', 'SUPER_ADMIN')) return;

  fillUserHeader();

  try {
    await syncUserProfile();
  } catch (err) {
    console.warn('Не удалось загрузить профиль:', err.message);
  }

  document.getElementById('claim_search').addEventListener('input', renderClaims);
  document.getElementById('claim_status_filter').addEventListener('change', renderClaims);

  await loadClaims();
  await loadShipmentOptions();
}

initClaimsPage();

const addClaimForm = document.getElementById('add_claim_form');
const claimFormError = document.getElementById('claim_form_error');

// Показать / скрыть форму
document.getElementById('create_claim_btn').addEventListener('click', () => {
  addClaimForm.classList.add('add_claim_form_visible');
  claimFormError.textContent = '';
});

async function loadShipmentOptions() {
  const select = document.getElementById('claim_shipment_id');
  try {
    const page = await getShipments();
    const shipments = page.content || [];
    select.innerHTML = '<option value="">— выберите перевозку —</option>' +
      shipments.map((item) =>
        `<option value="${item.id}">${item.orderNumber} — ${item.clientName || 'клиент не указан'}</option>`
      ).join('');
  } catch (err) {
    select.innerHTML = '<option value="">Не удалось загрузить перевозки</option>';
  }
}

function resetClaimForm() {
  addClaimForm.classList.remove('add_claim_form_visible');
  document.getElementById('claim_shipment_id').value = '';
  document.getElementById('claim_type').value = '';
  document.getElementById('claim_number').value = '';
  document.getElementById('claim_reason').value = '';
  claimFormError.textContent = '';
}

document.getElementById('cancel_claim_btn').addEventListener('click', resetClaimForm);

// Отправка формы
document.getElementById('save_claim_btn').addEventListener('click', async () => {
  const shipmentId = document.getElementById('claim_shipment_id').value.trim();
  const claimType  = document.getElementById('claim_type').value;
  const reason     = document.getElementById('claim_reason').value.trim();

  // Обязательные поля
  if (!shipmentId || !claimType || !reason) {
    claimFormError.textContent = 'Заполните обязательные поля: рейс, тип претензии, основание';
    return;
  }

  const payload = {
    shipmentId,
    claimType,
    reason,
    nonPaymentConfirmed: false,
  };

  const claimNumber    = document.getElementById('claim_number').value.trim();
  if (claimNumber)   payload.claimNumber   = claimNumber;

  const saveBtn = document.getElementById('save_claim_btn');
  saveBtn.disabled = true;
  saveBtn.textContent = 'Создание...';
  claimFormError.textContent = '';

  try {
    await createClaim(payload);

    resetClaimForm();

    if (typeof loadClaims === 'function') await loadClaims();

  } catch (err) {
    claimFormError.textContent = err.message || 'Не удалось создать претензию';
  } finally {
    saveBtn.disabled = false;
    saveBtn.textContent = 'Создать';
  }
});
