import { Routes, Route, Navigate } from "react-router-dom";
import { AuthProvider, ProtectedRoute } from "./auth/AuthProvider";
import { MetadataProvider } from "./metadata/MetadataProvider";
import { DisplayResolverProvider } from "./metadata/DisplayResolver";
import { WindowStackProvider } from "./windows/WindowStack";
import { ToastProvider } from "./components/Toast";
import { ConfirmProvider } from "./components/ConfirmDialog";
import { ErrorDialogProvider } from "./components/ErrorDialog";
import { TopNav, useThemeBootstrap } from "./components/Common";
import { NavProvider } from "./navigation/NavProvider";
import { SideNav } from "./navigation/SideNav";
import { LoginPage } from "./pages/LoginPage";
import { RegisterPage } from "./pages/RegisterPage";
import { DashboardPage } from "./pages/DashboardPage";
import { ProfilePage } from "./pages/ProfilePage";
import { FreeControllerPage } from "./pages/FreeControllerPage";
import { ObjectListPage } from "./pages/ObjectListPage";
import { initEditors } from "./editors";
import { initListViews } from "./editors/listViews";
import { initFreeControllers } from "./editors/freeControllers";

// Реєструємо доменні редактори, picker-конфіги, list-переозначення та
// представлення вільних контролерів один раз.
initEditors();
initListViews();
initFreeControllers();

/**
 * Корінь додатку.
 *
 * <p>Після повної генералізації майже всі об'єкти БД обслуговує єдиний маршрут
 * {@code /o/:slug} (generic {@code ObjectList} зі списком із метаданих + дрилл-даун
 * у згенероване вікно редагування). Окремими лишилися тільки:
 * курси валют (регістр з полем-датою), інструменти ({@code /tool/*}) та профіль.
 * Кастомні форми редагування мають лише Ролі та Інтерфейси.
 */
export default function App() {
  useThemeBootstrap();
  return (
    <AuthProvider>
      <ErrorDialogProvider>
        <MetadataProvider>
          <DisplayResolverProvider>
            <ToastProvider>
              <ConfirmProvider>
                <WindowStackProvider>
                  <NavProvider>
                    <AppRoutes />
                  </NavProvider>
                </WindowStackProvider>
              </ConfirmProvider>
            </ToastProvider>
          </DisplayResolverProvider>
        </MetadataProvider>
      </ErrorDialogProvider>
    </AuthProvider>
  );
}

function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />

      <Route path="/" element={<Protected><DashboardPage /></Protected>} />

      {/* Єдиний маршрут перегляду списку об'єкта БД (generic + дрилл-даун у модалку).
          Необов'язковий сегмент {@code :recordId} відображає відкритий <i>зі списку</i>
          редактор елемента (UUID або "new") — щоб посилання на конкретний запис
          можна було скопіювати/відкрити у новій вкладці. Дрилл-даун глибше (через
          ссилкові пікери) лишається суто модальним і в URL не потрапляє. */}
      <Route path="/o/:slug" element={<Protected><ObjectListPage /></Protected>} />
      <Route path="/o/:slug/:recordId" element={<Protected><ObjectListPage /></Protected>} />

      {/*
        Вільні контролери — один диспетчер по typeId. Маршрут не знає, який саме
        контролер за ним стоїть: FreeControllerPage резолвить тип за slug'ом,
        знаходить представлення за typeId (або віддає 404). Додати/прибрати
        контролер = оголосити тип на бекенді + (за потреби) зареєструвати хук;
        правок роутингу не треба.
      */}
      <Route path="/tool/:key" element={<Protected><FreeControllerPage /></Protected>} />

      <Route path="/profile" element={<Protected><ProfilePage /></Protected>} />

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

function Protected({ children }: { children: React.ReactNode }) {
  return (
    <ProtectedRoute>
      <TopNav />
      <div style={{ display: "flex", alignItems: "flex-start" }}>
        <SideNav />
        <div style={{ flex: 1, minWidth: 0 }}>{children}</div>
      </div>
    </ProtectedRoute>
  );
}
