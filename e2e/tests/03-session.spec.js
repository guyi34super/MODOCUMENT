// Smoke: logging out ends the session on the server, not just in the tab.
import { test, expect } from '../lib/fixtures.js';
import { ApiClient, signIn } from '../lib/api.js';
import { getAccount } from '../lib/accounts.js';
import { OPERATOR, operator } from '../lib/seed.js';

test.describe('session @smoke', () => {
  test('SMK-SES-01 "Log out" revokes the access token server-side', async ({ page }) => {
    // A dedicated session (not the cached one other specs reuse) for the
    // operator: no MFA owed, so this spec does not depend on BUG-01.
    await operator();
    const email = OPERATOR();
    const { token, user } = await signIn(email, getAccount(email).password);
    expect((await new ApiClient({ token }).request('GET', '/api/auth/me')).status).toBe(200);

    await page.goto('/login');
    await page.evaluate(
      ([t, u]) => {
        localStorage.setItem('modoc.token', t);
        localStorage.setItem('modoc.user', JSON.stringify(u));
      },
      [token, user]
    );
    await page.goto('/');
    await expect(page.getByRole('button', { name: 'Log out' })).toBeVisible();

    await page.getByRole('button', { name: 'Log out' }).click();
    await expect(page).toHaveURL(/\/login$/);
    expect(await page.evaluate(() => localStorage.getItem('modoc.token'))).toBeNull();

    // The jti is revoked: the very same bearer is refused afterwards.
    expect((await new ApiClient({ token }).request('GET', '/api/auth/me')).status).toBe(401);
    // And the protected pages bounce to the login screen.
    await page.goto('/templates');
    await expect(page).toHaveURL(/\/login$/);
  });
});
