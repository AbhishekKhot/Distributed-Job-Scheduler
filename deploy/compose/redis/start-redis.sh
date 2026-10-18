#!/bin/sh
# Starts Redis with an ACL file generated from the environment, so the password never appears
# in the container command line or in a committed file.
#
#   default  disabled
#   kairos   may only touch kairos:* keys and cannot run administrative commands
#
# noeviction is deliberate: evicting a run lock or a sent marker would break the
# no-duplicate-execution guarantee, so Redis must reject writes when it is full instead.
set -eu

: "${KAIROS_REDIS_PASSWORD:?KAIROS_REDIS_PASSWORD is required}"
case "$KAIROS_REDIS_PASSWORD" in
  *[!A-Za-z0-9]*) echo "KAIROS_REDIS_PASSWORD must be alphanumeric" >&2; exit 1 ;;
esac
if [ "${#KAIROS_REDIS_PASSWORD}" -lt 32 ]; then
  echo "KAIROS_REDIS_PASSWORD must be at least 32 characters" >&2
  exit 1
fi

umask 077
acl_file=/tmp/users.acl
printf 'user default off\nuser kairos on >%s ~kairos:* &kairos:* +@all -@dangerous +info\n' \
  "$KAIROS_REDIS_PASSWORD" > "$acl_file"

exec redis-server \
  --aclfile "$acl_file" \
  --protected-mode yes \
  --appendonly yes \
  --appendfsync everysec \
  --dir /data \
  --maxmemory 384mb \
  --maxmemory-policy noeviction
