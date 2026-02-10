package org.keycloak.services.managers;

import org.junit.Assert;
import org.junit.Test;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserLoginFailureModel;
import org.keycloak.models.UserModel;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.models.UserLoginFailureProvider;
import org.keycloak.models.UserProvider;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

public class DefaultBruteForceProtectorModelTest {

    /**
     * Testable wrapper: we avoid the async/transaction scheduling in processLogin().
     * We directly call the protected failure()/success() methods which hold the core state logic.
     */
    private static class TestableProtector extends DefaultBruteForceProtector {
        TestableProtector() { super(null); }

        // Disable event side effects for unit tests
        @Override
        protected void sendEvent(KeycloakSession session, RealmModel realm, UserLoginFailureModel userLoginFailure, org.keycloak.events.EventType type) {
            // no-op
        }

        void fail(KeycloakSession session, RealmModel realm, String userId, String remoteAddr, long failureTimeMillis) {
            failure(session, realm, userId, remoteAddr, failureTimeMillis);
        }

        void succeed(KeycloakSession session, RealmModel realm, String userId) {
            success(session, realm, userId);
        }
    }

    private RealmModel realmFor3FailureLockout() {
        Map<String, Object> values = new HashMap<>();
        // configuration to lock on 3rd failure (brute force strategy configured on the realm)
        values.put("isPermanentLockout", false);
        values.put("getMaxTemporaryLockouts", 0);
        values.put("getMaxDeltaTimeSeconds", 60 * 60 * 12);
        values.put("getMaxFailureWaitSeconds", 60 * 60);
        values.put("getBruteForceStrategy", RealmRepresentation.BruteForceStrategy.MULTIPLE);
        values.put("getWaitIncrementSeconds", 60);
        values.put("getFailureFactor", 3);
        values.put("getQuickLoginCheckMilliSeconds", 0L);
        values.put("getMinimumQuickLoginWaitSeconds", 0);

        return proxy(RealmModel.class, values);
    }

    private static final class InMemoryFailureModel {
        int numFailures = 0;
        int tempLockouts = 0;
        long lastFailureMillis = 0;
        int failedLoginNotBeforeSeconds = 0;
        String lastIpFailure = "127.0.0.1";
    }

    private UserLoginFailureModel failureModel(String userId, InMemoryFailureModel mem) {
        Map<String, Object> values = new HashMap<>();
        values.put("getUserId", userId);
        values.put("getLastIPFailure", mem.lastIpFailure);

        Map<String, Handler> handlers = new HashMap<>();

        handlers.put("incrementFailures", (args) -> { mem.numFailures++; return null; });
        handlers.put("getNumFailures", (args) -> mem.numFailures);

        handlers.put("clearFailures", (args) -> { mem.numFailures = 0; mem.tempLockouts = 0; mem.failedLoginNotBeforeSeconds = 0; return null; });

        handlers.put("incrementTemporaryLockouts", (args) -> { mem.tempLockouts++; return null; });
        handlers.put("getNumTemporaryLockouts", (args) -> mem.tempLockouts);

        handlers.put("getLastFailure", (args) -> mem.lastFailureMillis);
        handlers.put("setLastFailure", (args) -> { mem.lastFailureMillis = (Long) args[0]; return null; });

        handlers.put("getFailedLoginNotBefore", (args) -> mem.failedLoginNotBeforeSeconds);
        handlers.put("setFailedLoginNotBefore", (args) -> { mem.failedLoginNotBeforeSeconds = (Integer) args[0]; return null; });

        handlers.put("setLastIPFailure", (args) -> { mem.lastIpFailure = (String) args[0]; return null; });

        return proxy(UserLoginFailureModel.class, values, handlers);
    }

    private KeycloakSession sessionWithFailureModel(RealmModel realm, String userId, UserLoginFailureModel failureModel) {
        // UserLoginFailureProvider
        Map<String, Object> lfValues = new HashMap<>();
        Map<String, Handler> lfHandlers = new HashMap<>();
        lfHandlers.put("getUserLoginFailure", (args) -> failureModel);
        lfHandlers.put("addUserLoginFailure", (args) -> failureModel);
        UserLoginFailureProvider loginFailures = proxy(UserLoginFailureProvider.class, lfValues, lfHandlers);

        // UserProvider (only used for logging username)
        Map<String, Object> uValues = new HashMap<>();
        UserModel u = user(userId, "test-user");
        Map<String, Handler> uHandlers = new HashMap<>();
        uHandlers.put("getUserById", (args) -> u);
        UserProvider users = proxy(UserProvider.class, uValues, uHandlers);

        Map<String, Object> sValues = new HashMap<>();
        Map<String, Handler> sHandlers = new HashMap<>();
        sHandlers.put("loginFailures", (args) -> loginFailures);
        sHandlers.put("users", (args) -> users);

        return proxy(KeycloakSession.class, sValues, sHandlers);
    }

    private UserModel user(String userId, String username) {
        Map<String, Object> values = new HashMap<>();
        values.put("getId", userId);
        values.put("getUsername", username);
        return proxy(UserModel.class, values);
    }

    // ---- minimal dynamic proxy helpers (no Mockito needed) ----
    @FunctionalInterface
    private interface Handler {
        Object handle(Object[] args);
    }

