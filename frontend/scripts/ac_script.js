function renderActionButton(overdue) {
  if (overdue.status === 'waiting_confirm') {
    return `<button class="action_btn action_btn_confirm" data-action="confirm" data-id="${overdue.id}">Подтвердить неуплату</button>`;
  }

  if (overdue.status === 'in_progress') {
    return `<button class="action_btn action_btn_paid" data-action="mark-paid" data-id="${overdue.id}">Отметить оплату</button>`;
  }

  return `<span class="action_btn_done">—</span>`;
}

function renderOverdueRow(overdue) {
  return `
    <div class="overdue_row" data-id="${overdue.id}">
      <div class="overdue_row_client">${overdue.client}</div>
      <div class="overdue_row_carrier">${overdue.carrier}</div>
      <div class="overdue_row_trip">${overdue.trip}</div>
      <div class="overdue_row_amount">${overdue.amount}</div>
      <div class="overdue_row_days">${overdue.overdueDays}</div>
      <div class="overdue_row_status_cell">
        <span class="status-pill ${overdue.statusClass}">${overdue.statusText}</span>
      </div>
      <div class="overdue_row_action_cell">
        ${renderActionButton(overdue)}
      </div>
    </div>
  `;
}

const rowsHtml = overdues.map(renderOverdueRow).join('');
document.getElementById('overdues_list').innerHTML = rowsHtml;