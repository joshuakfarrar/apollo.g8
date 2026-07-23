@echo off
setlocal

:: Drops ONE named database after explicit confirmation. Nothing else
:: is touched -- no roles, no other databases. Run initialize-database.bat
:: afterwards to start fresh.

set /p DBHOST="PostgreSQL host [localhost]: "
if "%DBHOST%"=="" set DBHOST=localhost
set /p DBPORT="PostgreSQL port [5432]: "
if "%DBPORT%"=="" set DBPORT=5432
set /p ADMINUSER="Admin user to connect as [postgres]: "
if "%ADMINUSER%"=="" set ADMINUSER=postgres
set /p PGPASSWORD="Password for %ADMINUSER% (leave blank if none needed): "
set /p DBNAME="Database to DROP: "
if "%DBNAME%"=="" (
    echo No database name given; nothing dropped.
    exit /b 1
)

echo WARNING: This permanently deletes ALL data in '%DBNAME%'.
set /p CONFIRM="Type the database name again to confirm: "
if not "%CONFIRM%"=="%DBNAME%" (
    echo Names did not match; nothing dropped.
    exit /b 1
)

psql -h %DBHOST% -p %DBPORT% -U %ADMINUSER% -d postgres -v ON_ERROR_STOP=1 -c "DROP DATABASE ""%DBNAME%"""
if errorlevel 1 exit /b 1
echo Dropped database '%DBNAME%'.
