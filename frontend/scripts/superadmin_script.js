//  рендер пользователей

const ROLE_CLASS = {
  LAWYER: 'role-pill-lawyer',
  ACCOUNTANT: 'role-pill-accountant',
  EXPEDITOR_ADMIN: 'role-pill-admin',
  SUPER_ADMIN: 'role-pill-admin',
};

function mapUserRow(user) {
  const role = user.roles?.[0] || '—';
  return {
    id: user.id,
    fullName: user.fullName,
    email: user.email,
    organizationId: user.organizationId,
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


// Группировка по organizationId

function groupByOrganization(users) {
  const groups = {};
  users.forEach((user) => {
    const orgId = user.organizationId || 'unknown';
    if (!groups[orgId]) groups[orgId] = [];
    groups[orgId].push(user);
  });
  return groups;
}

function renderExpeditorSection(orgId, users) {
  const usersHtml = users.map(renderUserRow).join('');
  const title = orgId === 'unknown' ? 'Без организации' : `Организация ${orgId}`;

  return `
    <div class="expeditor_section" data-org-id="${orgId}">
      <div class="expeditor_section_header">
        <div class="expeditor_section_title">${title}</div>
        <div class="expeditor_section_count">${users.length} пользователей</div>
      </div>
      <div class="users_table">
        <div class="users_table_head">
          <div class="users_table_head_t">ФИО</div>
          <div class="users_table_head_t">Email</div>
          <div class="users_table_head_t">Роль</div>
          <div class="users_table_head_t">Статус</div>
          <div class="users_table_head_t">Действие</div>
        </div>
        <div class="users_list">${usersHtml}</div>
      </div>
    </div>
  `;
}


// Блокировка/разблокировка

function bindUserActions() {
  document.querySelectorAll('.action_btn').forEach((btn) => {
    btn.addEventListener('click', async (event) => {
      event.stopPropagation();
      const { action, id } = btn.dataset;
      try {
        if (action === 'block') await blockUser(id);
        if (action === 'unblock') await unblockUser(id);
        await loadAllUsers();
      } catch (err) {
        alert(err.message);
      }
    });
  });
}


// Загрузка всех пользователей

async function loadAllUsers() {
  const listEl = document.getElementById('all_users_list');
  listEl.innerHTML = '<div style="padding:24px;color:#555">Загрузка...</div>';

  try {
    const page = await getUsers({ size: 200 });
    const users = (page.content || []).map(mapUserRow);

    if (users.length === 0) {
      listEl.innerHTML = '<div style="padding:24px;color:#555">Пользователей пока нет</div>';
      return;
    }

    const groups = groupByOrganization(users);
    listEl.innerHTML = Object.entries(groups)
      .map(([orgId, orgUsers]) => renderExpeditorSection(orgId, orgUsers))
      .join('');

    bindUserActions();
    bindUserRowClicks();
  } catch (err) {
    listEl.innerHTML = `<div style="padding:24px;color:#c0392b">Ошибка: ${err.message}</div>`;
  }
}


// Просмотр пользователя

const modal = document.getElementById('user_modal_overlay');
const modalLoading = document.getElementById('modal_loading');
const modalError = document.getElementById('modal_error');
const modalFieldGrid = document.querySelector('.modal_field_grid');

function showModal() {
  modal.classList.add('modal_overlay_visible');
}

function hideModal() {
  modal.classList.remove('modal_overlay_visible');
  modalLoading.style.display = 'none';
  modalError.textContent = '';
  modalFieldGrid.style.display = 'grid';
}

function fillModal(user) {
  document.getElementById('modal_user_name').textContent = user.fullName;
  document.getElementById('modal_user_email').textContent = user.email;
  document.getElementById('modal_user_role').textContent = user.role;
  document.getElementById('modal_user_status').textContent = user.status;
  document.getElementById('modal_user_expeditor').textContent =
    user.organizationId || '—';
}

function bindUserRowClicks() {
  document.querySelectorAll('.user_row').forEach((row) => {
    row.addEventListener('click', async () => {
      const userId = row.dataset.id;
      showModal();

      modalLoading.style.display = 'block';
      modalFieldGrid.style.display = 'none';
      modalError.textContent = '';

      try {
        const user = await getUser(userId);
        fillModal(mapUserRow(user));
        modalLoading.style.display = 'none';
        modalFieldGrid.style.display = 'grid';
      } catch (err) {
        modalLoading.style.display = 'none';
        modalError.textContent = err.message || 'Не удалось загрузить данные';
      }
    });
  });
}

document.getElementById('modal_close_btn').addEventListener('click', hideModal);
document.getElementById('modal_close_footer_btn').addEventListener('click', hideModal);
modal.addEventListener('click', (event) => {
  if (event.target === modal) hideModal();
});


// Переключение вкладок

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


// Форма добавления пользователя

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
  document.getElementById('field_expeditor').value = '';
});

document.getElementById('save_user_btn').addEventListener('click', async () => {
  const fullName = document.getElementById('field_fullname').value.trim();
  const email = document.getElementById('field_email').value.trim();
  const password = document.getElementById('field_password').value;
  const role = document.getElementById('field_role').value;
  const organizationId = document.getElementById('field_expeditor').value;

  if (!fullName || !email || !password || !role || !organizationId) {
    alert('Заполните все поля');
    return;
  }

  try {
    await createUser({ fullName, email, password, roles: [role], organizationId });

    addUserForm.classList.remove('add_user_form_visible');
    document.getElementById('field_fullname').value = '';
    document.getElementById('field_email').value = '';
    document.getElementById('field_password').value = '';
    document.getElementById('field_role').value = '';
    document.getElementById('field_expeditor').value = '';

    await loadAllUsers();
  } catch (err) {
    alert(err.message);
  }
});


// Инициализация страницы

async function initSuperAdminPage() {
  if (!requireAuth()) return;
  fillUserHeader();
  try {
    await syncUserProfile();
  } catch (err) {
    console.warn('Не удалось загрузить профиль:', err.message);
  }
  await loadAllUsers();
}

initSuperAdminPage();
