package test.org.keycloak.quarkus.services;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

public class MockingVerifyTest {

    interface Dependency {
        void publish(String msg);
        int compute(int x);
    }

    static class FeatureUnderTest {
        private final Dependency dep;

        FeatureUnderTest(Dependency dep) {
            this.dep = dep;
        }

        void doWork(boolean enabled) {
            if (!enabled) {
                return; // should NOT call dep
            }
            int v = dep.compute(10);
            dep.publish("value=" + v);
        }
    }

    @Test
    public void testDoWork_verifiesInteractions() {
        // Arrange
        Dependency dep = mock(Dependency.class);
        when(dep.compute(10)).thenReturn(7);

        FeatureUnderTest sut = new FeatureUnderTest(dep);

        // Act
        sut.doWork(true);

        // Assert: verify behavior
        verify(dep, times(1)).compute(10);
        verify(dep, times(1)).publish("value=7");
        verifyNoMoreInteractions(dep);
    }

    @Test
    public void testDoWork_disabled_neverCallsDependency() {
        // Arrange
        Dependency dep = mock(Dependency.class);
        FeatureUnderTest sut = new FeatureUnderTest(dep);

        // Act
        sut.doWork(false);

        // Assert: verify NO behavior happened
        verify(dep, never()).compute(anyInt());
        verify(dep, never()).publish(anyString());
        verifyNoInteractions(dep);
    }
}
