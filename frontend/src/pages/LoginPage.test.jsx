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

function renderLogin() {
  return render(
    <MemoryRouter initialEntries={['/login']}>
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

describe('LoginPage', () => {
  it('signs in and navigates to the dashboard', async () => {
    const user = { userId: 1, email: 'demo@modocument.local', displayName: 'Demo User' };
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ token: 'jwt-1', user }));
    vi.stubGlobal('fetch', fetchMock);

    renderLogin();
    await userEvent.type(screen.getByLabelText('Email'), 'demo@modocument.local');
    await userEvent.type(screen.getByLabelText('Password'), 'Demo123!');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByText('DASHBOARD')).toBeInTheDocument();
    expect(getToken()).toBe('jwt-1');

    const [url, opts] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/auth/login');
    expect(JSON.parse(opts.body)).toEqual({
      email: 'demo@modocument.local',
      password: 'Demo123!',
    });
  });

  it('shows the backend error on bad credentials and stays on the page', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse({ error: 'Invalid email or password.' }, 401))
    );

    renderLogin();
    await userEvent.type(screen.getByLabelText('Email'), 'demo@modocument.local');
    await userEvent.type(screen.getByLabelText('Password'), 'wrong-pass');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByText('Invalid email or password.')).toBeInTheDocument();
    expect(screen.queryByText('DASHBOARD')).not.toBeInTheDocument();
    expect(getToken()).toBeNull();
  });

  it('redirects straight to the dashboard when already signed in', () => {
    localStorage.setItem('modoc.token', 'jwt-existing');
    localStorage.setItem('modoc.user', JSON.stringify({ userId: 1 }));

    renderLogin();
    expect(screen.getByText('DASHBOARD')).toBeInTheDocument();
  });

  // ------------------------------------------------- two-step login (ws06)

  it('walks the two-step MFA login: challenge, 6-digit code, tokens', async () => {
    const user = { userId: 1, email: 'demo@modocument.local', displayName: 'Demo User' };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse({ mfaRequired: true, enrollmentRequired: false, challenge: 'challenge-jwt' })
      )
      .mockResolvedValueOnce(jsonResponse({ token: 'jwt-mfa', user }));
    vi.stubGlobal('fetch', fetchMock);

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

    const [verifyUrl, verifyOpts] = fetchMock.mock.calls[1];
    expect(verifyUrl).toBe('/api/auth/mfa/verify');
    expect(JSON.parse(verifyOpts.body)).toEqual({ challenge: 'challenge-jwt', code: '123456' });
  });

  it('a wrong code surfaces the backend error and keeps the session empty', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse({ mfaRequired: true, enrollmentRequired: false, challenge: 'challenge-jwt' })
      )
      .mockResolvedValueOnce(jsonResponse({ error: 'Invalid verification code.' }, 401));
    vi.stubGlobal('fetch', fetchMock);

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
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse({ mfaRequired: true, enrollmentRequired: false, challenge: 'challenge-jwt' })
      )
      .mockResolvedValueOnce(jsonResponse({ token: 'jwt-rec', user }));
    vi.stubGlobal('fetch', fetchMock);

    renderLogin();
    await userEvent.type(screen.getByLabelText('Email'), 'demo@modocument.local');
    await userEvent.type(screen.getByLabelText('Password'), 'Demo123!');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    await userEvent.click(await screen.findByRole('button', { name: 'Use a recovery code' }));
    await userEvent.type(screen.getByLabelText('Recovery code'), 'ABCDE-FGHJK');
    await userEvent.click(screen.getByRole('button', { name: 'Verify' }));

    expect(await screen.findByText('DASHBOARD')).toBeInTheDocument();
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({
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
    const fetchMock = vi
      .fn()
      // login → challenge with enrolment forced
      .mockResolvedValueOnce(
        jsonResponse({ mfaRequired: true, enrollmentRequired: true, challenge: 'challenge-jwt' })
      )
      // enroll (authenticated by the challenge token)
      .mockResolvedValueOnce(jsonResponse(enrollment))
      // activate
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      // verify → tokens
      .mockResolvedValueOnce(jsonResponse({ token: 'jwt-admin', user }));
    vi.stubGlobal('fetch', fetchMock);

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
    expect(fetchMock.mock.calls[1][0]).toBe('/api/auth/mfa/enroll');
    expect(fetchMock.mock.calls[1][1].headers.Authorization).toBe('Bearer challenge-jwt');

    await userEvent.click(screen.getByRole('button', { name: 'I saved my recovery codes' }));
    await userEvent.type(screen.getByLabelText('Verification code'), '654321');
    await userEvent.click(screen.getByRole('button', { name: 'Activate and sign in' }));

    expect(await screen.findByText('DASHBOARD')).toBeInTheDocument();
    expect(getToken()).toBe('jwt-admin');
    // activate then verify, both with the same first code.
    expect(fetchMock.mock.calls[2][0]).toBe('/api/auth/mfa/activate');
    expect(JSON.parse(fetchMock.mock.calls[2][1].body)).toEqual({ code: '654321' });
    expect(fetchMock.mock.calls[3][0]).toBe('/api/auth/mfa/verify');
    expect(JSON.parse(fetchMock.mock.calls[3][1].body)).toEqual({
      challenge: 'challenge-jwt',
      code: '654321',
    });
  });
});
