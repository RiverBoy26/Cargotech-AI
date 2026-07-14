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

// Отрисовка таблицы пользователей

function renderActionButton(user) {
  if (user.status === 'Активен') {
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

document.getElementById('users_list').innerHTML =
  currentExpeditorUsers.map(renderUserRow).join('');

document.querySelectorAll('.action_btn').forEach((btn) => {
  btn.addEventListener('click', (event) => {
    event.stopPropagation();
    console.log(`Действие "${btn.dataset.action}" для пользователя: ${btn.dataset.id}`);
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
  document.getElementById('field_role').value = '';
});

document.getElementById('save_user_btn').addEventListener('click', () => {
  const fullName = document.getElementById('field_fullname').value.trim();
  const email = document.getElementById('field_email').value.trim();
  const role = document.getElementById('field_role').value;

  if (!fullName || !email || !role) {
    alert('Заполните все поля');
    return;
  }

  console.log('Добавить пользователя:', { fullName, email, role });

  addUserForm.classList.remove('add_user_form_visible');
  document.getElementById('field_fullname').value = '';
  document.getElementById('field_email').value = '';
  document.getElementById('field_role').value = '';
});
