-- antiburn 诊断日志表（Cloudflare D1）
-- 部署后在 D1 控制台执行一次，或用 wrangler d1 execute <db> --file=schema.sql。

CREATE TABLE IF NOT EXISTS logs (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    ts           INTEGER NOT NULL,
    model        TEXT,
    brand        TEXT,
    manufacturer TEXT,
    device       TEXT,
    product      TEXT,
    release      TEXT,
    sdk          INTEGER,
    module       TEXT,
    module_code  INTEGER,
    abi          TEXT,
    ip           TEXT,
    log          TEXT
);

CREATE INDEX IF NOT EXISTS idx_logs_ts ON logs (ts);
CREATE INDEX IF NOT EXISTS idx_logs_code ON logs (module_code);
CREATE INDEX IF NOT EXISTS idx_logs_keep ON logs (brand, model, ip, ts);
