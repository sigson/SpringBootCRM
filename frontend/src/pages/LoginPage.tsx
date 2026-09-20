import { useState } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { Alert, FormField, extractError } from "../components/Common";
import { useFieldValidation, validators } from "../components/validation";

export function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const fromState = (location.state as { from?: string } | null);
  const redirectTo = fromState?.from ?? "/";

  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [touched, setTouched] = useState<Record<string, boolean>>({});

  // Real-time валідація: логін і пароль. Для входу не блокуємо
  // submit жорстко (існуючі логіни могли бути створені до правил формату) —
  // лише показуємо підказку. Порожні поля все одно блокують кнопку.
  const usernameRT = useFieldValidation(username, [validators.username()]);
  const passwordRT = useFieldValidation(password, [validators.password(6, 200)]);
  const usernameError = touched.username ? usernameRT : null;
  const passwordError = touched.password ? passwordRT : null;

  async function onSubmit() {
    setSubmitting(true);
    setError(null);
    try {
      await login(username.trim(), password);
      navigate(redirectTo, { replace: true });
    } catch (err) {
      setError(extractError(err).message);
    } finally {
      setSubmitting(false);
    }
  }

  function onKey(e: React.KeyboardEvent) {
    if (e.key === "Enter" && username && password) void onSubmit();
  }

  return (
    <div className="login-shell" onKeyDown={onKey}>
      <aside className="login-shell__aside">
        <div className="login-shell__pitch">
          <div className="brand-mark" style={{ color: "white", fontSize: 18 }}>
            <span className="brand-mark__logo" />
            <span>SpringBootCRM</span>
          </div>
          <h2 style={{ marginTop: 32 }}>
            Demo project.
          </h2>
          <p></p>
        </div>
        <div style={{ position: "relative", zIndex: 1, color: "rgba(255,255,255,.6)", fontSize: 11 }}>
          DEV-environment · admin:admin for testing
        </div>
      </aside>

      <div className="login-shell__main">
        <div className="login-card">
          <h1>Sign in</h1>
          <p>Enter your username and password</p>

          {error && <Alert kind="error">{error}</Alert>}

          <div className="form-grid">
            <FormField label="Username" value={username}
                       onChange={v => { setUsername(v); setTouched(t => ({ ...t, username: true })); }}
                       autoFocus required error={usernameError} />
            <FormField label="Password" type="password"
                       value={password}
                       onChange={v => { setPassword(v); setTouched(t => ({ ...t, password: true })); }}
                       required error={passwordError} />
          </div>

          <div className="hflex" style={{ marginTop: 16, justifyContent: "space-between" }}>
            <Link to="/register" className="muted" style={{ fontSize: 12 }}>
              Sign up
            </Link>
            <button
              className="btn btn--primary"
              onClick={() => void onSubmit()}
              disabled={submitting || !username || !password}
            >{submitting ? "Sign in…" : "Sign in"}</button>
          </div>
        </div>
      </div>
    </div>
  );
}
