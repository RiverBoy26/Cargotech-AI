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
