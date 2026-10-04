-- ws06 (MFA + password policy): Argon2id hashes and login lockout columns.
--
--  * PasswordHash widens to 255: new hashes are DelegatingPasswordEncoder
--    output ("{argon2}$argon2id$v=19$m=16384,t=2,p=1$<salt>$<hash>", ~105
--    chars); old raw BCrypt hashes (60 chars) keep matching and are
--    re-hashed to Argon2id on the next successful login (SEC-018).
--  * FailedLoginCount — consecutive wrong-password attempts since the last
--    successful login (or the last lock). The attempt after the configured
--    allowance (default 5, so the 6th) locks the account.
--  * LockedUntil — while set and in the future, every login attempt answers
--    the same generic 401 BEFORE the password is verified (a dummy hash is
--    still burned so the timing stays uniform).
--  * LockoutCount — how many times the account has locked since its last
--    successful login; the lock duration doubles with each one
--    (1, 2, 4, ... minutes, capped at 30) and a successful login resets it.
--
-- SCHEMA PARITY: the test profile builds its H2 schema from the JPA entities
-- (ddl-auto: create, Flyway disabled), so the AppUser entity must be kept in
-- sync with this file by hand.

ALTER TABLE AppUsers ALTER COLUMN PasswordHash TYPE varchar(255);
ALTER TABLE AppUsers ADD COLUMN FailedLoginCount integer NOT NULL DEFAULT 0;
ALTER TABLE AppUsers ADD COLUMN LockedUntil timestamp(6);
ALTER TABLE AppUsers ADD COLUMN LockoutCount integer NOT NULL DEFAULT 0;
