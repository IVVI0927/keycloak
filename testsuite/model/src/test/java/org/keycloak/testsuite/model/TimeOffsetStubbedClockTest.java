package org.keycloak.testsuite.model;

import org.junit.Test;
import org.keycloak.common.util.Time;
import org.keycloak.events.Event;
import org.keycloak.events.EventStoreProvider;
import org.keycloak.events.EventType;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.provider.ProviderFactory;

import static org.junit.Assert.assertEquals;

@RequireProvider(EventStoreProvider.class)
public class TimeOffsetStubbedClockTest extends KeycloakModelTest {

    private String realmId;
    private Long fixedNowMillis = null;

    // Seam for time: can be stubbed in tests
    protected long nowMillis() {
        return fixedNowMillis != null ? fixedNowMillis : Time.currentTimeMillis();
    }

    @Override
    protected void createEnvironment(KeycloakSession s) {
        RealmModel r = s.realms().createRealm("realm");
        s.getContext().setRealm(r);
        r.setDefaultRole(s.roles().addRealmRole(r, Constants.DEFAULT_ROLES_ROLE_PREFIX + "-" + r.getName()));
        r.setEventsExpiration(5);
        realmId = r.getId();
    }

    @Override
    protected void cleanEnvironment(KeycloakSession s) {
        RealmModel r = s.realms().getRealm(realmId);
        s.getContext().setRealm(r);
        s.realms().removeRealm(realmId);
    }

    @Test
    public void testOffset_withStubbedClock() {
        fixedNowMillis = 1_000L; // stub time on the JUnit-managed instance

        withRealm(realmId, (session, realmModel) -> {
            EventStoreProvider provider = session.getProvider(EventStoreProvider.class);

            Event e = new Event();
            e.setType(EventType.LOGIN);
            e.setRealmId(realmId);
            e.setTime(nowMillis()); // uses stubbed method instead of real time
            provider.onEvent(e);
            return null;
        });

        withRealm(realmId, (session, realmModel) -> {
            EventStoreProvider provider = session.getProvider(EventStoreProvider.class);
            assertEquals(1, provider.createQuery().realm(realmId).getResultStream().count());

            setTimeOffset(5);

            ProviderFactory<EventStoreProvider> providerFactory =
                    session.getKeycloakSessionFactory().getProviderFactory(EventStoreProvider.class);
            if ("jpa".equals(providerFactory.getId())) {
                provider.clearExpiredEvents();
            }
            return null;
        });

        withRealm(realmId, (session, realmModel) -> {
            EventStoreProvider provider = session.getProvider(EventStoreProvider.class);
            assertEquals(0, provider.createQuery().realm(realmId).getResultStream().count());
            return null;
        });
    }
}
