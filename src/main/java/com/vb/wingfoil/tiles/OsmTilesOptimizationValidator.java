package com.vb.wingfoil.tiles;

import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import jakarta.inject.Singleton;

/**
 * Eagerly validates the {@code osm-tiles.optimization.colors} setting during context startup so an
 * out-of-set value (e.g. {@code 15}) fails startup with a clear error naming the value, rather than
 * being discovered only when the first composite is rendered. It listens for the context-level
 * {@link StartupEvent} because Micronaut singletons are otherwise created lazily: an unreferenced
 * validator would never be instantiated at startup and the invalid value would slip through.
 * Publishing {@code StartupEvent} resolves the matching listener beans, which forces this bean (and
 * thus its validating constructor) to run during {@code ApplicationContext#start()}.
 */
@Singleton
public class OsmTilesOptimizationValidator implements ApplicationEventListener<StartupEvent> {

    public OsmTilesOptimizationValidator(OsmTilesConfiguration config) {
        // Force resolution of the typed color mode now; throws IllegalArgumentException on invalid input.
        config.getOptimization().getColorsMode();
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        // No-op: validation happens in the constructor; the listener only forces eager instantiation.
    }
}
