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
        fullName: "Иванов Иван Иванович",
        email: "ivanov@trans-exp.ru",
        role: "Юрист",
        roleClass: "role-pill--lawyer",
        status: "Активен",
        statusClass: "status-pill--active",
      },
      {
        id: "u2",
        fullName: "Соколова Мария Андреевна",
        email: "sokolova@trans-exp.ru",
        role: "Бухгалтер",
        roleClass: "role-pill--accountant",
        status: "Активен",
        statusClass: "status-pill--active",
      },
      {
        id: "u3",
        fullName: "Громова Елена Викторовна",
        email: "gromova@trans-exp.ru",
        role: "Юрист",
        roleClass: "role-pill--lawyer",
        status: "Активен",
        statusClass: "status-pill--active",
      },
      {
        id: "u4",
        fullName: "Петров Петр Петрович",
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
        fullName: "Орлов Дмитрий Сергеевич",
        email: "orlov@logplus.ru",
        role: "Юрист",
        roleClass: "role-pill--lawyer",
        status: "Активен",
        statusClass: "status-pill--active",
      },
      {
        id: "u6",
        fullName: "Краснова Анна Павловна",
        email: "krasnova@logplus.ru",
        role: "Бухгалтер",
        roleClass: "role-pill--accountant",
        status: "Активен",
        statusClass: "status-pill--active",
      },
      {
        id: "u7",
        fullName: "Фёдоров Игорь Николаевич",
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
        fullName: "Власова Татьяна Игоревна",
        email: "vlasova@magistral.ru",
        role: "Юрист",
        roleClass: "role-pill--lawyer",
        status: "Активен",
        statusClass: "status-pill--active",
      },
      {
        id: "u9",
        fullName: "Зайцев Константин Юрьевич",
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
