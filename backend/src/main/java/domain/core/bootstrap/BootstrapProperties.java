package domain.core.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Конфигурация bootstrap'а. */
@ConfigurationProperties(prefix = "app.ddd")
public class BootstrapProperties {

    private final Bootstrap bootstrap = new Bootstrap();
    private final Access access = new Access();
    private final RefPrefetch refprefetch = new RefPrefetch();

    public Bootstrap getBootstrap() { return bootstrap; }
    public Access    getAccess()    { return access; }
    public RefPrefetch getRefprefetch() { return refprefetch; }

    public static class Bootstrap {
        private List<String> scanPackages = List.of("domain", "app");

        public List<String> getScanPackages() { return scanPackages; }
        public void setScanPackages(List<String> v) { this.scanPackages = v; }
    }

    public static class Access {
        private boolean strictDefault = true;
        private boolean implicitPostLoadCheck = true;

        public boolean isStrictDefault() { return strictDefault; }
        public void setStrictDefault(boolean v) { this.strictDefault = v; }

        public boolean isImplicitPostLoadCheck() { return implicitPostLoadCheck; }
        public void setImplicitPostLoadCheck(boolean v) { this.implicitPostLoadCheck = v; }
    }

    public static class RefPrefetch {
        private int defaultDepth = 3;

        public int getDefaultDepth() { return defaultDepth; }
        public void setDefaultDepth(int v) { this.defaultDepth = v; }
    }
}
