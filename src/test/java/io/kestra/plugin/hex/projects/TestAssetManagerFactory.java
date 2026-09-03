package io.kestra.plugin.hex.projects;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.kestra.core.assets.AssetManagerFactory;
import io.kestra.core.runners.AssetEmit;
import io.kestra.core.runners.AssetEmitter;

import io.micronaut.context.annotation.Replaces;
import jakarta.inject.Singleton;

// Kestra's real emitter is EE-only, so tests replace the factory to see what a task emitted.
@Singleton
@Replaces(AssetManagerFactory.class)
public class TestAssetManagerFactory extends AssetManagerFactory {
    private final List<AssetEmit> emitted = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean unsupported = false;

    @Override
    public AssetEmitter of(boolean enable) {
        return unsupported ? new UnsupportedEmitter() : new TrackingEmitter(emitted);
    }

    List<AssetEmit> emitted() {
        return List.copyOf(emitted);
    }

    // Mirrors the OSS edition, where the EE emitter is absent and emit() throws.
    void unsupported(boolean unsupported) {
        this.unsupported = unsupported;
    }

    void clear() {
        emitted.clear();
        unsupported = false;
    }

    // emitted() is what the worker reads to attach one run context's emissions to its own task run, so it
    // reports this emitter's own list, while the factory-wide one is what tests assert against.
    private static final class TrackingEmitter implements AssetEmitter {
        private final List<AssetEmit> shared;
        private final List<AssetEmit> own = new ArrayList<>();

        private TrackingEmitter(List<AssetEmit> shared) {
            this.shared = shared;
        }

        // Records regardless of enable, so a test can see whether the task itself called emit at all.
        @Override
        public void emit(AssetEmit assetEmit) {
            own.add(assetEmit);
            shared.add(assetEmit);
        }

        @Override
        public List<AssetEmit> emitted() {
            return List.copyOf(own);
        }
    }

    private record UnsupportedEmitter() implements AssetEmitter {
        @Override
        public void emit(AssetEmit assetEmit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AssetEmit> emitted() {
            return List.of();
        }
    }
}
