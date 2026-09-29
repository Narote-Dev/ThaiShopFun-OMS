-- Testcontainers only. The password exists so the app can log in as oms_app.
-- It is not a production secret. Flyway V1 keeps LOGIN when the role already exists.
CREATE ROLE oms_app LOGIN PASSWORD 'oms-app-test-only'
  NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
