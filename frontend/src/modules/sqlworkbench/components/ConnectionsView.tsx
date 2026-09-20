import { useState } from "react";
import { useSpringBootCrm } from "../proxy/WorkbenchContext";
import { useWorkbench } from "./Workbench";
import type { DataSourceRequest } from "../types";

const DRIVERS = [
  { label: "H2 (in-memory)", driverClass: "org.h2.Driver", url: "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1" },
  { label: "PostgreSQL", driverClass: "org.postgresql.Driver", url: "jdbc:postgresql://localhost:5432/db" },
  { label: "MariaDB/MySQL", driverClass: "org.mariadb.jdbc.Driver", url: "jdbc:mariadb://localhost:3306/db" },
];

/**
 * Управление JDBC-датасорсами.
 *
 * Вёрстка — сетка из двух равных половин (.rdr-cols-2): слева — список
 * зарегистрированных соединений, справа — форма нового соединения. На узких
 * экранах колонки складываются.
 */
export function ConnectionsView() {
  const { client, can } = useSpringBootCrm();
  const { datasources, refreshDataSources } = useWorkbench();
  const [form, setForm] = useState<DataSourceRequest>({
    id: "", name: "", driverClass: DRIVERS[1].driverClass,
    url: DRIVERS[1].url, username: "", password: "", readOnly: false,
  });
  const [msg, setMsg] = useState<string | null>(null);
  const manage = can("MANAGE_DATASOURCES");

  const submit = async () => {
    try {
      await client.registerDataSource(form);
      setMsg(`Data source «${form.name || form.id}» saved`);
      refreshDataSources();
    } catch (e) { setMsg(String((e as Error).message)); }
  };
  const test = async (id: string) => {
    const r = await client.testDataSource(id);
    setMsg(`Test «${id}»: ${r.ok ? "OK" : "is not responding"}`);
  };
  const remove = async (id: string) => {
    if (!confirm(`Delete data source «${id}»?`)) return;
    await client.removeDataSource(id); refreshDataSources();
  };

  return (
    <div className="rdr-panel">
      <div className="rdr-cols-2" style={!manage ? { gridTemplateColumns: "1fr" } : undefined}>
        {}
        <section className="rdr-card">
          <div className="rdr-card__head">Registered connections</div>
          <div className="rdr-card__body">
            <div className="rdr-grid-wrap" style={{ flex: "none" }}>
              <table className="rdr-grid">
                <thead>
                  <tr><th>ID</th><th>Name</th><th>URL</th><th>RO</th><th>Status</th><th></th></tr>
                </thead>
                <tbody>
                  {datasources.map((d) => (
                    <tr key={d.id}>
                      <td>{d.id}</td><td>{d.name}</td>
                      <td className="mono">{d.url}</td>
                      <td>{d.readOnly ? "yes" : "—"}</td>
                      <td>{d.connected ? <span className="ok">● connected</span> : <span className="off">○ no</span>}</td>
                      <td>
                        <div className="rdr-actions">
                          <button className="btn btn--small" onClick={() => test(d.id)}>test</button>
                          {manage && <button className="btn btn--small btn--danger" onClick={() => remove(d.id)}>delete</button>}
                        </div>
                      </td>
                    </tr>
                  ))}
                  {datasources.length === 0 && <tr><td className="empty" colSpan={6}>No connections</td></tr>}
                </tbody>
              </table>
            </div>
          </div>
        </section>

        {}
        {manage && (
          <section className="rdr-card">
            <div className="rdr-card__head">New connection</div>
            <div className="rdr-card__body">
              <div className="rdr-form">
                <label>Driver</label>
                <select className="rdr-select" onChange={(e) => {
                  const d = DRIVERS[+e.target.value];
                  setForm({ ...form, driverClass: d.driverClass, url: d.url });
                }}>
                  {DRIVERS.map((d, i) => <option key={i} value={i}>{d.label}</option>)}
                </select>

                <label>ID</label>
                <input className="rdr-input" value={form.id} onChange={(e) => setForm({ ...form, id: e.target.value })} placeholder="prod-pg" />

                <label>Name</label>
                <input className="rdr-input" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="Production PG" />

                <label>JDBC URL</label>
                <input className="rdr-input mono" value={form.url} onChange={(e) => setForm({ ...form, url: e.target.value })} />

                <label>Driver class</label>
                <input className="rdr-input mono" value={form.driverClass} onChange={(e) => setForm({ ...form, driverClass: e.target.value })} />

                <label>User</label>
                <input className="rdr-input" value={form.username} onChange={(e) => setForm({ ...form, username: e.target.value })} />

                <label>Password</label>
                <input className="rdr-input" type="password" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} />

                <label>Access</label>
                <label className="rdr-check">
                  <input type="checkbox" checked={form.readOnly} onChange={(e) => setForm({ ...form, readOnly: e.target.checked })} /> read-only
                </label>

                <div className="rdr-form__full">
                  <button className="btn btn--primary" onClick={submit}>Save</button>
                </div>
              </div>
            </div>
          </section>
        )}
      </div>
      {msg && <div className="rdr-toast">{msg}</div>}
    </div>
  );
}
