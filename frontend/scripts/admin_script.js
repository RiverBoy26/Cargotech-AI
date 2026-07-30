const ROLE_CLASS = {
  LAWYER: 'role-pill-lawyer',
  ACCOUNTANT: 'role-pill-accountant',
  EXPEDITOR_ADMIN: 'role-pill-admin',
  SUPER_ADMIN: 'role-pill-admin',
};

function escapeAdmin(value) {
  return String(value ?? '—')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');
}

function mapUserRow(user) {
  const role = user.roles?.[0] || '—';
  return {
    id: user.id,
    fullName: user.fullName,
    email: user.email,
    role: ROLE_LABELS[role] || role,
    roleClass: ROLE_CLASS[role] || '',
    status: user.active ? 'Активен' : 'Заблокирован',
    statusClass: user.active ? 'status-pill-success paid' : 'status-pill-danger escalation',
    active: user.active,
  };
}

function renderActionButton(user) {
  if (user.active) {
    return `<button class="action_btn action_btn_block" data-action="block" data-id="${user.id}">Заблокировать</button>`;
  }
  return `<button class="action_btn action_btn_unblock" data-action="unblock" data-id="${user.id}">Разблокировать</button>`;
}

function renderUserRow(user) {
  return `
    <div class="user_row" data-id="${user.id}">
      <div class="user_row_name">${user.fullName}</div>
      <div class="user_row_email">${user.email}</div>
      <div class="user_row_role_cell">
        <span class="role-pill ${user.roleClass}">${user.role}</span>
      </div>
      <div class="user_row_status_cell">
        <span class="status-pill ${user.statusClass}">${user.status}</span>
      </div>
      <div class="user_row_action_cell">
        ${renderActionButton(user)}
      </div>
    </div>
  `;
}

function bindUserActions() {
  document.querySelectorAll('.action_btn').forEach((btn) => {
    btn.addEventListener('click', async (event) => {
      event.stopPropagation();
      const { action, id } = btn.dataset;

      try {
        if (action === 'block') await blockUser(id);
        if (action === 'unblock') await unblockUser(id);
        await loadUsers();
      } catch (err) {
        alert(err.message);
      }
    });
  });
}

async function loadUsers() {
  const listEl = document.getElementById('users_list');
  listEl.innerHTML = '<div class="user_row">Загрузка...</div>';

  try {
    const page = await getUsers();
    const users = (page.content || []).map(mapUserRow);

    if (users.length === 0) {
      listEl.innerHTML = '<div class="user_row">Пользователей пока нет</div>';
      return;
    }

    listEl.innerHTML = users.map(renderUserRow).join('');
    bindUserActions();
  } catch (err) {
    listEl.innerHTML = `<div class="user_row">Ошибка: ${err.message}</div>`;
  }
}

async function loadParties() {
  const list = document.getElementById('parties_list');
  list.textContent = 'Загрузка...';
  try {
    const page = await getParties();
    list.innerHTML = (page.content || []).map((party) => `
      <div class="admin_entity_row">
        <div>${escapeAdmin(party.name)}</div>
        <div>${escapeAdmin(party.type)}</div>
        <div>${escapeAdmin(party.inn)}</div>
        <div>${escapeAdmin(party.email)}</div>
        <div>
          <span class="status-pill ${party.active ? 'status-pill-active' : 'status-pill-blocked'}">
            ${party.active ? 'Активен' : 'Архивирован'}
          </span>
        </div>
      </div>`).join('') || '<div class="admin_entity_empty">Контрагентов пока нет</div>';
  } catch (error) { list.textContent = `Ошибка: ${error.message}`; }
}

