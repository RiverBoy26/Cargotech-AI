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
    lawyerClaims = (page.content || []).filter((claim) => claim.status !== 'DRAFT');

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
}

initClaimsPage();
