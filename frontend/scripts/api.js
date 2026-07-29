/**
 * API-клиент CargoTech + общие утилиты для страниц.
 * API_BASE — поменяй, если бэкенд на другом адресе.
 */
const API_BASE = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1'
  ? 'http://localhost:8080/api/v1'
  : '/api/v1';

const STORAGE = {
  accessToken: 'cargotech_access_token',
  refreshToken: 'cargotech_refresh_token',
  user: 'cargotech_user',
};

const STATUS_MAP = {
  DRAFT: { text: 'Черновик', className: 'status-pill-info draft' },
  PENDING_LEGAL_REVIEW: { text: 'На проверке', className: 'status-pill-warning waiting' },
  LEGAL_APPROVED: { text: 'Утверждено', className: 'status-pill-success confirmed' },
  SENT: { text: 'Отправлено', className: 'status-pill-warning waiting' },
  AWAITING_RESPONSE: { text: 'Ожидание', className: 'status-pill-warning waiting' },
  PAID: { text: 'Оплачено', className: 'status-pill-success paid' },
  ESCALATED_TO_COURT: { text: 'Эскалация', className: 'status-pill-danger escalation' },
  CANCELLED: { text: 'Отменено', className: 'status-pill-info draft' },
  CLOSED_IN_COURT: { text: 'Закрыто', className: 'status-pill-success closed' },
};

const ROLE_LABELS = {
  LAWYER: 'Юрист',
  ACCOUNTANT: 'Бухгалтер',
  EXPEDITOR_ADMIN: 'Администратор',
  SUPER_ADMIN: 'Суперадмин',
};

function getAccessToken() {
  return localStorage.getItem(STORAGE.accessToken);
}

function saveSession(data) {
  localStorage.setItem(STORAGE.accessToken, data.accessToken);
  localStorage.setItem(STORAGE.refreshToken, data.refreshToken);
  localStorage.setItem(STORAGE.user, JSON.stringify({
    userId: data.userId,
    organizationId: data.organizationId,
    roles: data.roles || [],
    permissions: data.permissions || [],
  }));
}

function clearSession() {
  localStorage.removeItem(STORAGE.accessToken);
  localStorage.removeItem(STORAGE.refreshToken);
  localStorage.removeItem(STORAGE.user);
}

function getStoredUser() {
  const raw = localStorage.getItem(STORAGE.user);
  return raw ? JSON.parse(raw) : null;
}

function hasRole(role) {
  return Boolean(getStoredUser()?.roles?.includes(role));
}

function hasPermission(permission) {
  return Boolean(getStoredUser()?.permissions?.includes(permission));
}

function requireRole(...allowedRoles) {
  if (!requireAuth()) return false;
  const roles = getStoredUser()?.roles || [];
  if (!allowedRoles.some((role) => roles.includes(role))) {
    redirectByRole(roles);
    return false;
  }
  return true;
}

function formatMoney(value) {
  if (value == null) return '—';
  return `${Number(value).toLocaleString('ru-RU')} ₽`;
}

function mapStatus(status) {
  return STATUS_MAP[status] || { text: status || '—', className: 'status-pill-info draft' };
}

function getQueryParam(name) {
  return new URLSearchParams(window.location.search).get(name);
}

function fillUserHeader() {
  const user = getStoredUser();
  if (!user) return;

  const nameEl = document.querySelector('.topbar_usename');
  const roleEl = document.querySelector('.topbar_user_role');

  if (nameEl && user.fullName) nameEl.textContent = user.fullName;
  if (roleEl && user.roles?.[0]) {
    roleEl.textContent = ROLE_LABELS[user.roles[0]]?.toLowerCase() || user.roles[0];
  }
}

async function apiRequest(path, options = {}) {
  const {
    skipAuth = false,
    ...fetchOptions
  } = options;

  const headers = { ...(fetchOptions.headers || {}) };
  const hasBody = fetchOptions.body !== undefined && fetchOptions.body !== null;

  if (hasBody && !(fetchOptions.body instanceof FormData)) {
    headers['Content-Type'] = 'application/json';
  }

  if (!skipAuth) {
    const token = getAccessToken();

    if (token) {
      headers.Authorization = `Bearer ${token}`;
    }
  }

  let response;
  try {
    response = await fetch(`${API_BASE}${path}`, {
      ...fetchOptions,
      headers,
    });
  } catch (cause) {
    const error = new Error(
      `Сервис недоступен (${API_BASE}${path}). Проверьте gateway, нужный backend-модуль и CORS.`
    );
    error.cause = cause;
    error.code = 'NETWORK_ERROR';
    throw error;
  }

  if (response.status === 204) {
    return null;
  }

  const data = await response.json().catch(() => ({}));

  if (!response.ok) {
    if (response.status === 401 && !skipAuth) {
      clearSession();
      window.location.href = '/pages/authorization/login.html';
    }

    const error = new Error(data.message || `Ошибка ${response.status}`);
    error.status = response.status;
    error.code = data.code;
    throw error;
  }

  return data;
}

