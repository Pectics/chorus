-- @context
-- refs: ARCHITECTURE.md, section 3.3
-- sync: Keep schema version, case-sensitive node IDs, and JDBC readers aligned.
-- @endcontext
-- Apply once to an empty database using administrator credentials.
CREATE TABLE chorus_registry_schema (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    version INTEGER NOT NULL CHECK (version = 1)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4;

CREATE TABLE chorus_servers (
    node_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    display_name TEXT NOT NULL,
    host TEXT NOT NULL,
    port INTEGER NOT NULL CHECK (port BETWEEN 1 AND 65535)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4;

INSERT INTO chorus_registry_schema (singleton, version) VALUES (1, 1);