async function loadContracts() {
  const list = document.getElementById('contracts_list');
  list.textContent = 'Загрузка...';
  try {
    const page = await getContracts();
    list.innerHTML = (page.content || []).map((contract) => `
      <div class="admin_entity_row">
        <div>${escapeAdmin(contract.number)}</div>
        <div>${escapeAdmin(contract.clientName)}</div>
        <div>${escapeAdmin(contract.expeditorName)}</div>
        <div>
          <span class="status-pill ${
            contract.status === 'ACTIVE'
              ? 'status-pill-active'
              : ['EXPIRED', 'TERMINATED', 'ARCHIVED'].includes(contract.status)
                ? 'status-pill-blocked'
                : 'status-pill-neutral'
          }">${escapeAdmin(contract.status)}</span>
        </div>
        <div>${contract.paymentDays ?? 0} дней</div>
      </div>`).join('') || '<div class="admin_entity_empty">Договоров пока нет</div>';
  } catch (error) { list.textContent = `Ошибка: ${error.message}`; }
}

async function loadShipments() {
  const list = document.getElementById('shipments_list');
  list.textContent = 'Загрузка...';
  try {
    const page = await getShipments();
    list.innerHTML = (page.content || []).map((shipment) => `
      <div class="admin_entity_row">
        <div>${escapeAdmin(shipment.orderNumber)}</div>
        <div>${escapeAdmin(shipment.clientName)}</div>
        <div>${escapeAdmin(shipment.contractNumber)}</div>
        <div>${formatMoney(shipment.serviceAmount)}</div>
        <div>
          <span class="status-pill ${
            shipment.status === 'COMPLETED'
              ? 'status-pill-active'
              : shipment.status === 'CANCELLED'
                ? 'status-pill-blocked'
                : 'status-pill-neutral'
          }">${escapeAdmin(shipment.status)}</span>
        </div>
      </div>`).join('') || '<div class="admin_entity_empty">Рейсов пока нет</div>';
  } catch (error) { list.textContent = `Ошибка: ${error.message}`; }
}

const partyForm = document.getElementById('party_form');
const partyFieldIds = [
  'party_type', 'party_name', 'party_inn', 'party_kpp', 'party_ogrn',
  'party_email', 'party_phone', 'party_legal_address', 'party_postal_address',
];

function resetPartyForm() {
  partyForm.classList.remove('add_user_form_visible');
  partyFieldIds.forEach((id) => { document.getElementById(id).value = ''; });
  document.getElementById('party_form_error').textContent = '';
}

document.getElementById('add_party_btn').addEventListener('click', () => {
  partyForm.classList.add('add_user_form_visible');
  document.getElementById('party_name').focus();
});

document.getElementById('cancel_party_btn').addEventListener('click', resetPartyForm);

document.getElementById('save_party_btn').addEventListener('click', async () => {
  const payload = {
    type: document.getElementById('party_type').value,
    name: document.getElementById('party_name').value.trim(),
    inn: document.getElementById('party_inn').value.trim() || null,
    kpp: document.getElementById('party_kpp').value.trim() || null,
    ogrn: document.getElementById('party_ogrn').value.trim() || null,
    email: document.getElementById('party_email').value.trim() || null,
    phone: document.getElementById('party_phone').value.trim() || null,
    legalAddress: document.getElementById('party_legal_address').value.trim() || null,
    postalAddress: document.getElementById('party_postal_address').value.trim() || null,
  };
  const errorElement = document.getElementById('party_form_error');
  if (!payload.type || !payload.name) {
    errorElement.textContent = 'Тип и наименование контрагента обязательны';
    return;
  }
  try {
    await createParty(payload);
    resetPartyForm();
    await loadParties();
  } catch (error) {
    errorElement.textContent = error.message;
  }
});

const contractForm = document.getElementById('contract_form');
const shipmentForm = document.getElementById('shipment_form');
let shipmentContracts = [];

function setOptional(payload, key, value) {
  if (value !== '') payload[key] = value;
}

function setSelectItems(select, items, placeholder, labelBuilder) {
  select.innerHTML = [
    `<option value="">${placeholder}</option>`,
    ...items.map((item) => `<option value="${escapeAdmin(item.id)}">${escapeAdmin(labelBuilder(item))}</option>`),
  ].join('');
}

