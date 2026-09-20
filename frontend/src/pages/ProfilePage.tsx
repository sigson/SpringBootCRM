import { useState } from "react";
import { useAuth, isAdmin } from "../auth/AuthProvider";
import { usersApi } from "../api/endpoints";
import { Alert, FormField, PageHead } from "../components/Common";
import { useApiErrorHandler } from "../components/useApiErrorHandler";
import { useDisplayResolver } from "../metadata/DisplayResolver";
import { ACCESS_ROLE_TYPE_ID } from "../editors/index";

/**
 * Сторінка профілю користувача.
 *
 * <p>Помилки сервера (валідація email, довжини пароля) показуються через
 * центральну ErrorDialog-модалку. Inline-помилки під
 * полями ставляться з {@code envelope.fieldErrors}. Success-Alert лишений —
 * це позитивний feedback, не помилка.
 */
export function ProfilePage() {
  const { user, refresh } = useAuth();
  const handleApiError = useApiErrorHandler();
  const resolver = useDisplayResolver();

  const [email, setEmail] = useState(user?.email ?? "");
  const [displayName, setDisplayName] = useState(user?.displayName ?? "");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [success, setSuccess] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  if (!user) return null;

  async function onSubmit() {
    setSubmitting(true);
    setSuccess(null);
    setFieldErrors({});
    try {
      await usersApi.patchSelf({
        email: email.trim(),
        displayName: displayName.trim(),
        password: password || undefined,
      });
      await refresh();
      setPassword("");
      setSuccess("Profile saved");
    } catch (err) {
      const { fieldErrors: fe } = handleApiError(err);
      setFieldErrors(fe);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="page">
      <PageHead title="User profile"
                subtitle="Editing your own data" />

      <div className="card" style={{ maxWidth: 540 }}>
        {success && <Alert kind="success">{success}</Alert>}

        <div className="form-grid">
          <FormField label="Code" value={user.code ?? ""} onChange={() => {}}
                     readOnly description="Catalog code - a stable identifier" />
          <FormField label="Name" value={user.name ?? ""} onChange={() => {}}
                     readOnly description="Only an administrator can change this" />
          <FormField label="Username" value={user.username} onChange={() => {}}
                     readOnly description="The username cannot be changed after creation" />
          <FormField label="Email" type="email" value={email}
                     onChange={v => { setEmail(v); setFieldErrors(s => { const n = {...s}; delete n.email; return n; }); }}
                     error={fieldErrors.email} />
          <FormField label="Full name" value={displayName}
                     onChange={v => { setDisplayName(v); setFieldErrors(s => { const n = {...s}; delete n.displayName; return n; }); }}
                     error={fieldErrors.displayName} />
          <FormField label="New password" type="password"
                     value={password} onChange={v => { setPassword(v); setFieldErrors(s => { const n = {...s}; delete n.password; return n; }); }}
                     description="Leave empty to keep it unchanged. Minimum 6 characters."
                     error={fieldErrors.password} />
        </div>

        <div className="hflex" style={{ marginTop: 16, justifyContent: "space-between" }}>
          <div className="muted" style={{ fontSize: 11 }}>
            ID: <span className="mono">{user.id}</span>
            {isAdmin(user) && <span className="tag tag--admin" style={{ marginLeft: 8 }}>Admin</span>}
          </div>
          <button className="btn btn--primary" onClick={() => void onSubmit()}
                  disabled={submitting}>
            {submitting ? "Saving…" : "Save"}
          </button>
        </div>
      </div>

      {user.roleId && (
        <div className="card" style={{ marginTop: 16, maxWidth: 540 }}>
          <div className="section-label" style={{ margin: 0, marginBottom: 8 }}>
            Assigned role
          </div>
          <div>
            <span className="tag" title={user.roleId}>
              {resolver.displayOf(ACCESS_ROLE_TYPE_ID, user.roleId) ?? user.roleId}
            </span>
          </div>
        </div>
      )}
    </main>
  );
}
