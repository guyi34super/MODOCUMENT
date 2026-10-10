-- Refresh-token rotation + access-token revocation storage.
--
--  * RefreshToken — one row per issued refresh token. Only the SHA-256 hash of
--    the opaque value is stored (the value itself travels solely in an
--    httpOnly cookie). FamilyId ties a login's rotation chain together:
--    rotation marks the old row RevokedAt/ReplacedByJti, and presenting an
--    already-rotated token revokes the whole family (reuse detection).
--  * RevokedToken — jti denylist for access tokens (logout). Rows are only
--    meaningful until ExpiresAt (the token's own exp); anything older can be
--    swept by a later cleanup job.
--
-- SCHEMA PARITY: the test profile builds its H2 schema from the JPA entities
-- (RefreshToken/RevokedToken in com.modocument.domain.entity, ddl-auto:
-- create, Flyway disabled), so those entities must be kept in sync with this
-- file by hand.

CREATE TABLE RefreshToken (
    Jti           uuid PRIMARY KEY,
    UserId        integer NOT NULL REFERENCES AppUsers (AppUserId),
    FamilyId      uuid NOT NULL,
    TokenHash     varchar(64) NOT NULL,
    IssuedAt      timestamp(6) NOT NULL,
    ExpiresAt     timestamp(6) NOT NULL,
    RevokedAt     timestamp(6),
    ReplacedByJti uuid,
    Ip            varchar(64),
    UserAgent     varchar(400)
);
CREATE INDEX IF NOT EXISTS ix_refreshtoken_tokenhash ON RefreshToken (TokenHash);
CREATE INDEX IF NOT EXISTS ix_refreshtoken_userid ON RefreshToken (UserId);
CREATE INDEX IF NOT EXISTS ix_refreshtoken_familyid ON RefreshToken (FamilyId);

CREATE TABLE RevokedToken (
    Jti       uuid PRIMARY KEY,
    ExpiresAt timestamp(6) NOT NULL
);
