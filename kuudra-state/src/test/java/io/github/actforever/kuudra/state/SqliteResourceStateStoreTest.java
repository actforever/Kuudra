package io.github.actforever.kuudra.state;

import io.github.actforever.kuudra.config.KuudraConfig;
import io.github.actforever.kuudra.config.KuudraManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SqliteResourceStateStoreTest {
    @TempDir Path directory;
    @Test void persistsDesiredAndObservedGenerations() {
        var id=new KuudraManifest.ResourceId("Ingress","demo","gate");
        var metadata=new KuudraManifest.Metadata("demo","gate",Map.of(),Map.of());
        var component=new KuudraManifest.Component(id,metadata,"kuudra-official/default/plain-ingress","active",Map.of());
        var flowId=new KuudraManifest.ResourceId("Flow","demo","route");
        var flowMeta=new KuudraManifest.Metadata("demo","route",Map.of(),Map.of());
        var flow=new KuudraManifest.Flow(flowId,flowMeta,Map.of("gate",new KuudraManifest.ResourceReference("Ingress","demo","gate")),java.util.List.<KuudraConfig.EdgeConfig>of());
        var resources=new KuudraManifest.Resources(Map.of(id,component),Map.of(flowId,flow),Map.of());
        Path database=directory.resolve("kuudra.db");
        try(var store=new SqliteResourceStateStore(database)) {
            store.replaceDesired(resources); assertEquals(resources,store.desiredResources());
            assertTrue(store.states().stream().allMatch(state->state.generation()==1&&state.observedGeneration()==0));
            store.markAllObserved("READY","ok");
            assertTrue(store.states().stream().allMatch(state->state.observedGeneration()==1&&state.phase().equals("READY")));
            store.replaceDesired(resources);
            assertTrue(store.states().stream().allMatch(state->state.generation()==1));
            var changed = new KuudraManifest.Component(id, metadata,
                    "kuudra-official/default/plain-ingress", "active", Map.of("groupKey", "changed"));
            store.replaceDesired(new KuudraManifest.Resources(Map.of(id, changed), Map.of(), Map.of()));
            assertEquals(1, store.states().size());
            assertEquals(2, store.states().get(0).generation());
            assertEquals("PENDING", store.states().get(0).phase());
        }
        try(var reopened=new SqliteResourceStateStore(database)){
            assertEquals(1, reopened.desiredResources().components().size());
            assertTrue(reopened.desiredResources().flows().isEmpty());
        }
    }
}
