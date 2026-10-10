#!/bin/bash
# Pin the TEST-ONLY host keys (mounted read-only at /keys) before sshd starts:
# atmoz/sftp generates random host keys first and runs /etc/sftp.d/*.sh
# afterwards, so overwrite them here with owner-only permissions (git does not
# keep 0600, and sshd refuses group/world-readable host keys).
set -eu
for type in ed25519 rsa; do
  install -m 600 -o root -g root "/keys/ssh_host_${type}_key.TEST-ONLY" "/etc/ssh/ssh_host_${type}_key"
  install -m 644 -o root -g root "/keys/ssh_host_${type}_key.TEST-ONLY.pub" "/etc/ssh/ssh_host_${type}_key.pub"
done
