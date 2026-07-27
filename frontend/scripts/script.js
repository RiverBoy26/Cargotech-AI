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

async function loadClaims() {
  const listEl = document.getElementById('claims_list');
  listEl.innerHTML = '<div class="claim_row">Загрузка...</div>';

  try {
    const page = await getClaims();
    const claims = page.content || [];

    if (claims.length === 0) {
      listEl.innerHTML = '<div class="claim_row">Претензий пока нет</div>';
      return;
    }

    listEl.innerHTML = claims.map(renderClaimRow).join('');
    bindClaimRowClicks();
  } catch (err) {
    listEl.innerHTML = `<div class="claim_row">Ошибка: ${err.message}</div>`;
  }
}

async function initClaimsPage() {
  if (!requireAuth()) return;

  fillUserHeader();

  try {
    await syncUserProfile();
  } catch (err) {
    console.warn('Не удалось загрузить профиль:', err.message);
  }

  await loadClaims();
}

initClaimsPage();

const addClaimForm = document.getElementById('add_claim_form');
const claimFormError = document.getElementById('claim_form_error');

// Показать / скрыть форму
document.getElementById('create_claim_btn').addEventListener('click', () => {
  addClaimForm.classList.add('add_claim_form_visible');
  claimFormError.textContent = '';
});

function resetClaimForm() {
  addClaimForm.classList.remove('add_claim_form_visible');
  document.getElementById('claim_shipment_id').value = '';
  document.getElementById('claim_type').value = '';
  document.getElementById('claim_number').value = '';
  document.getElementById('claim_principal_debt').value = '';
  document.getElementById('claim_reason').value = '';
  document.getElementById('claim_draft_content').value = '';
  document.getElementById('claim_non_payment_confirmed').checked = false;
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
    claimFormError.textContent = 'Заполните обязательные поля: ID рейса, тип претензии, основание';
    return;
  }

  const payload = {
    shipmentId,
    claimType,
    reason,
    nonPaymentConfirmed: document.getElementById('claim_non_payment_confirmed').checked,
  };

  const claimNumber    = document.getElementById('claim_number').value.trim();
  const principalDebt  = document.getElementById('claim_principal_debt').value;
  const draftContent   = document.getElementById('claim_draft_content').value.trim();

  if (claimNumber)   payload.claimNumber   = claimNumber;
  if (principalDebt) payload.principalDebt = parseFloat(principalDebt);
  if (draftContent)  payload.draftContent  = draftContent;

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
