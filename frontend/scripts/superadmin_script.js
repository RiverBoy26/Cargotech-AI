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

// Отрисовка одной строки пользователя

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

// отрисовка всех экспедиторов секциями

function renderExpeditorSection(expeditor) {
  const usersHtml = expeditor.users.map(renderUserRow).join('');

  return `
    <div class="expeditor_section">
      <div class="expeditor_section_header">
        <div class="expeditor_section_title">${expeditor.name}</div>
        <div class="expeditor_section_count">${expeditor.users.length} пользователей</div>
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

// Отрисовываем все секции и вставляем в страницу
document.getElementById('all_users_list').innerHTML =
  expeditors.map(renderExpeditorSection).join('');

// Навешиваем обработчики на все кнопки действий
document.querySelectorAll('.action_btn').forEach((btn) => {
  btn.addEventListener('click', (event) => {
    event.stopPropagation();
    console.log(`Суперадмин: действие "${btn.dataset.action}" для пользователя: ${btn.dataset.id}`);
  });
});