async function loadContractClients() {
  const select = document.getElementById('contract_client_id');
  select.disabled = true;
  select.innerHTML = '<option value="">Загрузка клиентов...</option>';
  try {
    const page = await getParties({ type: 'CLIENT' });
    const clients = (page.content || []).filter((party) => party.active !== false);
    setSelectItems(select, clients, '— выберите клиента —', (party) =>
      party.inn ? `${party.name} · ИНН ${party.inn}` : party.name
    );
    if (!clients.length) {
      select.innerHTML = '<option value="">Сначала создайте контрагента с типом «Клиент»</option>';
    }
  } catch (error) {
    select.innerHTML = '<option value="">Не удалось загрузить клиентов</option>';
    throw error;
  } finally {
    select.disabled = false;
  }
}

function resetContractForm() {
  contractForm.classList.remove('add_user_form_visible');
  document.getElementById('contract_number').value = '';
  document.getElementById('contract_client_id').value = '';
  document.getElementById('contract_status').value = 'ACTIVE';
  document.getElementById('contract_signed_at').value = '';
  document.getElementById('contract_valid_from').value = '';
  document.getElementById('contract_valid_to').value = '';
  document.getElementById('contract_payment_days').value = '0';
  document.getElementById('contract_payment_start_event').value = 'UNLOADING_DATE';
  document.getElementById('contract_penalty_type').value = 'NONE';
  document.getElementById('contract_penalty_rate').value = '0';
  document.getElementById('contract_penalty_rate').disabled = true;
  document.getElementById('contract_claim_response_days').value = '10';
  document.getElementById('contract_jurisdiction').value = '';
  document.getElementById('contract_form_error').textContent = '';
}

document.getElementById('add_contract_btn').addEventListener('click', async () => {
  contractForm.classList.add('add_user_form_visible');
  document.getElementById('contract_form_error').textContent = '';
  try {
    await loadContractClients();
    document.getElementById('contract_number').focus();
  } catch (error) {
    document.getElementById('contract_form_error').textContent = error.message;
  }
});

document.getElementById('cancel_contract_btn').addEventListener('click', resetContractForm);

document.getElementById('contract_penalty_type').addEventListener('change', (event) => {
  const rate = document.getElementById('contract_penalty_rate');
  const disabled = event.target.value === 'NONE';
  rate.disabled = disabled;
  if (disabled) rate.value = '0';
});

document.getElementById('save_contract_btn').addEventListener('click', async () => {
  const errorElement = document.getElementById('contract_form_error');
  const saveButton = document.getElementById('save_contract_btn');
  const number = document.getElementById('contract_number').value.trim();
  const clientId = document.getElementById('contract_client_id').value;
  const paymentDaysRaw = document.getElementById('contract_payment_days').value;
  const responseDaysRaw = document.getElementById('contract_claim_response_days').value;
  const penaltyRateRaw = document.getElementById('contract_penalty_rate').value;
  const validFrom = document.getElementById('contract_valid_from').value;
  const validTo = document.getElementById('contract_valid_to').value;

  if (!number || !clientId || paymentDaysRaw === '' || responseDaysRaw === '') {
    errorElement.textContent = 'Заполните обязательные поля: номер, клиент, срок оплаты и срок ответа.';
    return;
  }
  if (validFrom && validTo && validFrom > validTo) {
    errorElement.textContent = 'Дата окончания договора не может быть раньше даты начала.';
    return;
  }

  const paymentDays = Number(paymentDaysRaw);
  const claimResponseDays = Number(responseDaysRaw);
  const penaltyRate = Number(penaltyRateRaw || 0);
  if (![paymentDays, claimResponseDays, penaltyRate].every(Number.isFinite)
      || paymentDays < 0 || claimResponseDays < 0 || penaltyRate < 0) {
    errorElement.textContent = 'Сроки и ставка неустойки должны быть неотрицательными числами.';
    return;
  }

  const payload = {
    number,
    clientId,
    status: document.getElementById('contract_status').value,
    paymentDays,
    paymentStartEvent: document.getElementById('contract_payment_start_event').value,
    penaltyType: document.getElementById('contract_penalty_type').value,
    penaltyRate,
    claimResponseDays,
  };
  setOptional(payload, 'signedAt', document.getElementById('contract_signed_at').value);
  setOptional(payload, 'validFrom', validFrom);
  setOptional(payload, 'validTo', validTo);
  setOptional(payload, 'jurisdiction', document.getElementById('contract_jurisdiction').value.trim());

  saveButton.disabled = true;
  saveButton.textContent = 'Создание...';
  errorElement.textContent = '';
  try {
    await createContract(payload);
    resetContractForm();
    await loadContracts();
  } catch (error) {
    errorElement.textContent = error.message || 'Не удалось создать договор';
  } finally {
    saveButton.disabled = false;
    saveButton.textContent = 'Создать договор';
  }
});