    private static <T> T proxy(Class<T> iface, Map<String, Object> fixedValues) {
        return proxy(iface, fixedValues, new HashMap<>());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, Map<String, Object> fixedValues, Map<String, Handler> handlers) {
        InvocationHandler ih = (Object p, Method m, Object[] args) -> {
            String name = m.getName();
            if (handlers.containsKey(name)) {
                return handlers.get(name).handle(args == null ? new Object[0] : args);
            }
            if (fixedValues.containsKey(name)) {
                return fixedValues.get(name);
            }

            // reasonable defaults for primitives
            Class<?> rt = m.getReturnType();
            if (rt.equals(boolean.class)) return false;
            if (rt.equals(int.class)) return 0;
            if (rt.equals(long.class)) return 0L;
            if (rt.equals(void.class)) return null;

            throw new UnsupportedOperationException("Method not stubbed: " + iface.getSimpleName() + "." + name);
        };
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, ih);
    }

    @Test
    public void testFailTransitionsToLockedAfterThreshold() {
        TestableProtector protector = new TestableProtector();
        RealmModel realm = realmFor3FailureLockout();

        String userId = "u1";
        InMemoryFailureModel mem = new InMemoryFailureModel();
        UserLoginFailureModel failureModel = failureModel(userId, mem);
        KeycloakSession session = sessionWithFailureModel(realm, userId, failureModel);

        long t0 = System.currentTimeMillis();

        // S0 -> S1
        protector.fail(session, realm, userId, "10.0.0.1", t0);
        Assert.assertEquals(1, failureModel.getNumFailures());
        Assert.assertFalse("Should not be locked after 1 failure",
                protector.isTemporarilyDisabled(session, realm, user(userId, "test-user")));

        // S1 -> S2
        protector.fail(session, realm, userId, "10.0.0.1", t0 + 1000);
        Assert.assertEquals(2, failureModel.getNumFailures());
        Assert.assertFalse("Should not be locked after 2 failures",
                protector.isTemporarilyDisabled(session, realm, user(userId, "test-user")));

        // S2 -> S3 (lock applied on 3rd failure)
        protector.fail(session, realm, userId, "10.0.0.1", t0 + 2000);
        Assert.assertEquals(3, failureModel.getNumFailures());

        // Immediately after, notBefore should be in the future => locked
        Assert.assertTrue("Should be locked after threshold failures",
                protector.isTemporarilyDisabled(session, realm, user(userId, "test-user")));
    }

    @Test
    public void testLockedRemainsLockedOnAdditionalFailure() {
        TestableProtector protector = new TestableProtector();
        RealmModel realm = realmFor3FailureLockout();

        String userId = "u2";
        InMemoryFailureModel mem = new InMemoryFailureModel();
        UserLoginFailureModel failureModel = failureModel(userId, mem);
        KeycloakSession session = sessionWithFailureModel(realm, userId, failureModel);

        long t0 = System.currentTimeMillis();

        // drive to Locked
        protector.fail(session, realm, userId, "10.0.0.2", t0);
        protector.fail(session, realm, userId, "10.0.0.2", t0 + 1000);
        protector.fail(session, realm, userId, "10.0.0.2", t0 + 2000);
        Assert.assertTrue(protector.isTemporarilyDisabled(session, realm, user(userId, "test-user")));

        // S3 -> S3 (another failure while locked)
        protector.fail(session, realm, userId, "10.0.0.2", t0 + 3000);
        Assert.assertTrue("Should remain locked after additional failure",
                protector.isTemporarilyDisabled(session, realm, user(userId, "test-user")));
    }

    @Test
    public void testSuccessClearsFailures() {
        TestableProtector protector = new TestableProtector();
        RealmModel realm = realmFor3FailureLockout();

        String userId = "u3";
        InMemoryFailureModel mem = new InMemoryFailureModel();
        UserLoginFailureModel failureModel = failureModel(userId, mem);
        KeycloakSession session = sessionWithFailureModel(realm, userId, failureModel);

        long t0 = System.currentTimeMillis();

        // S0 -> S1
        protector.fail(session, realm, userId, "10.0.0.3", t0);
        Assert.assertEquals(1, failureModel.getNumFailures());

        // S1 -> S0 via success
        protector.succeed(session, realm, userId);
        Assert.assertEquals("Failures should be cleared on success", 0, failureModel.getNumFailures());
        Assert.assertFalse("Should not be locked after clearing failures",
                protector.isTemporarilyDisabled(session, realm, user(userId, "test-user")));
    }

    @Test
    public void testUnlockAfterTimeExpires() {
        TestableProtector protector = new TestableProtector();
        RealmModel realm = realmFor3FailureLockout();

        String userId = "u4";
        InMemoryFailureModel mem = new InMemoryFailureModel();
        UserLoginFailureModel failureModel = failureModel(userId, mem);
        KeycloakSession session = sessionWithFailureModel(realm, userId, failureModel);

        long t0 = System.currentTimeMillis();

        // lock user
        protector.fail(session, realm, userId, "10.0.0.4", t0);
        protector.fail(session, realm, userId, "10.0.0.4", t0 + 1000);
        protector.fail(session, realm, userId, "10.0.0.4", t0 + 2000);
        Assert.assertTrue(protector.isTemporarilyDisabled(session, realm, user(userId, "test-user")));

        // Force lock expiration by setting notBefore in the past
        mem.failedLoginNotBeforeSeconds = (int)((System.currentTimeMillis() / 1000) - 10);
        Assert.assertFalse("Should be unlocked after lock expiration",
                protector.isTemporarilyDisabled(session, realm, user(userId, "test-user")));
    }
}
