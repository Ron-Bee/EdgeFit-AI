// EF-CLOUD-01A database adapter. The API layer speaks only
// { get, all, run, exec }: the same four methods a D1
// binding implements (modulo .bind()), so deployment swaps
// this file without touching validation, routes, or stats.
import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

export function openDatabase(filename = ":memory:") {
  const db = new DatabaseSync(filename);
  return {
    get(sql, ...params) {
      return db.prepare(sql).get(...params);
    },
    all(sql, ...params) {
      return db.prepare(sql).all(...params);
    },
    run(sql, ...params) {
      return db.prepare(sql).run(...params);
    },
    exec(sql) {
      return db.exec(sql);
    },
    close() {
      db.close();
    },
  };
}

export function initSchema(db) {
  const dir = dirname(fileURLToPath(import.meta.url));
  const ddl = readFileSync(join(dir, "schema.sql"), "utf8");
  db.exec(ddl);
}