function updateShipmentClient() {
  const contractId = document.getElementById('shipment_contract_id').value;
  const contract = shipmentContracts.find((item) => item.id === contractId);
  document.getElementById('shipment_client_name').value = contract?.clientName || 'Выберите договор';
}

async function loadShipmentContracts() {
  const select = document.getElementById('shipment_contract_id');
  select.disabled = true;
  select.innerHTML = '<option value="">Загрузка договоров...</option>';
  try {
    const page = await getContracts();
    shipmentContracts = (page.content || []).filter((contract) => contract.status === 'ACTIVE');
    setSelectItems(select, shipmentContracts, '— выберите договор —', (contract) =>
      `${contract.number} · ${contract.clientName}`
    );
    if (!shipmentContracts.length) {
      select.innerHTML = '<option value="">Сначала создайте действующий договор</option>';
    }
  } catch (error) {
    shipmentContracts = [];
    select.innerHTML = '<option value="">Не удалось загрузить договоры</option>';
    throw error;
  } finally {
    select.disabled = false;
  }
}

function resetShipmentForm() {
  shipmentForm.classList.remove('add_user_form_visible');
  document.getElementById('shipment_order_number').value = '';
  document.getElementById('shipment_contract_id').value = '';
  document.getElementById('shipment_client_name').value = 'Выберите договор';
  document.getElementById('shipment_status').value = 'CREATED';
  document.getElementById('shipment_route_from').value = '';
  document.getElementById('shipment_route_to').value = '';
  document.getElementById('shipment_loading_date').value = '';
  document.getElementById('shipment_unloading_date').value = '';
  document.getElementById('shipment_act_signed_at').value = '';
  document.getElementById('shipment_service_amount').value = '';
  document.getElementById('shipment_currency').value = 'RUB';
  document.getElementById('shipment_external_id').value = '';
  document.getElementById('shipment_form_error').textContent = '';
}

document.getElementById('add_shipment_btn').addEventListener('click', async () => {
  shipmentForm.classList.add('add_user_form_visible');
  document.getElementById('shipment_form_error').textContent = '';
  try {
    await loadShipmentContracts();
    updateShipmentClient();
    document.getElementById('shipment_order_number').focus();
  } catch (error) {
    document.getElementById('shipment_form_error').textContent = error.message;
  }
});

document.getElementById('shipment_contract_id').addEventListener('change', updateShipmentClient);
document.getElementById('cancel_shipment_btn').addEventListener('click', resetShipmentForm);

