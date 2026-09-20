package app.modules.sqlworkbench.service;

import app.modules.sqlworkbench.dto.Dtos.DataSourceInfo;
import app.modules.sqlworkbench.dto.Dtos.DataSourceRequest;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Реестр зарегистрированных JDBC-датасорсов — порт com.sqleo.common.jdbc.ConnectionAssistant.
 * Хранит алиасы соединений (как ENTRY_ALIASES в оригинале) и выдаёт соединения по id.
 *
 * Каждый алиас оборачивается в лёгкий {@link DriverDataSource}, который открывает
 * соединение через DriverManager-совместимый драйвер по URL/login/password.
 */
@Service("sqlworkbenchDataSourceRegistry")
public class DataSourceRegistry {

    /** Метаданные алиаса + ленивый DataSource. */
    private record Entry(DataSourceRequest cfg, DataSource ds) {}

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public DataSourceInfo register(DataSourceRequest req) {
        Objects.requireNonNull(req.id(), "id is required");
        DriverDataSource ds = new DriverDataSource(
                req.driverClass(), req.url(), req.username(), req.password());
        entries.put(req.id(), new Entry(req, ds));
        return toInfo(req.id());
    }

    /**
     * Регистрация алиаса поверх уже существующего {@link DataSource} (например, пула
     * хоста). Используется мостом {@code HostDataSourceRegistrar}, чтобы модуль сразу
     * видел реальную БД SpringBootCRM без ручного ввода JDBC-кредов. Соединения берутся из
     * пула хоста, а не через {@link DriverDataSource}.
     */
    public DataSourceInfo register(DataSourceRequest req, DataSource existing) {
        Objects.requireNonNull(req.id(), "id is required");
        Objects.requireNonNull(existing, "DataSource is required");
        entries.put(req.id(), new Entry(req, existing));
        return toInfo(req.id());
    }

    public void remove(String id) { entries.remove(id); }

    public List<DataSourceInfo> list() {
        List<DataSourceInfo> out = new ArrayList<>();
        for (String id : entries.keySet()) out.add(toInfo(id));
        out.sort(Comparator.comparing(DataSourceInfo::id));
        return out;
    }

    public boolean exists(String id) { return entries.containsKey(id); }

    public boolean isReadOnly(String id) {
        Entry e = entries.get(id);
        return e != null && e.cfg().readOnly();
    }

    public Connection connection(String id) {
        Entry e = require(id);
        try {
            Connection c = e.ds().getConnection();
            if (e.cfg().readOnly()) c.setReadOnly(true);
            return c;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not open the connection '" + id + "': " + ex.getMessage(), ex);
        }
    }

    /** Проверка соединения (аналог кнопки "Test" в MaskDatasource). */
    public boolean test(String id) {
        try (Connection c = connection(id)) {
            return c.isValid(5);
        } catch (Exception ex) {
            return false;
        }
    }

    private DataSourceInfo toInfo(String id) {
        Entry e = require(id);
        DataSourceRequest c = e.cfg();
        boolean ok;
        try (Connection con = e.ds().getConnection()) { ok = con.isValid(3); }
        catch (Exception ex) { ok = false; }
        return new DataSourceInfo(c.id(), c.name(), c.driverClass(), c.url(),
                c.username(), c.readOnly(), ok);
    }

    private Entry require(String id) {
        Entry e = entries.get(id);
        if (e == null) throw new NoSuchElementException("Data source not found: " + id);
        return e;
    }

    /** Минимальный DataSource поверх JDBC-драйвера (без пула — по одному соединению на запрос). */
    static final class DriverDataSource implements DataSource {
        private final String url, user, pwd;
        DriverDataSource(String driverClass, String url, String user, String pwd) {
            this.url = url; this.user = user; this.pwd = pwd;
            if (driverClass != null && !driverClass.isBlank()) {
                try { Class.forName(driverClass); }
                catch (ClassNotFoundException e) {
                    throw new IllegalArgumentException("JDBC driver not found on the classpath: " + driverClass, e);
                }
            }
        }
        @Override public Connection getConnection() throws java.sql.SQLException {
            return java.sql.DriverManager.getConnection(url, user, pwd);
        }
        @Override public Connection getConnection(String u, String p) throws java.sql.SQLException {
            return java.sql.DriverManager.getConnection(url, u, p);
        }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
