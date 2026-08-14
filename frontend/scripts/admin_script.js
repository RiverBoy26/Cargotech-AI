const ROLE_CLASS = {
  LAWYER: 'role-pill-lawyer',
  ACCOUNTANT: 'role-pill-accountant',
  EXPEDITOR_ADMIN: 'role-pill-admin',
  SUPER_ADMIN: 'role-pill-admin',
};

const CONTRACT_STATUS_LABEL = {
  DRAFT: 'Черновик', ACTIVE: 'Действует', EXPIRED: 'Истёк',
  TERMINATED: 'Расторгнут', ARCHIVED: 'Архивный',
};
const EXTRACTION_STATUS_LABEL = {
  NOT_STARTED: 'Файл не загружен', PENDING: 'Идёт распознавание',
  REVIEW_REQUIRED: 'Требуется проверка', CONFIRMED: 'Разбор подтверждён', FAILED: 'Ошибка разбора',
};
const EXTRACTION_FIELD_LABEL = {
  CONTRACT_NUMBER: 'Номер договора', SIGNED_AT: 'Дата договора',
  PAYMENT_DAYS: 'Срок оплаты', PAYMENT_DAY_TYPE: 'Тип дней срока оплаты', PAYMENT_START_EVENT: 'Начало срока оплаты',
  PAYMENT_SCHEDULE_TYPE: 'Перенос срока оплаты', PAYMENT_WEEK_DAYS: 'Платёжные дни',
  PENALTY_TYPE: 'Вид неустойки', PENALTY_RATE: 'Ставка',
  PENALTY_CAP_PERCENT: 'Максимальный размер неустойки, %', PENALTY_CAP_BASE: 'База ограничения',
  CLAIM_RESPONSE_DAYS: 'Срок ответа', CLAIM_RESPONSE_DAY_TYPE: 'Тип дней срока ответа', JURISDICTION: 'Подсудность', EXACT_CLAUSE: 'Точный пункт договора',
};
const CONTRACT_REVIEW_SCALAR_FIELDS = [
  'CONTRACT_NUMBER', 'SIGNED_AT', 'PAYMENT_DAYS', 'PAYMENT_DAY_TYPE', 'PAYMENT_START_EVENT',
  'PAYMENT_SCHEDULE_TYPE', 'PAYMENT_WEEK_DAYS',
  'PENALTY_TYPE', 'PENALTY_RATE', 'PENALTY_CAP_PERCENT', 'PENALTY_CAP_BASE',
  'CLAIM_RESPONSE_DAYS', 'CLAIM_RESPONSE_DAY_TYPE', 'JURISDICTION',
];
const PAYMENT_SCHEDULE_FIELDS = new Set(['PAYMENT_SCHEDULE_TYPE', 'PAYMENT_WEEK_DAYS']);
const PENALTY_CAP_FIELDS = new Set(['PENALTY_CAP_PERCENT', 'PENALTY_CAP_BASE']);
const SPECIAL_CONTRACT_FIELDS = new Set([...PAYMENT_SCHEDULE_FIELDS, ...PENALTY_CAP_FIELDS]);
const PAYMENT_START_EVENT_OPTIONS = {
  ACT_SIGNED: 'Дата подписания акта', UNLOADING_DATE: 'Дата выгрузки',
  TTN_SIGNED: 'Дата подписания ТТН', INVOICE_DATE: 'Дата счёта',
  REGISTRY_INCLUDED: 'Дата включения рейса в реестр',
  DOCUMENT_PACKAGE_RECEIVED: 'Дата получения полного комплекта документов',
};
const TERM_DAY_TYPE_OPTIONS = {
  CALENDAR_DAYS: 'Календарные дни', WORKING_DAYS: 'Рабочие дни', BANKING_DAYS: 'Банковские дни',
};
const PAYMENT_SCHEDULE_TYPE_OPTIONS = {
  NEXT_PAYMENT_DAY: 'Ближайший следующий платёжный день',
};
const PAYMENT_WEEK_DAY_OPTIONS = {
  MONDAY: 'Пн', TUESDAY: 'Вт', WEDNESDAY: 'Ср', THURSDAY: 'Чт',
  FRIDAY: 'Пт', SATURDAY: 'Сб', SUNDAY: 'Вс',
};
const PENALTY_TYPE_OPTIONS = {
  CONTRACT_PENALTY: 'Договорная неустойка', ARTICLE_395: 'Статья 395 ГК РФ', NONE: 'Не начисляется',
};
const PENALTY_CAP_BASE_OPTIONS = {
  PRINCIPAL_DEBT: 'Основной долг',
  OUTSTANDING_DEBT: 'Непогашенная задолженность',
  SHIPMENT_COST: 'Стоимость соответствующей перевозки',
  INVOICE_AMOUNT: 'Сумма соответствующего счёта',
};
const CLAUSE_TYPE_OPTIONS = {
  PAYMENT_TERMS: 'Условия оплаты', PENALTY: 'Неустойка',
  CLAIM_PROCEDURE: 'Претензионный порядок', JURISDICTION: 'Подсудность',
  LIABILITY: 'Ответственность', OTHER: 'Другое',
};
const PARTY_TYPE_LABEL = { CLIENT: 'Клиент', EXPEDITOR: 'Экспедитор', OTHER: 'Другое' };
const SHIPMENT_STATUS_LABEL = {
  PLANNED: 'Запланирован', IN_PROGRESS: 'В пути', COMPLETED: 'Завершён', CANCELLED: 'Отменён',
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
    displayName: formatUserFullName(user),
    email: user.email,
    role: ROLE_LABELS[role] || role,
    roleClass: ROLE_CLASS[role] || '',
    status: user.active ? 'Активен' : 'Заблокирован',
    statusClass: user.active ? 'status-pill-success paid' : 'status-pill-danger escalation',
    active: user.active,
  };
}

