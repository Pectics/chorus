-- @context
-- refs: ARCHITECTURE.md, section 3.3
-- sync: Keep schema version, case-sensitive node IDs, and JDBC readers aligned.
-- @endcontext
-- Apply once to an empty database using administrator credentials.
CREATE TABLE public.chorus_registry_schema (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    version INTEGER NOT NULL CHECK (version = 1)
);

CREATE TABLE public.chorus_servers (
    node_id VARCHAR(128) COLLATE "C" PRIMARY KEY,
    display_name TEXT NOT NULL,
    host TEXT NOT NULL,
    port INTEGER NOT NULL CHECK (port BETWEEN 1 AND 65535)
);

INSERT INTO public.chorus_registry_schema (singleton, version) VALUES (1, 1);
