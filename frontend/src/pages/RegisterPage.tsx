import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { FormField } from "../components/Common";
import { useApiErrorHandler } from "../components/useApiErrorHandler";
import { useFieldValidation, validators } from "../components/validation";

/**
 * Сторінка реєстрації.
 *
 * <p>Помилки сервера (валідація email, довжина пароля, конфлікт логіну)
 * показуються через центральну ErrorDialog-модалку.
 * Inline-помилки під полями підтримуються — backend повертає список
 * {@code fieldErrors[]}; hook {@code useApiErrorHandler} витягує їх у мапу.
 */
export function RegisterPage() {
  const { register } = useAuth();
  const navigate = useNavigate();
  const handleApiError = useApiErrorHandler();

  const [username, setUsername] = useState("");
  const [email, setEmail] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [touched, setTouched] = useState<Record<string, boolean>>({});

  // Real-time валідація: логін, пароль, email.
  const usernameRT = useFieldValidation(username, [validators.username()]);
  const emailRT    = useFieldValidation(email,    [validators.email()]);
  const passwordRT = useFieldValidation(password, [validators.password(6, 200)]);

  // Показуємо помилку поля, лише якщо воно вже «торкнуте» або є серверна помилка.
  const usernameError = fieldErrors.username ?? (touched.username ? usernameRT : null);
  const emailError    = fieldErrors.email    ?? (touched.email ? emailRT : null);
  const passwordError = fieldErrors.password ?? (touched.password ? passwordRT : null);

  function markTouched(name: string) {
    setTouched(t => (t[name] ? t : { ...t, [name]: true }));
  }

  async function onSubmit() {
    setSubmitting(true);
    setFieldErrors({});
    try {
      await register(username.trim(), email.trim(), displayName.trim(), password);
      navigate("/", { replace: true });
    } catch (err) {
      const { fieldErrors: fe } = handleApiError(err);
      setFieldErrors(fe);
    } finally {
      setSubmitting(false);
    }
  }

  const canSubmit =
    username.trim() !== "" && password !== "" &&
    !usernameRT && !emailRT && !passwordRT;

  return (
    <div className="login-shell">
      <aside className="login-shell__aside">
        <div className="login-shell__pitch">
          <div className="brand-mark" style={{ color: "white", fontSize: 18 }}>
            <span className="brand-mark__logo" />
            <span>SpringBootCRM</span>
          </div>
          <h2 style={{ marginTop: 32 }}>Create an account</h2>
          <p>Basic rights without administrative permissions. An admin will assign roles later.</p>
        </div>
      </aside>

      <div className="login-shell__main">
        <div className="login-card">
          <h1>Sign up</h1>
          <p>Fill in the form to create an account</p>

          <div className="form-grid">
            <FormField label="Username" value={username}
                       onChange={v => { setUsername(v); markTouched("username");
                                        setFieldErrors(s => { const n = {...s}; delete n.username; return n; }); }}
                       autoFocus required placeholder="alex.k"
                       error={usernameError} />
            <FormField label="Full name" value={displayName}
                       onChange={setDisplayName} placeholder="Alex Morgan"
                       error={fieldErrors.displayName} />
            <FormField label="Email" type="email" value={email}
                       onChange={v => { setEmail(v); markTouched("email");
                                        setFieldErrors(s => { const n = {...s}; delete n.email; return n; }); }}
                       placeholder="user@example.com"
                       error={emailError} />
            <FormField label="Password" type="password" value={password}
                       onChange={v => { setPassword(v); markTouched("password");
                                        setFieldErrors(s => { const n = {...s}; delete n.password; return n; }); }}
                       required
                       description="minimum 6 characters"
                       error={passwordError} />
          </div>

          <div className="hflex" style={{ marginTop: 16, justifyContent: "space-between" }}>
            <Link to="/login" className="muted" style={{ fontSize: 12 }}>
              Already have an account? Sign in
            </Link>
            <button className="btn btn--primary"
                    onClick={() => void onSubmit()}
                    disabled={submitting || !canSubmit}>
              {submitting ? "Sign up…" : "Sign up"}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
