function renderActionButton(claim) {
  if (!claim.nonPaymentConfirmed && claim.status === 'DRAFT') {
    return `<button class="action_btn action_btn_confirm" data-action="confirm" data-id="${claim.id}">Подтвердить неуплату</button>`;
  }

  if (claim.status !== 'PAID' && claim.status !== 'CANCELLED' && claim.status !== 'CLOSED_IN_COURT') {
    return `<button class="action_btn action_btn_paid" data-action="mark-paid" data-id="${claim.id}">Отметить оплату</button>`;
  }

  return `<span class="action_btn_done">—</span>`;
}

function renderOverdueRow(claim) {
  const status = mapStatus(claim.status);

  return `
    <div class="overdue_row" data-id="${claim.id}">
      <div class="overdue_row_client">${claim.debtorName || '—'}</div>
      <div class="overdue_row_carrier">${claim.creditorName || '—'}</div>
      <div class="overdue_row_trip">${claim.shipmentNumber || '—'}</div>
      <div class="overdue_row_amount">${formatMoney(claim.principalDebt)}</div>
      <div class="overdue_row_days">+${claim.overdueDays ?? 0} дн.</div>
      <div class="overdue_row_status_cell">
        <span class="status-pill ${status.className}">${status.text}</span>
      </div>
      <div class="overdue_row_action_cell">
        ${renderActionButton(claim)}
      </div>
    </div>
  `;
}

function bindOverdueActions() {
  document.querySelectorAll('.action_btn').forEach((btn) => {
    btn.addEventListener('click', async () => {
      const { action, id } = btn.dataset;

      try {
        if (action === 'confirm') {
          await updateClaim(id, {
            nonPaymentConfirmed: true,
            nonPaymentConfirmationComment: 'Подтверждено бухгалтером',
          });
        }

        if (action === 'mark-paid') {
          await claimAction(id, 'mark-paid', 'Оплата подтверждена бухгалтером');
        }

        await loadOverdues();
      } catch (err) {
        alert(err.message);
      }
    });
  });
}

async function loadOverdues() {
  const listEl = document.getElementById('overdues_list');
  listEl.innerHTML = '<div class="overdue_row">Загрузка...</div>';

  try {
    const page = await getClaims({ status: 'DRAFT', size: 100 });
    const claims = page.content || [];

    if (claims.length === 0) {
      listEl.innerHTML = '<div class="overdue_row">Черновиков для проверки нет</div>';
      return;
    }

    listEl.innerHTML = claims.map(renderOverdueRow).join('');
    bindOverdueActions();
  } catch (err) {
    listEl.innerHTML = `<div class="overdue_row">Ошибка: ${err.message}</div>`;
  }
}

async function initAccountantPage() {
  if (!requireAuth()) return;

  fillUserHeader();

  try {
    await syncUserProfile();
  } catch (err) {
    console.warn('Не удалось загрузить профиль:', err.message);
  }

  await loadOverdues();
}

initAccountantPage();
