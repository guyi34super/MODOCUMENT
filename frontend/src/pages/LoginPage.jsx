import { useEffect, useState } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import QRCode from 'qrcode';
import { useAuth } from '../context/AuthContext';
import { activateMfa, enrollMfa } from '../api/auth';
import { Button, Field, inputClass } from '../components/ui';
import { useToast } from '../context/ToastContext';

/**
 * Sign-in, in up to three steps (ws06):
 *  1. credentials — email + password;
 *  2. code — when the account has MFA, a 6-digit TOTP (or recovery) code
 *     converts the login challenge into a session;
 *  3. enroll — an ADMIN without MFA is forced through enrolment first:
 *     secret/QR/recovery codes, an "I saved them" confirm, then the first
 *     code activates MFA and completes the login in one go.
 */
export default function LoginPage() {
  const { isAuthenticated, login, completeMfaLogin } = useAuth();
  const navigate = useNavigate();
  const toast = useToast();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);

  // 'credentials' | 'code' | 'enroll' | 'enroll-code'
  const [step, setStep] = useState('credentials');
  const [challenge, setChallenge] = useState('');
  const [code, setCode] = useState('');
  const [useRecovery, setUseRecovery] = useState(false);
  const [enrollment, setEnrollment] = useState(null);
  const [qrDataUrl, setQrDataUrl] = useState('');

  useEffect(() => {
    if (enrollment?.otpauthUri) {
      QRCode.toDataURL(enrollment.otpauthUri, { margin: 1, width: 192 })
        .then(setQrDataUrl)
        .catch(() => setQrDataUrl(''));
    }
  }, [enrollment]);

  if (isAuthenticated) return <Navigate to="/" replace />;

  const copy = async (text, what) => {
    try {
      await navigator.clipboard.writeText(text);
      toast.success(`${what} copied`);
    } catch {
      toast.error('Copy failed — select and copy the text manually.');
    }
  };

  const submitCredentials = async (e) => {
    e.preventDefault();
    setBusy(true);
    try {
      const result = await toast.promise(login(email, password), { loading: 'Signing in…' });
      if (!result.mfaRequired) {
        navigate('/');
        return;
      }
      setChallenge(result.challenge);
      if (result.enrollmentRequired) {
        // Forced enrolment (ADMIN): the challenge token authenticates it.
        const fresh = await toast.promise(enrollMfa(result.challenge), {
          loading: 'Preparing two-factor setup…',
        });
        setEnrollment(fresh);
        setStep('enroll');
      } else {
        setStep('code');
      }
    } catch {
      // The error toast is already shown.
    } finally {
      setBusy(false);
    }
  };

  const submitCode = async (e) => {
    e.preventDefault();
    setBusy(true);
    try {
      await toast.promise(completeMfaLogin(challenge, code.trim()), {
        loading: 'Verifying code…',
      });
      navigate('/');
    } catch {
      setCode('');
    } finally {
      setBusy(false);
    }
  };

  const submitEnrollCode = async (e) => {
    e.preventDefault();
    setBusy(true);
    try {
      await toast.promise(
        (async () => {
          const totp = code.trim();
          // Activation proves possession; the backend deliberately lets the
          // SAME first code finish the pending login right after.
          await activateMfa(totp, challenge);
          await completeMfaLogin(challenge, totp);
        })(),
        { loading: 'Activating two-factor authentication…' }
      );
      navigate('/');
    } catch {
      setCode('');
    } finally {
      setBusy(false);
    }
  };

  const backToCredentials = () => {
    setStep('credentials');
    setChallenge('');
    setCode('');
    setUseRecovery(false);
    setEnrollment(null);
    setQrDataUrl('');
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

        {step === 'credentials' && (
          <>
            <h1 className="mb-4 text-xl font-normal">Sign in</h1>
            <form onSubmit={submitCredentials} className="space-y-4">
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
            <p className="mt-4 text-sm text-muted">
              No account? Ask your administrator to create one.
            </p>
          </>
        )}

        {step === 'code' && (
          <>
            <h1 className="mb-1 text-xl font-normal">Two-factor authentication</h1>
            <p className="mb-4 text-sm text-muted">
              {useRecovery
                ? 'Enter one of your unused recovery codes.'
                : 'Enter the 6-digit code from your authenticator app.'}
            </p>
            <form onSubmit={submitCode} className="space-y-4">
              <Field label={useRecovery ? 'Recovery code' : 'Verification code'}>
                <input
                  required
                  autoFocus
                  autoComplete="one-time-code"
                  inputMode={useRecovery ? 'text' : 'numeric'}
                  pattern={useRecovery ? undefined : '[0-9]{6}'}
                  maxLength={useRecovery ? 32 : 6}
                  className={inputClass}
                  value={code}
                  onChange={(e) => setCode(e.target.value)}
                />
              </Field>
              <Button type="submit" disabled={busy} className="w-full justify-center">
                {busy ? 'Verifying…' : 'Verify'}
              </Button>
            </form>
            <div className="mt-4 flex items-center justify-between text-sm">
              <button
                type="button"
                className="cursor-pointer text-accent hover:underline"
                onClick={() => {
                  setUseRecovery(!useRecovery);
                  setCode('');
                }}
              >
                {useRecovery ? 'Use authenticator code' : 'Use a recovery code'}
              </button>
              <button
                type="button"
                className="cursor-pointer text-muted hover:underline"
                onClick={backToCredentials}
              >
                Back
              </button>
            </div>
          </>
        )}

        {step === 'enroll' && enrollment && (
          <>
            <h1 className="mb-1 text-xl font-normal">Set up two-factor authentication</h1>
            <p className="mb-4 text-sm text-muted">
              Administrator accounts require a second factor. Scan the QR code
              (or add the secret manually), and store the recovery codes
              somewhere safe — they are shown only once.
            </p>
            <div className="space-y-4 text-sm">
              {qrDataUrl && (
                <img
                  src={qrDataUrl}
                  alt="QR code for authenticator app"
                  className="mx-auto size-48 rounded border border-line"
                />
              )}
              <div>
                <p className="mb-1 font-medium">Secret</p>
                <div className="flex items-center gap-2">
                  <code className="break-all rounded bg-sidebar px-2 py-1 text-xs">
                    {enrollment.secret}
                  </code>
                  <Button
                    type="button"
                    variant="secondary"
                    size="sm"
                    onClick={() => copy(enrollment.secret, 'Secret')}
                  >
                    Copy
                  </Button>
                </div>
              </div>
              <div>
                <p className="mb-1 font-medium">otpauth URI</p>
                <div className="flex items-center gap-2">
                  <code className="break-all rounded bg-sidebar px-2 py-1 text-xs">
                    {enrollment.otpauthUri}
                  </code>
                  <Button
                    type="button"
                    variant="secondary"
                    size="sm"
                    onClick={() => copy(enrollment.otpauthUri, 'otpauth URI')}
                  >
                    Copy
                  </Button>
                </div>
              </div>
              <div>
                <p className="mb-1 font-medium">Recovery codes</p>
                <ul className="grid grid-cols-2 gap-1 rounded bg-sidebar p-2 font-mono text-xs">
                  {enrollment.recoveryCodes.map((recovery) => (
                    <li key={recovery}>{recovery}</li>
                  ))}
                </ul>
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  className="mt-2"
                  onClick={() => copy(enrollment.recoveryCodes.join('\n'), 'Recovery codes')}
                >
                  Copy all
                </Button>
              </div>
              <Button
                type="button"
                className="w-full justify-center"
                onClick={() => setStep('enroll-code')}
              >
                I saved my recovery codes
              </Button>
            </div>
          </>
        )}

        {step === 'enroll-code' && (
          <>
            <h1 className="mb-1 text-xl font-normal">Confirm your authenticator</h1>
            <p className="mb-4 text-sm text-muted">
              Enter the current 6-digit code from your authenticator app to
              activate two-factor authentication and finish signing in.
            </p>
            <form onSubmit={submitEnrollCode} className="space-y-4">
              <Field label="Verification code">
                <input
                  required
                  autoFocus
                  autoComplete="one-time-code"
                  inputMode="numeric"
                  pattern="[0-9]{6}"
                  maxLength={6}
                  className={inputClass}
                  value={code}
                  onChange={(e) => setCode(e.target.value)}
                />
              </Field>
              <Button type="submit" disabled={busy} className="w-full justify-center">
                {busy ? 'Activating…' : 'Activate and sign in'}
              </Button>
            </form>
            <button
              type="button"
              className="mt-4 cursor-pointer text-sm text-muted hover:underline"
              onClick={() => setStep('enroll')}
            >
              Back to the setup details
            </button>
          </>
        )}
      </div>
    </div>
  );
}
