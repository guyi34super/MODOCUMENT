import { useEffect, useState } from 'react';
import { Navigate, useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { Button, Field, inputClass } from '../components/ui';
import { useToast } from '../context/ToastContext';
import * as authApi from '../api/auth';

export default function LoginPage() {
  const { isAuthenticated, login } = useAuth();
  const navigate = useNavigate();
  const toast = useToast();
  const [searchParams] = useSearchParams();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  // OIDC single sign-on buttons: one per configured provider; the endpoint
  // is public and answers [] when no OIDC is configured (then: password only).
  const [providers, setProviders] = useState([]);

  useEffect(() => {
    let active = true;
    authApi
      .providers()
      .then((list) => {
        if (active) setProviders(Array.isArray(list) ? list : []);
      })
      .catch(() => {
        if (active) setProviders([]);
      });
    return () => {
      active = false;
    };
  }, []);

  // The backend redirects here with ?error=oidc on any SSO failure —
  // deliberately without detail (it lives in the server log + audit trail).
  const oidcError = searchParams.get('error') === 'oidc';

  if (isAuthenticated) return <Navigate to="/" replace />;

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    try {
      await toast.promise(login(email, password), { loading: 'Signing in…' });
      navigate('/');
    } catch {
      // The error toast is already shown.
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-sidebar px-4">
      <div className="w-full max-w-sm rounded-lg border border-line bg-white p-8">
        <div className="mb-6 flex items-center gap-3">
          <div className="flex size-10 items-center justify-center rounded border-2 border-ink text-lg font-black">
            MO
          </div>
          <div className="leading-tight">
            <p className="font-bold">MoDocument</p>
            <p className="text-xs text-muted">
              <span className="font-semibold text-ink">intelligence</span> that works
            </p>
          </div>
        </div>
        <h1 className="mb-4 text-xl font-normal">Sign in</h1>
        {oidcError && (
          <p className="mb-4 rounded border border-line bg-danger-bg px-3 py-2 text-sm text-danger-ink">
            Single sign-on failed. Try again, or contact your administrator.
          </p>
        )}
        <form onSubmit={submit} className="space-y-4">
          <Field label="Email">
            <input
              type="email"
              required
              autoFocus
              className={inputClass}
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
          </Field>
          <Field label="Password">
            <input
              type="password"
              required
              className={inputClass}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </Field>
          <Button type="submit" disabled={busy} className="w-full justify-center">
            {busy ? 'Signing in…' : 'Sign in'}
          </Button>
        </form>
        {providers.length > 0 && (
          <div className="mt-4 space-y-2">
            <div className="flex items-center gap-3">
              <span className="h-px flex-1 bg-line" />
              <span className="text-xs text-muted">or</span>
              <span className="h-px flex-1 bg-line" />
            </div>
            {providers.map((provider) => (
              // A real navigation on purpose: the OIDC handshake is a full
              // browser redirect to the IdP, not an XHR.
              <a
                key={provider.id}
                href={`/oauth2/authorization/${provider.id}`}
                className="inline-flex w-full cursor-pointer items-center justify-center gap-1.5 rounded border border-line bg-white px-4 py-1.5 text-sm font-medium text-accent transition-colors hover:bg-pill/50"
              >
                Sign in with {provider.name}
              </a>
            ))}
          </div>
        )}
        <p className="mt-4 text-sm text-muted">
          No account? Ask your administrator to create one.
        </p>
      </div>
    </div>
  );
}
