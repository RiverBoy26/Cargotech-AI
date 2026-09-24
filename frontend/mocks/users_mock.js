// ========================================
// Моковые данные — пользователи по экспедиторам
// ========================================
// Структура: массив экспедиторов, у каждого — массив users.
// Админ экспедитора видит только ОДИН из этих объектов (своего).
// Суперадмин видит все.

const expeditors = [
  {
    id: "exp-1",
    name: "ООО «ТрансЭкспедитор»",
    users: [
      {
        id: "u1",
        lastName: "Иванов", firstName: "Иван", middleName: "Иванович",
        email: "ivanov@trans-exp.ru",
        role: "Юрист",
        roleClass: "role-pill--lawyer",
        status: "Активен",
        statusClass: "status-pill--active",
      },
      {
        id: "u2",
        lastName: "Соколова", firstName: "Мария", middleName: "Андреевна",
        email: "sokolova@trans-exp.ru",
        role: "Бухгалтер",
        roleClass: "role-pill--accountant",
        status: "Активен",
        statusClass: "status-pill--active",
      },
      {
        id: "u3",
        lastName: "Громова", firstName: "Елена", middleName: "Викторовна",
        email: "gromova@trans-exp.ru",
        role: "Юрист",
        roleClass: "role-pill--lawyer",
        status: "Активен",
        statusClass: "status-pill--active",
      },
      {
        id: "u4",
        lastName: "Петров", firstName: "Петр", middleName: "Петрович",
        email: "petrov@trans-exp.ru",
        role: "Бухгалтер",
        roleClass: "role-pill--accountant",
        status: "Заблокирован",
        statusClass: "status-pill--blocked",
      },
    ],
  },
  {
    id: "exp-2",
    name: "ИП Логистика Плюс",
    users: [
      {
        id: "u5",
        lastName: "Орлов", firstName: "Дмитрий", middleName: "Сергеевич",
        email: "orlov@logplus.ru",
        role: "Юрист",
        roleClass: "role-pill--lawyer",
        status: "Активен",
        statusClass: "status-pill--active",
      },
      {
        id: "u6",
        lastName: "Краснова", firstName: "Анна", middleName: "Павловна",
        email: "krasnova@logplus.ru",
        role: "Бухгалтер",
        roleClass: "role-pill--accountant",
        status: "Активен",
        statusClass: "status-pill--active",
      },
      {
        id: "u7",
        lastName: "Фёдоров", firstName: "Игорь", middleName: "Николаевич",
        email: "fedorov@logplus.ru",
        role: "Администратор",
        roleClass: "role-pill--admin",
        status: "Активен",
        statusClass: "status-pill--active",
      },
    ],
  },
  {
    id: "exp-3",
    name: "ООО «Магистраль-Карго»",
    users: [
      {
        id: "u8",
        lastName: "Власова", firstName: "Татьяна", middleName: "Игоревна",
        email: "vlasova@magistral.ru",
        role: "Юрист",
        roleClass: "role-pill--lawyer",
        status: "Активен",
        statusClass: "status-pill--active",
      },
      {
        id: "u9",
        lastName: "Зайцев", firstName: "Константин", middleName: "Юрьевич",
        email: "zaitsev@magistral.ru",
        role: "Администратор",
        roleClass: "role-pill--admin",
        status: "Заблокирован",
        statusClass: "status-pill--blocked",
      },
    ],
  },
];

// Для страницы админа экспедитора — только его организация.
// В реальном приложении это придёт из токена авторизации (/auth/me).
// Пока берём первого экспедитора как "текущего".
const currentExpeditorUsers = expeditors[0].users;
