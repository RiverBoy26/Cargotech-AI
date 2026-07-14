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
  if (!requireAuth()) return;

  fillUserHeader();

  try {
    await syncUserProfile();
  } catch (err) {
    console.warn('Не удалось загрузить профиль:', err.message);
  }

  await loadUsers();
}

initAdminPage();
