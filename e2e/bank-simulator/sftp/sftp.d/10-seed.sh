#!/bin/bash
# atmoz/sftp runs /etc/sftp.d/*.sh at start: lay out the bank's drop folders
# (TEST ONLY) and copy the inbound seed files in, owned by the SFTP user.
set -eu
user="${BANK_SIM_SFTP_USER:-bank}"
home="/home/$user"
for dir in inbound inbound/processed outbound; do
  mkdir -p "$home/$dir"
done
cp -n /seed/inbound/* "$home/inbound/" 2>/dev/null || true
chown -R "$user:users" "$home/inbound" "$home/outbound"
chmod 755 "$home/inbound" "$home/outbound"