document.getElementById('save_shipment_btn').addEventListener('click', async () => {
  const errorElement = document.getElementById('shipment_form_error');
  const saveButton = document.getElementById('save_shipment_btn');
  const orderNumber = document.getElementById('shipment_order_number').value.trim();
  const contractId = document.getElementById('shipment_contract_id').value;
  const contract = shipmentContracts.find((item) => item.id === contractId);
  const serviceAmountRaw = document.getElementById('shipment_service_amount').value;
  const loadingDate = document.getElementById('shipment_loading_date').value;
  const unloadingDate = document.getElementById('shipment_unloading_date').value;

  if (!orderNumber || !contract || serviceAmountRaw === '') {
    errorElement.textContent = 'Заполните обязательные поля: номер рейса, договор и стоимость.';
    return;
  }
  if (loadingDate && unloadingDate && loadingDate > unloadingDate) {
    errorElement.textContent = 'Дата выгрузки не может быть раньше даты погрузки.';
    return;
  }

  const serviceAmount = Number(serviceAmountRaw);
  if (!Number.isFinite(serviceAmount) || serviceAmount < 0) {
    errorElement.textContent = 'Стоимость перевозки должна быть неотрицательным числом.';
    return;
  }

  const payload = {
    orderNumber,
    clientId: contract.clientId,
    contractId,
    serviceAmount,
    currency: document.getElementById('shipment_currency').value,
    status: document.getElementById('shipment_status').value,
  };
  setOptional(payload, 'routeFrom', document.getElementById('shipment_route_from').value.trim());
  setOptional(payload, 'routeTo', document.getElementById('shipment_route_to').value.trim());
  setOptional(payload, 'loadingDate', loadingDate);
  setOptional(payload, 'unloadingDate', unloadingDate);
  setOptional(payload, 'actSignedAt', document.getElementById('shipment_act_signed_at').value);
  setOptional(payload, 'externalId', document.getElementById('shipment_external_id').value.trim());

  saveButton.disabled = true;
  saveButton.textContent = 'Создание...';
  errorElement.textContent = '';
  try {
    await createShipment(payload);
    resetShipmentForm();
    await loadShipments();
  } catch (error) {
    errorElement.textContent = error.message || 'Не удалось создать рейс';
  } finally {
    saveButton.disabled = false;
    saveButton.textContent = 'Создать рейс';
  }
});

const tabButtons = document.querySelectorAll('.tab_btn');
const tabPanels = document.querySelectorAll('.tab_panel');

tabButtons.forEach((button) => {
  button.addEventListener('click', () => {
    const targetTab = button.dataset.tab;
    tabButtons.forEach((btn) => btn.classList.remove('tab_btn_active'));
    tabPanels.forEach((panel) => panel.classList.remove('tab_panel_active'));
    button.classList.add('tab_btn_active');
    document.getElementById(`tab_panel_${targetTab}`).classList.add('tab_panel_active');
  });
});

const addUserForm = document.getElementById('add_user_form');

document.getElementById('add_user_btn').addEventListener('click', () => {
  addUserForm.classList.add('add_user_form_visible');
});

document.getElementById('cancel_user_btn').addEventListener('click', () => {
  addUserForm.classList.remove('add_user_form_visible');
  document.getElementById('field_fullname').value = '';
  document.getElementById('field_email').value = '';
  document.getElementById('field_password').value = '';
  document.getElementById('field_role').value = '';
});

document.getElementById('save_user_btn').addEventListener('click', async () => {
  const fullName = document.getElementById('field_fullname').value.trim();
  const email = document.getElementById('field_email').value.trim();
  const password = document.getElementById('field_password').value;
  const role = document.getElementById('field_role').value;
  const user = getStoredUser();

  if (!fullName || !email || !password || !role) {
    alert('Заполните все поля');
    return;
  }

  try {
    await createUser({
      organizationId: user.organizationId,
      fullName,
      email,
      password,
      roles: [role],
    });

    addUserForm.classList.remove('add_user_form_visible');
    document.getElementById('field_fullname').value = '';
    document.getElementById('field_email').value = '';
    document.getElementById('field_password').value = '';
    document.getElementById('field_role').value = '';

    await loadUsers();
  } catch (err) {
    alert(err.message);
  }
});

async function initAdminPage() {
  if (!requireRole('EXPEDITOR_ADMIN', 'SUPER_ADMIN')) return;

  fillUserHeader();

  try {
    await syncUserProfile();
  } catch (err) {
    console.warn('Не удалось загрузить профиль:', err.message);
  }

  await loadUsers();
  await Promise.all([loadParties(), loadContracts(), loadShipments()]);
}

initAdminPage();
