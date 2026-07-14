const form = document.getElementById('loginForm');
const errorEl = document.getElementById('error');
const submitBtn = form.querySelector('button[type="submit"]');

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  errorEl.textContent = '';

  const email = document.getElementById('username').value.trim();
  const password = document.getElementById('password').value;

  submitBtn.disabled = true;
  submitBtn.textContent = 'Вход...';

  try {
    const data = await login(email, password);
    redirectByRole(data.roles);
  } catch (err) {
    errorEl.textContent = err.message || 'Неверный email или пароль';
  } finally {
    submitBtn.disabled = false;
    submitBtn.textContent = 'Войти';
  }
});
