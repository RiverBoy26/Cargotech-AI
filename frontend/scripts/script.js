function renderClaimRow(claim) {
  return `
    <div class="claim_row">
      <div class="claim_row_client">${claim.client}</div>
      <div class="claim_row_carrier">${claim.carrier}</div>
      <div class="claim_row_trip">${claim.trip}</div>
      <div class="claim_row_overdue_cell">
        <div class="claim_row_overdue ${claim.overdueClass}">${claim.overdueDays}</div>
        <div class="claim_row_overdue_sub">${claim.overdueRule}</div>
      </div>
      <div class="claim_row_amount">${claim.amount}</div>
      <div class="claim_row_penalty">${claim.penalty}</div>
      <div>
        <span class="status-pill ${claim.statusClass}">${claim.statusText}</span>
      </div>
    </div>
  `;
}

const rowsHtml = claims.map(renderClaimRow).join('');
document.getElementById('claims_list').innerHTML = rowsHtml;