@echo off
setlocal
cd /d "%~dp0"

:: Sets up a NEW PostgreSQL database and user for this app.
:: This script only ever CREATEs -- it refuses to touch an existing
:: database, and never changes an existing role's password.

echo This sets up a NEW PostgreSQL database and user for $name$.
echo Existing databases are never modified or dropped.
echo.

set /p DBHOST="PostgreSQL host [localhost]: "
if "%DBHOST%"=="" set DBHOST=localhost
set /p DBPORT="PostgreSQL port [5432]: "
if "%DBPORT%"=="" set DBPORT=5432
set /p ADMINUSER="Admin user to connect as [postgres]: "
if "%ADMINUSER%"=="" set ADMINUSER=postgres
set /p PGPASSWORD="Password for %ADMINUSER% (leave blank if none needed): "
set /p DBNAME="New database name [$name$]: "
if "%DBNAME%"=="" set DBNAME=$name$
set /p DBUSER="Database user for the app [$name$]: "
if "%DBUSER%"=="" set DBUSER=$name$

:: No -v ON_ERROR_STOP=1 here: cmd's for /f re-parsing splits on the "="
:: and psql then misreads every argument after it.
set PSQL_ADMIN=psql -h %DBHOST% -p %DBPORT% -U %ADMINUSER% -d postgres

set DBEXISTS=
for /f "usebackq delims=" %%i in (`%PSQL_ADMIN% -tAc "SELECT 1 FROM pg_database WHERE datname='%DBNAME%'"`) do set DBEXISTS=%%i
if "%DBEXISTS%"=="1" (
    echo Database '%DBNAME%' already exists -- refusing to touch it.
    echo Pick a different name, or drop it first with reset-database.bat.
    exit /b 1
)

set ROLEEXISTS=
for /f "usebackq delims=" %%i in (`%PSQL_ADMIN% -tAc "SELECT 1 FROM pg_roles WHERE rolname='%DBUSER%'"`) do set ROLEEXISTS=%%i
if "%ROLEEXISTS%"=="1" goto password_existing

set /p DBPASS="Password for new user %DBUSER% (leave blank to generate one): "
if not "%DBPASS%"=="" goto create_role
for /f "usebackq delims=" %%i in (`powershell -NoProfile -Command "[guid]::NewGuid().ToString()"`) do set DBPASS=%%i
echo Generated a random password.

:create_role
set DBPASS_SQL=%DBPASS:'=''%
%PSQL_ADMIN% -q -c "CREATE ROLE ""%DBUSER%"" LOGIN PASSWORD '%DBPASS_SQL%'"
if errorlevel 1 exit /b 1
goto create_db

:password_existing
echo Role '%DBUSER%' already exists -- reusing it. Its password will not be changed.
set /p DBPASS="Password for existing user %DBUSER%: "

:create_db
%PSQL_ADMIN% -q -c "CREATE DATABASE ""%DBNAME%"" OWNER ""%DBUSER%"""
if errorlevel 1 exit /b 1
echo Created database '%DBNAME%' owned by '%DBUSER%'.

set PGPASSWORD=%DBPASS%
for %%f in (000-create-users.sql 001-create-confirmations.sql 002-create-sessions.sql 003-create-resets.sql) do (
    psql -h %DBHOST% -p %DBPORT% -U %DBUSER% -d %DBNAME% -v ON_ERROR_STOP=1 -q -f "%%f"
    if errorlevel 1 exit /b 1
    echo Applied %%f
)

echo.
echo Done! Put these in webapp\resources\application.conf:
echo.
echo   sql-url="jdbc:postgresql://%DBHOST%:%DBPORT%/%DBNAME%"
echo   sql-username="%DBUSER%"
echo   sql-password="%DBPASS%"
