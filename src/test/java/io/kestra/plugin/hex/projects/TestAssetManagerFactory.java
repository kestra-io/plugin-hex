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
        return unsupported ? new UnsupportedEmitter() : new TrackingEmitter(emitted, enable);
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

    private record TrackingEmitter(List<AssetEmit> emitted, boolean enable) implements AssetEmitter {
        @Override
        public void emit(AssetEmit assetEmit) {
            if (enable) {
                emitted.add(assetEmit);
            }
        }

        @Override
        public List<AssetEmit> emitted() {
            return List.copyOf(emitted);
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
