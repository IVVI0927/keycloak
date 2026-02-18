package org.keycloak.testsuite.model;

import org.junit.Test;

import static org.junit.Assert.*;

public class HotRodServerRuleCoverageTest {

    @Test
    public void createEmbeddedHotRodServer_runsAndCleansUp() {
        HotRodServerRule rule = new HotRodServerRule();

        // Use Config to create a scope with the expected prefix. need a non-null Scope.
        Config cfg = new Config(() -> true);
        var scope = cfg.scope("hotrod");
        
        try {
            // execute most of the red lines in HotRodServerRule
            rule.createEmbeddedHotRodServer(scope);

            // Minimal assertions: if it started, these getters should be usable
            assertNotNull(rule.getHotRodServer());
            assertNotNull(rule.getRemoteCacheManager());

            // Optional: touch a couple more methods for method coverage
            assertNotNull(rule.streamCacheManagers());
        } finally {
            // !Always cleanup to prevent port/resource leak
            rule.after();
        }
    }
}
