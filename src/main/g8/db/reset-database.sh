#!/usr/bin/env bash
#
# Drops ONE named database after explicit confirmation. Nothing else
# is touched — no roles, no other databases. Run initialize-database.sh
# afterwards to start fresh.
set -euo pipefail

read -rp "PostgreSQL host [localhost]: " DBHOST
DBHOST=\${DBHOST:-localhost}
read -rp "PostgreSQL port [5432]: " DBPORT
DBPORT=\${DBPORT:-5432}
read -rp "Admin user to connect as [postgres]: " ADMINUSER
ADMINUSER=\${ADMINUSER:-postgres}
read -rsp "Password for \$ADMINUSER (leave blank if none needed): " ADMINPASS
echo
read -rp "Database to DROP: " DBNAME
if [ -z "\$DBNAME" ]; then
  echo "No database name given; nothing dropped." >&2
  exit 1
fi

echo "⚠️  This permanently deletes ALL data in '\$DBNAME'."
read -rp "Type the database name again to confirm: " CONFIRM
if [ "\$CONFIRM" != "\$DBNAME" ]; then
  echo "Names did not match; nothing dropped." >&2
  exit 1
fi

PGPASSWORD="\$ADMINPASS" psql -h "\$DBHOST" -p "\$DBPORT" -U "\$ADMINUSER" \
  -d postgres -v ON_ERROR_STOP=1 -c "DROP DATABASE \"\$DBNAME\""
echo "Dropped database '\$DBNAME'."
