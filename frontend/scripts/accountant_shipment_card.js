function setAccountantCardText(id, value) {
  document.getElementById(id).textContent = value ?? '—';
}

function getAccountantSubmissionPayload() {
  const reason = document.getElementById('draft_reason').value.trim();
  const text = document.getElementById('draft_text').value.trim();
  if (!reason) throw new Error('Введите основание претензии');
  if (!text) throw new Error('Введите текст будущей претензии');
  return { reason, text };
}

function fillShipmentSummary(shipment, claim = null) {
  const status = claim
    ? mapStatus(claim.status)
    : { text: 'Требует подтверждения', className: 'status-pill-warning waiting' };
  setAccountantCardText('shipment_card_title', `Рейс ${claim?.shipmentNumber || shipment.shipmentNumber || '—'}`);
  setAccountantCardText(
    'shipment_card_subtitle',
    claim ? `Претензия ${claim.claimNumber || ''}` : 'Подготовка претензии по просроченному рейсу'
  );
  setAccountantCardText('shipment_client', claim?.debtorName || shipment.clientName);
  setAccountantCardText('shipment_expeditor', claim?.creditorName || shipment.expeditorName);
  setAccountantCardText('shipment_amount', formatMoney(shipment.shipmentAmount));
  setAccountantCardText('shipment_debt', formatMoney(shipment.remainingDebt ?? claim?.principalDebt));
  setAccountantCardText('shipment_overdue', `${shipment.overdueDays ?? claim?.overdueDays ?? 0} дн.`);
  setAccountantCardText('shipment_claim_number', claim?.claimNumber || 'Будет присвоен автоматически');
  const statusElement = document.getElementById('shipment_claim_status');
  statusElement.textContent = status.text;
  statusElement.className = `status-pill ${status.className}`;
}

function markSubmissionComplete(claim) {
  const status = mapStatus(claim.status);
  const statusElement = document.getElementById('shipment_claim_status');
  statusElement.textContent = status.text;
  statusElement.className = `status-pill ${status.className}`;
  setAccountantCardText('shipment_claim_number', claim.claimNumber);
  setAccountantCardText('shipment_card_subtitle', `Претензия ${claim.claimNumber || ''} передана юристу`);
  document.getElementById('draft_reason').disabled = true;
  document.getElementById('draft_text').disabled = true;
  document.getElementById('save_draft_btn').hidden = true;
  document.getElementById('confirm_non_payment_btn').hidden = true;
}

async function initAccountantShipmentCard() {
  if (!requireRole('ACCOUNTANT', 'SUPER_ADMIN')) return;
  fillUserHeader();
  await syncUserProfile().catch(() => {});

  const claimId = getQueryParam('claimId');
  const shipmentId = getQueryParam('shipmentId');
  const message = document.getElementById('draft_message');
  const saveButton = document.getElementById('save_draft_btn');
  const confirmButton = document.getElementById('confirm_non_payment_btn');

  if (!claimId && !shipmentId) {
    message.textContent = 'Не указан идентификатор рейса или претензии';
    return;
  }

  try {
    const overdues = await getOverdueShipments();
    let claim = null;
    let shipment = null;
    let versions = [];

    if (claimId) {
      [claim, versions] = await Promise.all([
        getClaim(claimId),
        getClaimVersions(claimId),
      ]);
      shipment = (overdues || []).find((item) => item.shipmentId === claim.shipmentId) || {};
    } else {
      shipment = (overdues || []).find((item) => item.shipmentId === shipmentId);
      if (!shipment) throw new Error('Просроченный рейс не найден или задолженность уже погашена');
      if (shipment.claimId) {
        window.location.replace(`/pages/accountant/shipment_card.html?claimId=${encodeURIComponent(shipment.claimId)}`);
        return;
      }
    }

    fillShipmentSummary(shipment, claim);
    const latestVersion = [...versions].sort((a, b) => b.versionNumber - a.versionNumber)[0];
    document.getElementById('draft_reason').value = claim?.reason || '';
    document.getElementById('draft_text').value = latestVersion?.content || '';

    const editable = !claim || claim.status === 'DRAFT';
    document.getElementById('draft_reason').disabled = !editable;
    document.getElementById('draft_text').disabled = !editable;
    confirmButton.hidden = !editable;
    saveButton.hidden = !claim || !editable;
    if (!editable) {
      message.textContent = 'Редактирование доступно только до передачи претензии юристу';
    }

    saveButton.addEventListener('click', async () => {
      saveButton.disabled = true;
      message.textContent = '';
      try {
        const payload = getAccountantSubmissionPayload();
        message.textContent = 'Сохранение...';
        await updateAccountantClaimDraft(claimId, payload);
        message.textContent = 'Изменения сохранены как новая версия черновика';
      } catch (error) {
        message.textContent = `Ошибка: ${error.message}`;
      } finally {
        saveButton.disabled = false;
      }
    });

    confirmButton.addEventListener('click', async () => {
      confirmButton.disabled = true;
      saveButton.disabled = true;
      message.textContent = '';
      try {
        const payload = getAccountantSubmissionPayload();
        message.textContent = 'Создание претензии и передача юристу...';
        const submitted = claim
          ? await submitClaimToLegalReview(claimId, payload)
          : await confirmShipmentNonPayment(shipment.shipmentId, payload);
        markSubmissionComplete(submitted);
        message.textContent = 'Неуплата подтверждена. Претензия создана со статусом «На проверке».';
      } catch (error) {
        message.textContent = `Ошибка: ${error.message}`;
      } finally {
        confirmButton.disabled = false;
        saveButton.disabled = false;
      }
    });
  } catch (error) {
    message.textContent = `Не удалось загрузить карточку: ${error.message}`;
    saveButton.hidden = true;
    confirmButton.hidden = true;
  }
}

initAccountantShipmentCard();
