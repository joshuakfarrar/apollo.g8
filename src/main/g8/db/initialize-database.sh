#!/usr/bin/env bash
#
# Sets up a NEW PostgreSQL database and user for this app.
# This script only ever CREATEs — it refuses to touch an existing
# database, and never changes an existing role's password.
set -euo pipefail
cd "\$(dirname "\$0")"

echo "This sets up a NEW PostgreSQL database and user for $name$."
echo "Existing databases are never modified or dropped."
echo

read -rp "PostgreSQL host [localhost]: " DBHOST
DBHOST=\${DBHOST:-localhost}
read -rp "PostgreSQL port [5432]: " DBPORT
DBPORT=\${DBPORT:-5432}
read -rp "Admin user to connect as [postgres]: " ADMINUSER
ADMINUSER=\${ADMINUSER:-postgres}
read -rsp "Password for \$ADMINUSER (leave blank if none needed): " ADMINPASS
echo
read -rp "New database name [$name$]: " DBNAME
DBNAME=\${DBNAME:-$name$}
read -rp "Database user for the app [$name$]: " DBUSER
DBUSER=\${DBUSER:-$name$}

admin() {
  PGPASSWORD="\$ADMINPASS" psql -h "\$DBHOST" -p "\$DBPORT" -U "\$ADMINUSER" \
    -d postgres -v ON_ERROR_STOP=1 "\$@"
}

if [ "\$(admin -tAc "SELECT 1 FROM pg_database WHERE datname = '\$DBNAME'")" = "1" ]; then
  echo "Database '\$DBNAME' already exists — refusing to touch it." >&2
  echo "Pick a different name, or drop it first with reset-database.sh." >&2
  exit 1
fi

ROLE_EXISTS=\$(admin -tAc "SELECT 1 FROM pg_roles WHERE rolname = '\$DBUSER'")

if [ "\$ROLE_EXISTS" = "1" ]; then
  echo "Role '\$DBUSER' already exists — reusing it (its password will not be changed)."
  read -rsp "Password for existing user \$DBUSER: " DBPASS
  echo
else
  read -rsp "Password for new user \$DBUSER [$name$]: " DBPASS
  echo
  DBPASS=\${DBPASS:-$name$}
  DBPASS_SQL=\${DBPASS//"'"/"''"}
  admin -q -c "CREATE ROLE \"\$DBUSER\" LOGIN PASSWORD '\$DBPASS_SQL'"
fi

admin -q -c "CREATE DATABASE \"\$DBNAME\" OWNER \"\$DBUSER\""
echo "Created database '\$DBNAME' owned by '\$DBUSER'."

for f in 000-create-users.sql 001-create-confirmations.sql 002-create-sessions.sql 003-create-resets.sql; do
  PGPASSWORD="\$DBPASS" psql -h "\$DBHOST" -p "\$DBPORT" -U "\$DBUSER" \
    -d "\$DBNAME" -v ON_ERROR_STOP=1 -q -f "\$f"
  echo "Applied \$f"
done

echo
echo "Done! webapp/resources/application.conf already defaults to the"
echo "values below — edit it only if yours differ:"
echo
echo "  sql-url=\"jdbc:postgresql://\$DBHOST:\$DBPORT/\$DBNAME\""
echo "  sql-username=\"\$DBUSER\""
echo "  sql-password=\"\$DBPASS\""
