package io.github.sanchous327.antifreecam.culling.platform.command;

import io.github.sanchous327.antifreecam.culling.platform.IPlatform;
import org.jspecify.annotations.NullMarked;

@NullMarked
public interface PlatformCommandSender {

    IPlatform getPlatform();

    boolean hasPermission(String permission, boolean defaultValue);
}
