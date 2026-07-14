let currentClaim = null;

function setText(id, value) {
  const el = document.getElementById(id);
  if (el) el.textContent = value ?? '—';
}

function renderHistoryItem(item) {
  const date = item.changedAt || item.createdAt || '';
  const title = item.newStatus
    ? `${item.previousStatus || '—'} → ${item.newStatus}`
    : (item.text || 'Комментарий');
  const subtitle = item.reason || item.text || '';

  return `
    <div class="history_item">
      <div class="history_item_title">${title}</div>
      <div class="history_item_meta">${date}${subtitle ? ' — ' + subtitle : ''}</div>
    </div>
  `;
}

async function loadHistory(claimId) {
  const listEl = document.getElementById('history_list');
  listEl.innerHTML = 'Загрузка...';

  try {
    const [statusHistory, comments] = await Promise.all([
      getClaimStatusHistory(claimId),
      getClaimComments(claimId),
    ]);

    const items = [
      ...(statusHistory || []).map((h) => ({ ...h, kind: 'status' })),
      ...(comments || []).map((c) => ({ ...c, kind: 'comment' })),
    ];

    if (items.length === 0) {
      listEl.innerHTML = '<div class="history_item">История пуста</div>';
      return;
    }

    listEl.innerHTML = items.map(renderHistoryItem).join('');
  } catch (err) {
    listEl.innerHTML = `<div class="history_item">Ошибка: ${err.message}</div>`;
  }
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
  setText('info_payment_term', claim.paymentDays != null ? `${claim.paymentDays} дней` : '—');
  setText('info_completion_date', claim.actSignedAt || claim.unloadingDate || '—');
  setText('info_overdue_date', claim.overdueStartDate || '—');
  setText('info_lawyer_name', claim.assignedLawyerName || '—');

  const stripe = document.getElementById('claim_card_stripe');
  if (stripe) stripe.className = `claim_card_stripe ${status.className}`;
}

async function reloadClaim(claimId) {
  const claim = await getClaim(claimId);
  fillClaimCard(claim);
  await loadHistory(claimId);
  return claim;
}

async function initClaimCardPage() {
  if (!requireAuth()) return;

  fillUserHeader();

  try {
    await syncUserProfile();
  } catch (err) {
    console.warn('Не удалось загрузить профиль:', err.message);
  }

  const claimId = getQueryParam('id');
  if (!claimId) {
    alert('Не указан id претензии');
    window.location.href = '/pages/lawyer/claims.html';
    return;
  }

  document.getElementById('back_link')?.addEventListener('click', () => {
    window.location.href = '/pages/lawyer/claims.html';
  });

  document.getElementById('btn_recalculate')?.addEventListener('click', async () => {
    try {
      await recalculateClaim(claimId);
      await reloadClaim(claimId);
      alert('Расчёт обновлён');
    } catch (err) {
      alert(err.message);
    }
  });

  document.getElementById('add_comment_btn')?.addEventListener('click', async () => {
    const input = document.getElementById('comment_input');
    const text = input?.value.trim();
    if (!text) {
      alert('Введите комментарий');
      return;
    }

    try {
      await addClaimComment(claimId, text);
      input.value = '';
      await loadHistory(claimId);
    } catch (err) {
      alert(err.message);
    }
  });

  document.getElementById('btn_compose')?.addEventListener('click', () => {
    alert('AI-генерация пока не подключена');
  });

  document.getElementById('btn_download')?.addEventListener('click', () => {
    alert('Скачивание DOCX/PDF пока не подключено');
  });

  document.getElementById('btn_edit')?.addEventListener('click', async () => {
    if (!currentClaim) return;
    const reason = prompt('Новое основание претензии:', currentClaim.reason || '');
    if (reason == null) return;

    try {
      await updateClaim(claimId, { reason });
      await reloadClaim(claimId);
    } catch (err) {
      alert(err.message);
    }
  });

  document.getElementById('btn_court_package')?.addEventListener('click', async () => {
    if (currentClaim?.status !== 'SENT' && currentClaim?.status !== 'AWAITING_RESPONSE') {
      alert('Пакет для суда доступен после отправки претензии');
      return;
    }
    try {
      await claimAction(claimId, 'escalate-to-court', 'Передача в судебную работу');
      await reloadClaim(claimId);
      alert('Претензия переведена в судебную работу');
    } catch (err) {
      alert(err.message);
    }
  });

  try {
    await reloadClaim(claimId);
  } catch (err) {
    alert(`Не удалось загрузить претензию: ${err.message}`);
    window.location.href = '/pages/lawyer/claims.html';
  }
}

initClaimCardPage();
