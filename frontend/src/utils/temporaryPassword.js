/** No 0/O, 1/l/I lookalikes: the password is read out or typed by a person. */
const PASSWORD_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789';

/**
 * A temporary password for an admin-created account or a reset: four
 * hyphenated groups of four random characters (~90 bits, 19 chars) from the
 * browser's CSPRNG. The user must replace it at the first sign-in.
 */
export function generateTemporaryPassword() {
  const random = new Uint32Array(16);
  crypto.getRandomValues(random);
  let password = '';
  random.forEach((value, i) => {
    if (i > 0 && i % 4 === 0) password += '-';
    password += PASSWORD_ALPHABET[value % PASSWORD_ALPHABET.length];
  });
  return password;
}
