-- Flyway callback, run after every migrate. The default privileges of schema scheduler give the
-- application role DML on every table the owner creates, including Flyway's history table; the
-- services never need it, so it is taken away again.
REVOKE ALL ON TABLE scheduler.flyway_schema_history FROM "${appRole}";