function buildQuery(params) {
  const query = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      query.set(key, value);
    }
  });
  return query.toString();
}

// --- Auth ---

async function login(email, password) {
  clearSession();

  const data = await apiRequest('/auth/login', {
    method: 'POST',
    skipAuth: true,
    body: JSON.stringify({ email, password }),
  });

  saveSession(data);
  return data;
}

async function logout() {
  const refreshToken = localStorage.getItem(STORAGE.refreshToken);

  try {
    if (refreshToken) {
      await apiRequest('/auth/logout', {
        method: 'POST',
        skipAuth: true,
        body: JSON.stringify({ refreshToken }),
      });
    }
  } finally {
    clearSession();
    window.location.href = '/pages/authorization/login.html';
  }
}

async function getMe() {
  return apiRequest('/auth/me');
}

async function syncUserProfile() {
  const me = await getMe();
  localStorage.setItem(STORAGE.user, JSON.stringify({
    ...getStoredUser(),
    userId: me.id,
    organizationId: me.organizationId,
    fullName: me.fullName,
    email: me.email,
    roles: me.roles,
    permissions: me.permissions,
  }));
  fillUserHeader();
  return me;
}

// --- Users ---

async function getUsers(params = {}) {
  const qs = buildQuery({
    page: params.page ?? 0,
    size: params.size ?? 50,
    sort: params.sort ?? 'fullName,asc',
    search: params.search,
    active: params.active,
    role: params.role,
    organizationId: params.organizationId,
  });
  return apiRequest(`/users?${qs}`);
}

async function getUser(userId) {
  return apiRequest(`/users/${userId}`);
}

