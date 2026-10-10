import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AuthProvider } from '../context/AuthContext';
import { ToastProvider } from '../context/ToastContext';
import { getToken } from '../api/client';
import LoginPage from './LoginPage';

// The QR renderer pulls in canvas internals jsdom does not provide; the
// data-URL output is all the page consumes. A plain function (not vi.fn) so
// the global afterEach restoreAllMocks cannot strip its implementation.
vi.mock('qrcode', () => ({
  default: { toDataURL: () => Promise.resolve('data:image/png;base64,QR') },
}));

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function renderLogin(initialEntry = '/login') {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <AuthProvider>
        <ToastProvider>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/" element={<div>DASHBOARD</div>} />
          </Routes>
        </ToastProvider>
      </AuthProvider>
    </MemoryRouter>
  );
}

/**
 * Routes the mocked fetch by URL: the page always asks /api/auth/providers on
 * mount (SSO buttons), while /api/auth/login answers with `loginResponse`.
 */
function stubFetch({ providers = [], loginResponse }) {
  const fetchMock = vi.fn((url) => {
    if (url === '/api/auth/providers') return Promise.resolve(jsonResponse(providers));
    return Promise.resolve(loginResponse);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

/**
 * Like stubFetch, but every non-provider call answers from a fixed queue —
 * the shape the multi-step MFA tests (ws06) need. The providers fetch the
 * page fires on mount (ws07) is answered deterministically with [] so it
 * never consumes a queued response.
 */
function stubFetchQueue(...responses) {
  const queue = [...responses];
  const fetchMock = vi.fn((url) => {
    if (url === '/api/auth/providers') return Promise.resolve(jsonResponse([]));
    return Promise.resolve(queue.shift());
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

/** The mock's calls without the page's own /api/auth/providers fetch. */
function apiCalls(fetchMock) {
  return fetchMock.mock.calls.filter(([url]) => url !== '/api/auth/providers');
}

describe('LoginPage', () => {
  it('signs in and navigates to the dashboard', async () => {
    const user = { userId: 1, email: 'demo@modocument.local', displayName: 'Demo User' };
    const fetchMock = stubFetch({ loginResponse: jsonResponse({ token: 'jwt-1', user }) });

    renderLogin();
    await userEvent.type(screen.getByLabelText('Email'), 'demo@modocument.local');
    await userEvent.type(screen.getByLabelText('Password'), 'Demo123!');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByText('DASHBOARD')).toBeInTheDocument();
    expect(getToken()).toBe('jwt-1');

    const loginCall = fetchMock.mock.calls.find(([url]) => url === '/api/auth/login');
    expect(loginCall).toBeTruthy();
    expect(JSON.parse(loginCall[1].body)).toEqual({
      email: 'demo@modocument.local',
      password: 'Demo123!',
    });
  });

  it('shows the backend error on bad credentials and stays on the page', async () => {
    stubFetch({ loginResponse: jsonResponse({ error: 'Invalid email or password.' }, 401) });

    renderLogin();
    await userEvent.type(screen.getByLabelText('Email'), 'demo@modocument.local');
    await userEvent.type(screen.getByLabelText('Password'), 'wrong-pass');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByText('Invalid email or password.')).toBeInTheDocument();
    expect(screen.queryByText('DASHBOARD')).not.toBeInTheDocument();
    expect(getToken()).toBeNull();
  });

  it('redirects straight to the dashboard when already signed in', () => {
    stubFetch({});
    localStorage.setItem('modoc.token', 'jwt-existing');
    localStorage.setItem('modoc.user', JSON.stringify({ userId: 1 }));

    renderLogin();
    expect(screen.getByText('DASHBOARD')).toBeInTheDocument();
  });

  // ------------------------------------------------- two-step login (ws06)

  it('walks the two-step MFA login: challenge, 6-digit code, tokens', async () => {
    const user = { userId: 1, email: 'demo@modocument.local', displayName: 'Demo User' };
    const fetchMock = stubFetchQueue(
      jsonResponse({ mfaRequired: true, enrollmentRequired: false, challenge: 'challenge-jwt' }),
      jsonResponse({ token: 'jwt-mfa', user })
    );

    renderLogin();
    await userEvent.type(screen.getByLabelText('Email'), 'demo@modocument.local');
    await userEvent.type(screen.getByLabelText('Password'), 'Demo123!');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    // Phase 1 bought only a challenge: no token stored, code step shown.
    expect(await screen.findByText('Two-factor authentication')).toBeInTheDocument();
    expect(getToken()).toBeNull();

    await userEvent.type(screen.getByLabelText('Verification code'), '123456');
    await userEvent.click(screen.getByRole('button', { name: 'Verify' }));

    expect(await screen.findByText('DASHBOARD')).toBeInTheDocument();
    expect(getToken()).toBe('jwt-mfa');

    const [verifyUrl, verifyOpts] = apiCalls(fetchMock)[1];
    expect(verifyUrl).toBe('/api/auth/mfa/verify');
    expect(JSON.parse(verifyOpts.body)).toEqual({ challenge: 'challenge-jwt', code: '123456' });
  });

  it('a wrong code surfaces the backend error and keeps the session empty', async () => {
    stubFetchQueue(
      jsonResponse({ mfaRequired: true, enrollmentRequired: false, challenge: 'challenge-jwt' }),
      jsonResponse({ error: 'Invalid verification code.' }, 401)
    );

    renderLogin();
    await userEvent.type(screen.getByLabelText('Email'), 'demo@modocument.local');
    await userEvent.type(screen.getByLabelText('Password'), 'Demo123!');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    await userEvent.type(await screen.findByLabelText('Verification code'), '000000');
    await userEvent.click(screen.getByRole('button', { name: 'Verify' }));

    expect(await screen.findByText('Invalid verification code.')).toBeInTheDocument();
    expect(screen.queryByText('DASHBOARD')).not.toBeInTheDocument();
    expect(getToken()).toBeNull();
  });

  it('the recovery-code toggle switches the input and sends the recovery code', async () => {
    const user = { userId: 1, email: 'demo@modocument.local' };
    const fetchMock = stubFetchQueue(
      jsonResponse({ mfaRequired: true, enrollmentRequired: false, challenge: 'challenge-jwt' }),
      jsonResponse({ token: 'jwt-rec', user })
    );

    renderLogin();
    await userEvent.type(screen.getByLabelText('Email'), 'demo@modocument.local');
    await userEvent.type(screen.getByLabelText('Password'), 'Demo123!');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    await userEvent.click(await screen.findByRole('button', { name: 'Use a recovery code' }));
    await userEvent.type(screen.getByLabelText('Recovery code'), 'ABCDE-FGHJK');
    await userEvent.click(screen.getByRole('button', { name: 'Verify' }));

    expect(await screen.findByText('DASHBOARD')).toBeInTheDocument();
    expect(JSON.parse(apiCalls(fetchMock)[1][1].body)).toEqual({
      challenge: 'challenge-jwt',
      code: 'ABCDE-FGHJK',
    });
  });

  it('forced enrolment shows secret, QR and recovery codes, then activates and signs in', async () => {
    const user = { userId: 2, email: 'admin@modocument.local', displayName: 'Admin' };
    const enrollment = {
      secret: 'JBSWY3DPEHPK3PXP',
      otpauthUri: 'otpauth://totp/MoDocument%3Aadmin?secret=JBSWY3DPEHPK3PXP&issuer=MoDocument',
      recoveryCodes: ['AAAAA-BBBBB', 'CCCCC-DDDDD'],
    };
    const fetchMock = stubFetchQueue(
      // login → challenge with enrolment forced
      jsonResponse({ mfaRequired: true, enrollmentRequired: true, challenge: 'challenge-jwt' }),
      // enroll (authenticated by the challenge token)
      jsonResponse(enrollment),
      // activate
      new Response(null, { status: 204 }),
      // verify → tokens
      jsonResponse({ token: 'jwt-admin', user })
    );

    renderLogin();
    await userEvent.type(screen.getByLabelText('Email'), 'admin@modocument.local');
    await userEvent.type(screen.getByLabelText('Password'), 'Demo123!');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    // The enrolment screen shows everything exactly once.
    expect(await screen.findByText('Set up two-factor authentication')).toBeInTheDocument();
    expect(screen.getByText('JBSWY3DPEHPK3PXP')).toBeInTheDocument();
    expect(screen.getByText('AAAAA-BBBBB')).toBeInTheDocument();
    expect(screen.getByText('CCCCC-DDDDD')).toBeInTheDocument();
    expect(await screen.findByAltText('QR code for authenticator app')).toBeInTheDocument();
    // The enroll call carried the challenge token, not a session token.
    expect(apiCalls(fetchMock)[1][0]).toBe('/api/auth/mfa/enroll');
    expect(apiCalls(fetchMock)[1][1].headers.Authorization).toBe('Bearer challenge-jwt');

    await userEvent.click(screen.getByRole('button', { name: 'I saved my recovery codes' }));
    await userEvent.type(screen.getByLabelText('Verification code'), '654321');
    await userEvent.click(screen.getByRole('button', { name: 'Activate and sign in' }));

    expect(await screen.findByText('DASHBOARD')).toBeInTheDocument();
    expect(getToken()).toBe('jwt-admin');
    // activate then verify, both with the same first code.
    expect(apiCalls(fetchMock)[2][0]).toBe('/api/auth/mfa/activate');
    expect(JSON.parse(apiCalls(fetchMock)[2][1].body)).toEqual({ code: '654321' });
    expect(apiCalls(fetchMock)[3][0]).toBe('/api/auth/mfa/verify');
    expect(JSON.parse(apiCalls(fetchMock)[3][1].body)).toEqual({
      challenge: 'challenge-jwt',
      code: '654321',
    });
  });

  // ---------------------------------------------- OIDC providers (ws07)

  it('shows a sign-in link per configured OIDC provider', async () => {
    stubFetch({
      providers: [
        { id: 'entra', name: 'Microsoft' },
        { id: 'keycloak', name: 'Keycloak' },
      ],
    });

    renderLogin();

    const microsoft = await screen.findByRole('link', { name: 'Sign in with Microsoft' });
    expect(microsoft).toHaveAttribute('href', '/oauth2/authorization/entra');
    const keycloak = screen.getByRole('link', { name: 'Sign in with Keycloak' });
    expect(keycloak).toHaveAttribute('href', '/oauth2/authorization/keycloak');
    // The password form stays as the break-glass path.
    expect(screen.getByLabelText('Email')).toBeInTheDocument();
  });

  it('shows no provider buttons when no OIDC is configured', async () => {
    stubFetch({ providers: [] });

    renderLogin();

    // The providers call resolves before this settles.
    expect(await screen.findByLabelText('Email')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Sign in with/ })).not.toBeInTheDocument();
  });

  it('shows an opaque SSO error banner when redirected with ?error=oidc', async () => {
    stubFetch({ providers: [{ id: 'keycloak', name: 'Keycloak' }] });

    renderLogin('/login?error=oidc');

    expect(
      await screen.findByText(/Single sign-on failed/)
    ).toBeInTheDocument();
  });

  // ------------------------------------------- session expiry (SUP-S02)

  it('shows the session-expired banner when redirected with ?expired=1', async () => {
    stubFetch({ providers: [] });

    renderLogin('/login?expired=1');

    expect(
      await screen.findByText('Your session expired. Please sign in again.')
    ).toBeInTheDocument();
    // Still a normal login form underneath the banner.
    expect(screen.getByLabelText('Email')).toBeInTheDocument();
  });

  it('shows no expiry banner on a plain /login visit', async () => {
    stubFetch({ providers: [] });

    renderLogin();

    expect(await screen.findByLabelText('Email')).toBeInTheDocument();
    expect(screen.queryByText(/session expired/i)).not.toBeInTheDocument();
  });

  it('BUG-02: after a password change it says so neutrally, without the expiry banner', async () => {
    stubFetch({ providers: [] });

    renderLogin({ pathname: '/login', state: { notice: 'password-changed' } });

    expect(
      await screen.findByText('Password changed — sign in again with your new password.')
    ).toBeInTheDocument();
    expect(screen.queryByText(/session expired/i)).not.toBeInTheDocument();
  });
});
