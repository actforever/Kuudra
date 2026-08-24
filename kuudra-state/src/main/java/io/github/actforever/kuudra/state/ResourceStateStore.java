package io.github.actforever.kuudra.state;

import io.github.actforever.kuudra.config.KuudraManifest;
import java.util.List;

/** Persistent desired/observed resource state boundary. */
public interface ResourceStateStore extends AutoCloseable {
    void replaceDesired(KuudraManifest.Resources resources);
    KuudraManifest.Resources desiredResources();
    List<ResourceState> states();
    void markAllObserved(String phase, String message);
    @Override void close();

    record ResourceState(KuudraManifest.ResourceId id, long generation, long observedGeneration,
                         String phase, String message) { }
}
