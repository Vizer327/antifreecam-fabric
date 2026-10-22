package io.github.sanchous327.antifreecam.mixin.blocks;

import com.google.common.collect.Queues;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.github.sanchous327.antifreecam.blocks.util.Util;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Queue;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@Mixin(Connection.class)
public abstract class ConnectionMixin {

    @Unique
    private final Queue<BooleanSupplier> antiFreecam$isActionReady = Queues.newConcurrentLinkedQueue();
    @Shadow
    private Channel channel;
    @Shadow
    @Final
    private Queue<Consumer<Connection>> pendingActions;

    @Shadow
    protected abstract void sendPacket(Packet<?> $$0, ChannelFutureListener $$1, boolean $$2);

    /**
     * @author Drex
     * @reason Wait for chunk packets to be ready (fully obfuscated)
     */
    @Overwrite
    private void flushQueue() {
        assert pendingActions.size() == antiFreecam$isActionReady.size();
        if (this.channel != null && this.channel.isOpen()) {
            synchronized (this.pendingActions) {
                while (!this.antiFreecam$isActionReady.isEmpty() && (this.antiFreecam$isActionReady.peek().getAsBoolean())) {
                    pendingActions.poll().accept((Connection) (Object) this);
                    antiFreecam$isActionReady.poll();
                }
            }
        }
    }

    @Redirect(
        method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/Connection;sendPacket(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V"
        )
    )
    public void redirectSendPacket(Connection instance, Packet<?> packet, ChannelFutureListener listener, boolean flush) {
        if (this.antiFreecam$isActionReady.isEmpty() && Util.isReady(packet)) {
            this.sendPacket(packet, listener, flush);
        } else {
            synchronized (this.pendingActions) {
                pendingActions.add(connection -> this.sendPacket(packet, listener, flush));
                antiFreecam$isActionReady.add(() -> Util.isReady(packet));
            }
        }
    }

    @WrapOperation(
        method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/Queue;add(Ljava/lang/Object;)Z"
        )
    )
    public <E> boolean addToActionReadyQueue(Queue<E> instance, E e, Operation<Boolean> original, Packet<?> packet) {
        synchronized (this.pendingActions) {
            antiFreecam$isActionReady.add(() -> Util.isReady(packet));
            return original.call(instance, e);
        }
    }

    @WrapOperation(
        method = {"flushChannel", "runOnceConnected"},
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/Queue;add(Ljava/lang/Object;)Z"
        )
    )
    public <E> boolean addToActionReadyQueue(Queue<E> instance, E e, Operation<Boolean> original) {
        synchronized (this.pendingActions) {
            antiFreecam$isActionReady.add(() -> true);
            return original.call(instance, e);
        }
    }

}
