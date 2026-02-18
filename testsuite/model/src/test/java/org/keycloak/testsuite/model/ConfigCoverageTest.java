package org.keycloak.testsuite.model;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

public class ConfigCoverageTest {

    // Avoid leaking system properties across tests
    @After
    public void cleanup() {
        System.clearProperty("keycloak.test.some.scope.someKey");
        System.clearProperty("keycloak.test.empty.scope.someKey");
    }

    @Test
    public void reset_whenUseGlobalTrue_clearsDefaultProperties() {
        // useGlobal = true => reset() should clear defaultProperties
        Config cfg = new Config(() -> true);

        // Put something into global/default config map
        cfg.spi("test").config("k1", "v1");

        // Sanity check: value can be resolved
        assertEquals("v1", cfg.scope("test").get("k1"));

        // Act
        cfg.reset();

        // After reset, it should be removed (defaultProperties cleared)
        assertNull(cfg.scope("test").get("k1"));
    }

    @Test
    public void reset_whenUseGlobalFalse_removesThreadLocalProperties() {
        // useGlobal = false => config stored in ThreadLocal map; reset() removes it
        Config cfg = new Config(() -> false);

        cfg.spi("test").config("k1", "v1");
        assertEquals("v1", cfg.scope("test").get("k1"));

        cfg.reset();

        // ThreadLocal map removed => value gone
        assertNull(cfg.scope("test").get("k1"));
    }

    @Test
    public void spiConfig_config_valueNonNull_putsValue() {
        Config cfg = new Config(() -> true);

        cfg.spi("alpha").config("k", "v");

        // Key becomes prefix + key internally; we validate via scope lookup
        assertEquals("v", cfg.scope("alpha").get("k"));
    }

    @Test
    public void spiConfig_config_valueNull_removesValue() {
        Config cfg = new Config(() -> true);

        cfg.spi("alpha").config("k", "v");
        assertEquals("v", cfg.scope("alpha").get("k"));

        // value == null => remove branch
        cfg.spi("alpha").config("k", null);

        assertNull(cfg.scope("alpha").get("k"));
    }

    @Test
    public void providerConfig_config_putAndRemove_andCallProviderAndSpi() {
        Config cfg = new Config(() -> true);

        // Exercise provider(String) and ProviderConfig.config(...) branches
        Config.ProviderConfig pc = cfg.spi("beta").provider("myProvider");
        assertNotNull(pc);

        pc.config("k", "v");
        assertEquals("v", cfg.scope("beta", "myProvider").get("k"));

        pc.config("k", null); // remove branch
        assertNull(cfg.scope("beta", "myProvider").get("k"));

        // exercise ProviderConfig.spi(String)
        Config.SpiConfig sc = pc.spi("gamma");
        assertNotNull(sc);
        sc.config("x", "y");
        assertEquals("y", cfg.scope("gamma").get("x"));
    }

    @Test
    public void mapConfigScope_get_prefersConfigValue_whenNonEmpty() {
        Config cfg = new Config(() -> true);

        // Put a value into config map
        cfg.spi("test.some.scope").config("someKey", "fromConfig");

        // Even if system property is set, config should win
        System.setProperty("keycloak.test.some.scope.someKey", "fromSystem");

        String v = cfg.scope("test", "some", "scope").get("someKey");
        assertEquals("fromConfig", v);
    }

    @Test
    public void mapConfigScope_get_fallsBackToSystemProperty_whenConfigMissingOrEmpty() {
        Config cfg = new Config(() -> true);

        // No value in config => should read from System.getProperty("keycloak.<prefix+key>")
        System.setProperty("keycloak.test.some.scope.someKey", "fromSystem");

        String v = cfg.scope("test", "some", "scope").get("someKey");
        assertEquals("fromSystem", v);
    }

    @Test
    public void mapConfigScope_get_returnsNull_whenNoConfigAndNoSystemProperty() {
        Config cfg = new Config(() -> true);

        // Ensure system property not set
        System.clearProperty("keycloak.test.some.scope.someKey");

        String v = cfg.scope("test", "some", "scope").get("someKey");
        assertNull(v);
    }

    @Test
    public void mapConfigScope_get_treatsEmptyAsMissing_andUsesFallbackRule() {
        Config cfg = new Config(() -> true);

        // Put empty in config (triggers v == null || v.isEmpty() branch)
        cfg.spi("test.empty.scope").config("someKey", "");

        System.setProperty("keycloak.test.empty.scope.someKey", "fallback");

        String v = cfg.scope("test", "empty", "scope").get("someKey");
        assertEquals("fallback", v);
    }
}
