package io.github.sanchous327.antifreecam.culling.config;

import org.jspecify.annotations.NullMarked;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The current culling settings; swapped as a whole on /antifreecam reload, then the reload hooks run.
 */
@NullMarked
public final class CullingConfigHolder {

    private final List<Consumer<CullingConfig>> reloadHooks = new ArrayList<>();
    private volatile CullingConfig config;

    public CullingConfigHolder(CullingConfig config) {
        this.config = config;
    }

    public CullingConfig getDelegate() {
        return this.config;
    }

    public void addReloadHook(Consumer<CullingConfig> hook) {
        synchronized (this) {
            this.reloadHooks.add(hook);
        }
    }

    public void addReloadHookAndRun(Consumer<CullingConfig> hook) {
        this.addReloadHook(hook);
        hook.accept(this.config);
    }

    public void set(CullingConfig config) {
        synchronized (this) {
            this.config = config;
            for (Consumer<CullingConfig> hook : this.reloadHooks) {
                hook.accept(config);
            }
        }
    }
}
