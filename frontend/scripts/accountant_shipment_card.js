function setAccountantCardText(id, value) {
  document.getElementById(id).textContent = value ?? '—';
}

async function initAccountantShipmentCard() {
  if (!requireRole('ACCOUNTANT', 'SUPER_ADMIN')) return;
  fillUserHeader();
  await syncUserProfile().catch(() => {});
  const claimId = getQueryParam('claimId');
  const message = document.getElementById('draft_message');
  const saveButton = document.getElementById('save_draft_btn');
  if (!claimId) {
    message.textContent = 'Не указан идентификатор претензии';
    saveButton.disabled = true;
    return;
  }

  try {
    const [claim, versions, overdues] = await Promise.all([
      getClaim(claimId),
      getClaimVersions(claimId),
      getOverdueShipments(),
    ]);
    const shipment = (overdues || []).find((item) => item.claimId === claimId) || {};
    const latestVersion = [...(versions || [])].sort((a, b) => b.versionNumber - a.versionNumber)[0];
    const status = mapStatus(claim.status);
    setAccountantCardText('shipment_card_title', `Рейс ${claim.shipmentNumber || shipment.shipmentNumber || '—'}`);
    setAccountantCardText('shipment_card_subtitle', `Черновик претензии ${claim.claimNumber || ''}`);
    setAccountantCardText('shipment_client', claim.debtorName);
    setAccountantCardText('shipment_expeditor', claim.creditorName);
    setAccountantCardText('shipment_amount', formatMoney(shipment.shipmentAmount));
    setAccountantCardText('shipment_debt', formatMoney(shipment.remainingDebt ?? claim.principalDebt));
    setAccountantCardText('shipment_overdue', `${shipment.overdueDays ?? claim.overdueDays ?? 0} дн.`);
    setAccountantCardText('shipment_claim_number', claim.claimNumber);
    const statusElement = document.getElementById('shipment_claim_status');
    statusElement.textContent = status.text;
    statusElement.className = `status-pill ${status.className}`;
    document.getElementById('draft_reason').value = claim.reason || '';
    document.getElementById('draft_text').value = latestVersion?.content || '';

    const editable = claim.status === 'DRAFT';
    document.getElementById('draft_reason').disabled = !editable;
    document.getElementById('draft_text').disabled = !editable;
    saveButton.hidden = !editable;
    if (!editable) message.textContent = 'Редактирование доступно только для черновика претензии';

    saveButton.addEventListener('click', async () => {
      const text = document.getElementById('draft_text').value.trim();
      if (!text) return void (message.textContent = 'Заполните текст черновика');
      saveButton.disabled = true;
      message.textContent = 'Сохранение...';
      try {
        await updateAccountantClaimDraft(claimId, { reason: document.getElementById('draft_reason').value.trim(), text });
        message.textContent = 'Изменения сохранены как новая версия черновика';
      } catch (error) {
        message.textContent = `Ошибка: ${error.message}`;
      } finally {
        saveButton.disabled = false;
      }
    });
  } catch (error) {
    message.textContent = `Не удалось загрузить карточку: ${error.message}`;
    saveButton.disabled = true;
  }
}

initAccountantShipmentCard();