function renderActionButton(user) {
  if (String(user.id) === String(getStoredUser()?.userId)) {
    return '<span class="action_btn_done">Текущая учётная запись</span>';
  }
  if (user.active) {
    return `<button class="action_btn action_btn_block" data-action="block" data-id="${user.id}">Заблокировать</button>`;
  }
  return `<button class="action_btn action_btn_unblock" data-action="unblock" data-id="${user.id}">Разблокировать</button>`;
}

function renderUserRow(user) {
  return `
    <div class="user_row" data-id="${user.id}">
      <div class="user_row_name">${escapeAdmin(user.displayName)}</div>
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
        showToast(err.message, 'error');
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
        <div>${escapeAdmin(PARTY_TYPE_LABEL[party.type] || party.type)}</div>
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

function termDayTypeLabel(value) {
  return { CALENDAR_DAYS: 'календарных дней', WORKING_DAYS: 'рабочих дней', BANKING_DAYS: 'банковских дней' }[value] || 'дней';
}

function paymentWeekDaysLabel(value) {
  if (!value) return '';
  return value.split(',')
    .map((day) => PAYMENT_WEEK_DAY_OPTIONS[day.trim()] || day.trim())
    .filter(Boolean)
    .join(', ');
}

function paymentScheduleSummary(contract) {
  if (contract.paymentScheduleType !== 'NEXT_PAYMENT_DAY' || !contract.paymentWeekDays) return '';
  return ` → следующий платёжный день (${paymentWeekDaysLabel(contract.paymentWeekDays)})`;
}

async function loadContracts() {
  const list = document.getElementById('contracts_list');
  list.textContent = 'Загрузка...';
  try {
    const page = await getContracts();
    list.innerHTML = (page.content || []).map((contract) => `
      <div class="admin_entity_row" data-contract-row="${escapeAdmin(contract.id)}">
        <div>${contract.number ? escapeAdmin(contract.number) : '<span class="contract_value_missing">Не подтверждён</span>'}</div>
        <div>${escapeAdmin(contract.clientName)}</div>
        <div>${escapeAdmin(contract.expeditorName)}</div>
        <div>
          <span class="status-pill ${
            contract.status === 'ACTIVE'
              ? 'status-pill-active'
              : ['EXPIRED', 'TERMINATED', 'ARCHIVED'].includes(contract.status)
                ? 'status-pill-blocked'
                : 'status-pill-neutral'
          }">${escapeAdmin(CONTRACT_STATUS_LABEL[contract.status] || contract.status)}</span>
        </div>
        <div>
          <div>${contract.paymentDays == null
            ? 'Не указан'
            : `${contract.paymentDays} ${escapeAdmin(termDayTypeLabel(contract.paymentDayType))}${escapeAdmin(paymentScheduleSummary(contract))}`}</div>
          <small>${escapeAdmin(EXTRACTION_STATUS_LABEL[contract.extractionStatus] || contract.extractionStatus)}</small>
          ${['REVIEW_REQUIRED', 'CONFIRMED'].includes(contract.extractionStatus)
            ? `<button class="secondary_btn contract_review_btn" type="button" data-contract-id="${escapeAdmin(contract.id)}">${contract.extractionStatus === 'CONFIRMED' ? 'Просмотреть / изменить' : 'Проверить и подтвердить'}</button>`
            : ''}
        </div>
      </div>
      <div class="contract_extraction_panel" id="contract_extraction_${escapeAdmin(contract.id)}" hidden></div>`).join('') || '<div class="admin_entity_empty">Договоров пока нет</div>';
    bindContractExtractionActions();
    schedulePendingContractRefresh((page.content || []).some((contract) => contract.extractionStatus === 'PENDING'));
  } catch (error) { list.textContent = `Ошибка: ${error.message}`; }
}

let pendingContractRefreshTimer = null;
const contractExtractionDrafts = new Map();

function schedulePendingContractRefresh(hasPending) {
  if (pendingContractRefreshTimer) clearTimeout(pendingContractRefreshTimer);
  pendingContractRefreshTimer = hasPending
    ? setTimeout(() => { loadContracts(); }, 2500)
    : null;
}

function renderSelectOptions(options, currentValue) {
  return [
    '<option value="">— не найдено —</option>',
    ...Object.entries(options).map(([value, label]) =>
      `<option value="${escapeAdmin(value)}" ${value === currentValue ? 'selected' : ''}>${escapeAdmin(label)}</option>`
    ),
  ].join('');
}

function renderContractCandidateInput(candidate, index) {
  const value = candidate.value || '';
  const common = `class="form_input contract_candidate_value" data-candidate-index="${index}"`;
  if (candidate.field === 'PAYMENT_START_EVENT') {
    return `<select ${common}>${renderSelectOptions(PAYMENT_START_EVENT_OPTIONS, value)}</select>`;
  }
  if (candidate.field === 'PAYMENT_DAY_TYPE' || candidate.field === 'CLAIM_RESPONSE_DAY_TYPE') {
    return `<select ${common}>${renderSelectOptions(TERM_DAY_TYPE_OPTIONS, value)}</select>`;
  }
  if (candidate.field === 'PAYMENT_SCHEDULE_TYPE') {
    return `<select ${common}>${renderSelectOptions(PAYMENT_SCHEDULE_TYPE_OPTIONS, value)}</select>`;
  }
  if (candidate.field === 'PAYMENT_WEEK_DAYS') {
    const selected = new Set(String(value || '').split(',').map((item) => item.trim()).filter(Boolean));
    return `<div class="contract_weekday_picker" data-candidate-index="${index}">
      ${Object.entries(PAYMENT_WEEK_DAY_OPTIONS).map(([day, label]) => `
        <label class="contract_weekday_option">
          <input class="contract_weekday_checkbox" type="checkbox" value="${escapeAdmin(day)}" ${selected.has(day) ? 'checked' : ''}>
          <span>${escapeAdmin(label)}</span>
        </label>`).join('')}
    </div>`;
  }
  if (candidate.field === 'PENALTY_TYPE') {
    return `<select ${common}>${renderSelectOptions(PENALTY_TYPE_OPTIONS, value)}</select>`;
  }
  if (candidate.field === 'PENALTY_CAP_BASE') {
    return `<select ${common}>${renderSelectOptions(PENALTY_CAP_BASE_OPTIONS, value)}</select>`;
  }
  if (candidate.field === 'JURISDICTION') {
    return `<textarea ${common} rows="2" maxlength="1000" placeholder="Не найдено в договоре">${escapeAdmin(value)}</textarea>`;
  }
  const types = {
    SIGNED_AT: 'date', PAYMENT_DAYS: 'number', PENALTY_RATE: 'number', PENALTY_CAP_PERCENT: 'number', CLAIM_RESPONSE_DAYS: 'number',
  };
  const type = types[candidate.field] || 'text';
  const numberAttributes = type === 'number'
    ? ` min="0" step="${['PENALTY_RATE', 'PENALTY_CAP_PERCENT'].includes(candidate.field) ? '0.0001' : '1'}"`
    : '';
  const required = candidate.field === 'CONTRACT_NUMBER' ? ' required maxlength="128"' : '';
  return `<input ${common} type="${type}" value="${escapeAdmin(value)}"${numberAttributes}${required} placeholder="Не найдено в договоре">`;
}

function isLegalFallbackCandidate(candidate) {
  if (candidate.confidence != null || candidate.manuallyEdited) return false;
  if (candidate.field === 'PENALTY_TYPE' && candidate.value === 'ARTICLE_395') return true;
  if (candidate.field === 'CLAIM_RESPONSE_DAYS' && String(candidate.value) === '30') return true;
  if (candidate.field === 'CLAIM_RESPONSE_DAY_TYPE' && candidate.value === 'CALENDAR_DAYS') return true;
  return false;
}

function legalFallbackLabel(candidate) {
  if (candidate.field === 'PENALTY_TYPE') return 'Договорная неустойка не установлена — применяется ст. 395 ГК РФ';
  return 'Договорный срок не установлен — применяется 30 календарных дней по ч. 5 ст. 4 АПК РФ';
}

function extractionReliability(candidate) {
  if (candidate.manuallyEdited) return '<span class="contract_manual_badge">Изменено вручную</span>';
  if (isLegalFallbackCandidate(candidate)) {
    return `<span class="contract_fallback_badge">${escapeAdmin(legalFallbackLabel(candidate))}</span>`;
  }
  if (candidate.confidence == null && SPECIAL_CONTRACT_FIELDS.has(candidate.field)) {
    return '<span class="contract_optional_badge">Особое условие не установлено</span>';
  }
  if (candidate.confidence == null) return '<span class="contract_missing_badge">Не найдено — требуется ручная проверка</span>';
  const percent = Math.round(Number(candidate.confidence) * 100);
  const level = percent >= 90 ? 'высокая' : percent >= 75 ? 'средняя' : 'низкая';
  return `Надёжность автоматического извлечения: ${level}`;
}

function renderContractScalar(candidate, index) {
  return `
    <article class="contract_extraction_item" data-review-index="${index}" data-contract-field="${escapeAdmin(candidate.field)}">
      <label class="contract_candidate_label">${escapeAdmin(EXTRACTION_FIELD_LABEL[candidate.field] || candidate.field)}${candidate.field === 'CONTRACT_NUMBER' ? ' *' : ''}</label>
      ${renderContractCandidateInput(candidate, index)}
      <small class="contract_candidate_reliability">${extractionReliability(candidate)}</small>
      ${candidate.source
        ? `<blockquote>${escapeAdmin(candidate.source)}${candidate.sourcePage ? ` · стр. ${candidate.sourcePage}` : ''}${candidate.clauseNumber ? ` · п. ${escapeAdmin(candidate.clauseNumber)}` : ''}</blockquote>`
        : isLegalFallbackCandidate(candidate)
          ? '<p class="contract_source_fallback">Это юридический fallback, а не извлечённый пункт договора. Ссылка на несуществующий пункт не создаётся.</p>'
          : '<p class="contract_source_missing">Источник не найден. Система не создаёт ссылку на пункт автоматически.</p>'}
    </article>`;
}

function renderContractClause(candidate, index) {
  return `
    <article class="contract_extraction_item contract_clause_item" data-review-index="${index}">
      <div class="contract_clause_header">
        <strong>Пункт договора</strong>
        <button class="secondary_btn contract_clause_remove" type="button" data-index="${index}">Удалить</button>
      </div>
      <div class="contract_clause_grid">
        <label>Номер пункта<input class="form_input contract_clause_number" value="${escapeAdmin(candidate.clauseNumber || '')}" maxlength="64" placeholder="Например, 4.2"></label>
        <label>Категория<select class="form_input form_select contract_clause_type">${renderSelectOptions(CLAUSE_TYPE_OPTIONS, candidate.clauseType || '')}</select></label>
      </div>
      <label>Текст пункта<textarea class="form_input contract_candidate_value" rows="3" maxlength="4000">${escapeAdmin(candidate.value || '')}</textarea></label>
      <small class="contract_candidate_reliability">${extractionReliability(candidate)}</small>
      ${candidate.source
        ? `<blockquote>${escapeAdmin(candidate.source)}${candidate.sourcePage ? ` · стр. ${candidate.sourcePage}` : ''}</blockquote>`
        : '<p class="contract_source_missing">Добавлено вручную — автоматический источник отсутствует.</p>'}
    </article>`;
}

function renderSpecialContractTerms(entries) {
  if (!entries.length) return '';
  const paymentEntries = entries.filter(({ candidate }) => PAYMENT_SCHEDULE_FIELDS.has(candidate.field));
  const penaltyEntries = entries.filter(({ candidate }) => PENALTY_CAP_FIELDS.has(candidate.field));
  const hasPayment = paymentEntries.some(({ candidate }) => Boolean(candidate.value));
  const hasPenaltyCap = penaltyEntries.some(({ candidate }) => Boolean(candidate.value));
  return `
    <section class="contract_payment_schedule_section contract_special_terms_section">
      <div class="contract_payment_schedule_header">
        <div>
          <h4>Особые условия договора</h4>
          <p>Редкие условия показываются только когда они действительно найдены или добавлены вручную. Они влияют на расчёт, но не перегружают основную карточку.</p>
        </div>
      </div>
      ${hasPayment || paymentEntries.some(({ candidate }) => candidate.manuallyEdited)
        ? `<div class="contract_special_term_group"><strong>Платёжный календарь</strong><div class="contract_payment_schedule_grid">${paymentEntries.map(({ candidate, index }) => renderContractScalar(candidate, index)).join('')}</div></div>`
        : ''}
      ${hasPenaltyCap || penaltyEntries.some(({ candidate }) => candidate.manuallyEdited)
        ? `<div class="contract_special_term_group"><strong>Ограничение договорной неустойки</strong><div class="contract_payment_schedule_grid">${penaltyEntries.map(({ candidate, index }) => renderContractScalar(candidate, index)).join('')}</div></div>`
        : ''}
    </section>`;
}

function normalizedCandidate(candidate) {
  return {
    field: candidate.field,
    value: candidate.value ?? null,
    source: candidate.source ?? null,
    sourcePage: candidate.sourcePage ?? null,
    confidence: candidate.confidence ?? null,
    clauseNumber: candidate.clauseNumber ?? null,
    clauseType: candidate.clauseType ?? null,
    manuallyEdited: Boolean(candidate.manuallyEdited),
  };
}

function renderContractReview(panel, contractId, extraction) {
  const candidates = (extraction.candidates || []).map(normalizedCandidate);
  for (const field of CONTRACT_REVIEW_SCALAR_FIELDS) {
    if (!candidates.some((candidate) => candidate.field === field)) {
      candidates.push(normalizedCandidate({ field }));
    }
  }
  candidates.sort((left, right) => {
    const leftOrder = left.field === 'EXACT_CLAUSE' ? 100 : CONTRACT_REVIEW_SCALAR_FIELDS.indexOf(left.field);
    const rightOrder = right.field === 'EXACT_CLAUSE' ? 100 : CONTRACT_REVIEW_SCALAR_FIELDS.indexOf(right.field);
    return leftOrder - rightOrder;
  });
  const draft = { ...extraction, candidates, showSpecialTerms: Boolean(extraction.showSpecialTerms) };
  contractExtractionDrafts.set(contractId, draft);
  const candidateEntries = candidates.map((candidate, index) => ({ candidate, index }));
  const scalarEntries = candidateEntries.filter(({ candidate }) =>
    candidate.field !== 'EXACT_CLAUSE' && !SPECIAL_CONTRACT_FIELDS.has(candidate.field)
  );
  const specialEntries = candidateEntries.filter(({ candidate }) => SPECIAL_CONTRACT_FIELDS.has(candidate.field));
  const hasSpecialTerms = specialEntries.some(({ candidate }) => Boolean(candidate.value));
  const clauseEntries = candidateEntries.filter(({ candidate }) => candidate.field === 'EXACT_CLAUSE');
  panel.innerHTML = `
    <div class="contract_extraction_title">Проверка условий договора</div>
    <p class="contract_review_hint">Проверьте найденные значения. Юридические fallback-и отмечены отдельно и не выдаются за условия договора.</p>
    <div class="contract_review_grid">
      ${scalarEntries.map(({ candidate, index }) => renderContractScalar(candidate, index)).join('')}
    </div>
    ${(hasSpecialTerms || draft.showSpecialTerms)
      ? renderSpecialContractTerms(specialEntries)
      : '<button class="secondary_btn contract_special_terms_add" type="button">+ Добавить особые условия договора</button>'}
    <section class="contract_clause_section">
      <div class="contract_clause_section_header">
        <div>
          <h4>Найденные пункты-первоисточники</h4>
          <p>Это не дополнительные настройки договора. Здесь сохраняются точные фрагменты, на которые система сможет ссылаться в претензии и использовать как договорный контекст. Удаляйте только явно нерелевантные пункты.</p>
        </div>
      </div>
      <div class="contract_clause_list">
        ${clauseEntries.length
          ? clauseEntries.map(({ candidate, index }) => renderContractClause(candidate, index)).join('')
          : '<p class="contract_clause_empty">Релевантные пункты автоматически не найдены. Их можно добавить вручную.</p>'}
      </div>
      <button class="secondary_btn contract_clause_add" type="button">+ Добавить пункт вручную</button>
    </section>
    <p class="form_error contract_review_error" role="alert"></p>
    <div class="add_user_form_actions">
      <button class="secondary_btn contract_review_close" type="button">Закрыть</button>
      <button class="secondary_btn contract_review_save" type="button">Сохранить исправления</button>
      <button class="primary_btn contract_review_confirm" type="button">Подтвердить договор</button>
    </div>`;

  panel.querySelector('.contract_review_close').addEventListener('click', () => { panel.hidden = true; });
  panel.querySelector('.contract_special_terms_add')?.addEventListener('click', () => {
    draft.showSpecialTerms = true;
    for (const field of SPECIAL_CONTRACT_FIELDS) {
      const candidate = draft.candidates.find((item) => item.field === field);
      if (candidate) candidate.manuallyEdited = true;
    }
    renderContractReview(panel, contractId, draft);
  });
  panel.querySelector('.contract_clause_add').addEventListener('click', () => {
    draft.candidates.push(normalizedCandidate({ field: 'EXACT_CLAUSE', manuallyEdited: true }));
    renderContractReview(panel, contractId, draft);
  });
  panel.querySelectorAll('.contract_clause_remove').forEach((button) => {
    button.addEventListener('click', () => {
      draft.candidates.splice(Number(button.dataset.index), 1);
      renderContractReview(panel, contractId, draft);
    });
  });
  panel.querySelector('.contract_review_save').addEventListener('click', async (event) => {
    try {
      await saveContractReview(panel, contractId, event.currentTarget, true);
    } catch (error) {
      // The error is rendered next to the review actions.
    }
  });
  panel.querySelector('.contract_review_confirm').addEventListener('click', async (event) => {
    const numberInput = panel.querySelector('[data-contract-field="CONTRACT_NUMBER"] .contract_candidate_value');
    if (!numberInput?.value.trim()) {
      panel.querySelector('.contract_review_error').textContent = 'Укажите номер договора перед подтверждением.';
      return;
    }
    event.currentTarget.disabled = true;
    try {
      await saveContractReview(panel, contractId, event.currentTarget, false);
      await confirmContractExtraction(contractId);
      showToast('Договор подтверждён, карточка заполнена', 'success');
      await loadContracts();
    } catch (error) {
      panel.querySelector('.contract_review_error').textContent = error.message;
      event.currentTarget.disabled = false;
    }
  });
}

function collectContractReview(panel, contractId) {
  const draft = contractExtractionDrafts.get(contractId);
  return draft.candidates.map((candidate, index) => {
    const item = panel.querySelector(`[data-review-index="${index}"]`);
    if (!item) return { ...candidate };
    const value = candidate.field === 'PAYMENT_WEEK_DAYS'
      ? (Array.from(item.querySelectorAll('.contract_weekday_checkbox:checked'))
          .map((input) => input.value)
          .join(',') || null)
      : (item.querySelector('.contract_candidate_value')?.value.trim() || null);
    const clauseNumberInput = item.querySelector('.contract_clause_number');
    const clauseTypeInput = item.querySelector('.contract_clause_type');
    const clauseNumber = clauseNumberInput ? (clauseNumberInput.value.trim() || null) : (candidate.clauseNumber || null);
    const clauseType = clauseTypeInput ? (clauseTypeInput.value || null) : (candidate.clauseType || null);
    const changed = value !== (candidate.value || null)
      || clauseNumber !== (candidate.clauseNumber || null)
      || clauseType !== (candidate.clauseType || null);
    return {
      ...candidate,
      value,
      clauseNumber,
      clauseType,
      manuallyEdited: candidate.manuallyEdited || changed,
    };
  }).filter((candidate) => candidate.field !== 'EXACT_CLAUSE' || candidate.value);
}

async function saveContractReview(panel, contractId, button, rerender) {
  const errorElement = panel.querySelector('.contract_review_error');
  button.disabled = true;
  errorElement.textContent = '';
  try {
    const extraction = await submitContractExtraction(contractId, collectContractReview(panel, contractId));
    showToast('Исправления сохранены', 'success');
    if (rerender) renderContractReview(panel, contractId, extraction);
    return extraction;
  } catch (error) {
    errorElement.textContent = error.message;
    throw error;
  } finally {
    if (button.isConnected) button.disabled = false;
  }
}

function bindContractExtractionActions() {
  document.querySelectorAll('.contract_review_btn').forEach((button) => {
    button.addEventListener('click', async () => {
      const contractId = button.dataset.contractId;
      const panel = document.getElementById(`contract_extraction_${contractId}`);
      panel.hidden = false;
      panel.textContent = 'Загрузка найденных условий...';
      try {
        const extraction = await getContractExtraction(contractId);
        renderContractReview(panel, contractId, extraction);
      } catch (error) {
        panel.textContent = `Ошибка: ${error.message}`;
      }
    });
  });
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
          }">${escapeAdmin(SHIPMENT_STATUS_LABEL[shipment.status] || shipment.status)}</span>
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
  document.getElementById('contract_client_id').value = '';
  document.getElementById('contract_file').value = '';
  document.getElementById('contract_form_error').textContent = '';
}

document.getElementById('add_contract_btn').addEventListener('click', async () => {
  contractForm.classList.add('add_user_form_visible');
  document.getElementById('contract_form_error').textContent = '';
  try {
    await loadContractClients();
    document.getElementById('contract_client_id').focus();
  } catch (error) {
    document.getElementById('contract_form_error').textContent = error.message;
  }
});

document.getElementById('cancel_contract_btn').addEventListener('click', resetContractForm);

document.getElementById('save_contract_btn').addEventListener('click', async () => {
  const errorElement = document.getElementById('contract_form_error');
  const saveButton = document.getElementById('save_contract_btn');
  const clientId = document.getElementById('contract_client_id').value;
  const contractFile = document.getElementById('contract_file').files[0];

  if (!clientId || !contractFile) {
    errorElement.textContent = 'Выберите клиента и обязательный файл договора.';
    return;
  }

  saveButton.disabled = true;
  saveButton.textContent = 'Загрузка договора...';
  errorElement.textContent = '';
  try {
    const uploaded = await uploadContractDocument(contractFile);
    saveButton.textContent = 'Запуск распознавания...';
    await createContract({ clientId, documentId: uploaded.id });
    resetContractForm();
    await loadContracts();
    showToast('Договор загружен. Идёт распознавание условий.', 'info');
  } catch (error) {
    errorElement.textContent = error.message || 'Не удалось загрузить договор';
  } finally {
    saveButton.disabled = false;
    saveButton.textContent = 'Загрузить и распознать';
  }
});

function updateShipmentClient() {
  const contractId = document.getElementById('shipment_contract_id').value;
  const contract = shipmentContracts.find((item) => item.id === contractId);
  document.getElementById('shipment_client_name').value = contract?.clientName || 'Выберите договор';
  const anchorLabel = document.getElementById('shipment_payment_anchor_label');
  const anchorHint = document.getElementById('shipment_payment_anchor_hint');
  const labels = {
    REGISTRY_INCLUDED: 'Дата включения рейса в реестр',
    DOCUMENT_PACKAGE_RECEIVED: 'Дата получения полного комплекта документов',
  };
  if (anchorLabel) anchorLabel.textContent = labels[contract?.paymentStartEvent] || 'Дата договорного события начала срока оплаты';
  if (anchorHint) {
    anchorHint.textContent = labels[contract?.paymentStartEvent]
      ? 'Обязательна для расчёта просрочки по выбранному договору.'
      : 'Заполняется для нестандартного договорного события, если оно не совпадает с актом, выгрузкой, ТТН или счётом.';
  }
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
  document.getElementById('shipment_payment_start_event_date').value = '';
  document.getElementById('shipment_service_amount').value = '';
  document.getElementById('shipment_currency').value = 'RUB';
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
  setOptional(payload, 'paymentStartEventDate', document.getElementById('shipment_payment_start_event_date').value);

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
  document.getElementById('field_first_name').value = '';
  document.getElementById('field_last_name').value = '';
  document.getElementById('field_middle_name').value = '';
  document.getElementById('field_email').value = '';
  document.getElementById('field_password').value = '';
  document.getElementById('field_role').value = '';
});

document.getElementById('save_user_btn').addEventListener('click', async () => {
  const firstName = document.getElementById('field_first_name').value.trim();
  const lastName = document.getElementById('field_last_name').value.trim();
  const middleName = document.getElementById('field_middle_name').value.trim();
  const email = document.getElementById('field_email').value.trim();
  const password = document.getElementById('field_password').value;
  const role = document.getElementById('field_role').value;
  const user = getStoredUser();

  if (!firstName || !lastName || !email || !password || !role) {
    showToast('Заполните все поля', 'error');
    return;
  }

  try {
    await createUser({
      organizationId: user.organizationId,
      firstName,
      lastName,
      middleName: middleName || null,
      email,
      password,
      roles: [role],
    });

    addUserForm.classList.remove('add_user_form_visible');
    document.getElementById('field_first_name').value = '';
    document.getElementById('field_last_name').value = '';
    document.getElementById('field_middle_name').value = '';
    document.getElementById('field_email').value = '';
    document.getElementById('field_password').value = '';
    document.getElementById('field_role').value = '';

    await loadUsers();
  } catch (err) {
    showToast(err.message, 'error');
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