async function createUser(payload) {
  return apiRequest('/users', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

async function blockUser(userId) {
  return apiRequest(`/users/${userId}/block`, { method: 'POST' });
}

async function unblockUser(userId) {
  return apiRequest(`/users/${userId}/unblock`, { method: 'POST' });
}

async function getRoles() {
  return apiRequest('/roles');
}

// --- Organizations ---

async function getOrganizations(params = {}) {
  const qs = buildQuery({
    page: params.page ?? 0,
    size: params.size ?? 100,
    sort: params.sort ?? 'name,asc',
    search: params.search,
    status: params.status,
  });
  return apiRequest(`/organizations?${qs}`);
}

async function getOrganization(organizationId) {
  return apiRequest(`/organizations/${organizationId}`);
}

async function createOrganization(payload) {
  return apiRequest('/organizations', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

async function updateOrganization(organizationId, payload) {
  return apiRequest(`/organizations/${organizationId}`, {
    method: 'PATCH',
    body: JSON.stringify(payload),
  });
}

async function synchronizeOrganization(organizationId) {
  return apiRequest(`/organizations/${organizationId}/synchronize`, {
    method: 'POST',
  });
}

// --- Claims ---

async function getClaims(params = {}) {
  const qs = buildQuery({
    page: params.page ?? 0,
    size: params.size ?? 50,
    sort: params.sort ?? 'createdAt,desc',
    status: params.status,
    search: params.search,
  });
  return apiRequest(`/claims?${qs}`);
}

async function getClaim(claimId) {
  return apiRequest(`/claims/${claimId}`);
}

async function createClaim(payload) {
  return apiRequest('/claims', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

async function updateClaim(claimId, payload) {
  return apiRequest(`/claims/${claimId}`, {
    method: 'PATCH',
    body: JSON.stringify(payload),
  });
}

async function claimAction(claimId, action, reason) {
  const body = reason ? JSON.stringify({ reason }) : undefined;
  return apiRequest(`/claims/${claimId}/${action}`, {
    method: 'POST',
    body,
  });
}

async function getClaimStatusHistory(claimId) {
  return apiRequest(`/claims/${claimId}/status-history`);
}

async function getClaimComments(claimId) {
  return apiRequest(`/claims/${claimId}/comments`);
}

async function addClaimComment(claimId, text) {
  return apiRequest(`/claims/${claimId}/comments`, {
    method: 'POST',
    body: JSON.stringify({ text }),
  });
}

async function getClaimCalculation(claimId) {
  return apiRequest(`/calculations/claim/${claimId}`);
}

async function recalculateClaim(claimId) {
  return apiRequest(`/calculations/claim/${claimId}/recalculate`, { method: 'POST' });
}

async function generateClaimText(claimId) {
  return apiRequest(`/claims/${claimId}/generate`, { method: 'POST' });
}

async function getClaimVersions(claimId) {
  return apiRequest(`/claims/${claimId}/versions`);
}

async function createClaimVersion(claimId, payload) {
  return apiRequest(`/claims/${claimId}/versions`, {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

async function markClaimVersionFinal(claimId, versionId) {
  return apiRequest(`/claims/${claimId}/versions/${versionId}/final`, {
    method: 'POST',
  });
}

// --- Claim master data ---

async function getParties(params = {}) {
  const qs = buildQuery({ page: params.page ?? 0, size: params.size ?? 100, type: params.type });
  return apiRequest(`/parties?${qs}`);
}

async function getParty(partyId) {
  return apiRequest(`/parties/${partyId}`);
}

async function createParty(payload) {
  return apiRequest('/parties', { method: 'POST', body: JSON.stringify(payload) });
}

async function getContracts(params = {}) {
  const qs = buildQuery({ page: params.page ?? 0, size: params.size ?? 100 });
  return apiRequest(`/contracts?${qs}`);
}

async function getContract(contractId) {
  return apiRequest(`/contracts/${contractId}`);
}

async function createContract(payload) {
  return apiRequest('/contracts', { method: 'POST', body: JSON.stringify(payload) });
}

async function getShipments(params = {}) {
  const qs = buildQuery({ page: params.page ?? 0, size: params.size ?? 100 });
  return apiRequest(`/shipments?${qs}`);
}

async function getShipment(shipmentId) {
  return apiRequest(`/shipments/${shipmentId}`);
}

async function createShipment(payload) {
  return apiRequest('/shipments', { method: 'POST', body: JSON.stringify(payload) });
}

// --- Payments ---

async function getPayments(params = {}) {
  const qs = buildQuery({
    page: params.page ?? 0,
    size: params.size ?? 100,
    sort: params.sort ?? 'paymentDate,desc',
  });
  return apiRequest(`/payments?${qs}`);
}

async function getPayment(paymentId) {
  return apiRequest(`/payments/${paymentId}`);
}

async function createPayment(payload) {
  return apiRequest('/payments', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

async function importPayments(source, file) {
  const endpoints = {
    ONE_C: '/payments/import/1c',
    BANK_STATEMENT: '/payments/import/bank-statement',
  };
  const endpoint = endpoints[source];
  if (!endpoint) throw new Error('Выберите источник импорта');

  const body = new FormData();
  body.append('file', file);
  return apiRequest(endpoint, {
    method: 'POST',
    body,
  });
}

async function getClaimPayments(claimId) {
  return apiRequest(`/payments/claims/${claimId}`);
}

async function reconcilePayments() {
  return apiRequest('/payments/reconcile', { method: 'POST' });
}

async function preflightClaimPayment(claimId, comment = '') {
  const qs = buildQuery({ comment });
  return apiRequest(`/payments/claims/${claimId}/preflight-check${qs ? `?${qs}` : ''}`, {
    method: 'POST',
  });
}

async function markClaimPaidByPayment(claimId, paymentId, comment = '') {
  return apiRequest(`/payments/claims/${claimId}/mark-paid`, {
    method: 'POST',
    body: JSON.stringify({ paymentId, comment }),
  });
}

// --- Document templates and generated files ---

async function getAvailableClaimTemplates(claimType, clientId) {
  const qs = buildQuery({ claimType, clientId });
  return apiRequest(`/document-templates/available?${qs}`);
}

async function previewClaimTemplate(
  templateId,
  data = {},
  templateVersionId = null
) {
  return apiRequest(`/document-templates/${templateId}/preview`, {
    method: 'POST',
    body: JSON.stringify({ templateVersionId, data }),
  });
}

async function getClaimDocuments(claimId) {
  const qs = buildQuery({ entityType: 'CLAIM', entityId: claimId, size: 100 });
  return apiRequest(`/documents?${qs}`);
}

async function generateClaimDocument(payload) {
  return apiRequest('/documents/generate/claim', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

async function sendDocumentEmail(documentId, payload) {
  return apiRequest(`/documents/${documentId}/send-email`, {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

async function getDocumentDeliveries(documentId) {
  return apiRequest(`/documents/${documentId}/deliveries`);
}

async function downloadDocument(documentId) {
  const response = await fetch(`${API_BASE}/documents/${documentId}/download`, {
    headers: { Authorization: `Bearer ${getAccessToken()}` },
  });
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(error.message || `Ошибка ${response.status}`);
  }
  const blob = await response.blob();
  const disposition = response.headers.get('Content-Disposition') || '';
  const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  const plain = disposition.match(/filename="?([^";]+)"?/i)?.[1];
  const filename = encoded ? decodeURIComponent(encoded) : (plain || `document-${documentId}`);
  const link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(link.href);
}

function redirectByRole(roles) {
  const routes = {
    LAWYER: '/pages/lawyer/claims.html',
    ACCOUNTANT: '/pages/accountant/dashboard.html',
    EXPEDITOR_ADMIN: '/pages/admin/admin_dashboard.html',
    SUPER_ADMIN: '/pages/superadmin/superadmin_dashboard.html',
  };
  window.location.href = routes[roles?.[0]] || '/pages/lawyer/claims.html';
}

function requireAuth() {
  if (!getAccessToken()) {
    window.location.href = '/pages/authorization/login.html';
    return false;
  }
  return true;
}
