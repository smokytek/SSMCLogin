/*
 * The MIT License (MIT)
 *
 * Copyright © 2026 - OpenLogin Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.nickuc.openlogin.bukkit.util;

import com.nickuc.openlogin.bukkit.enums.BukkitVersion;
import com.nickuc.openlogin.common.util.reflection.ClassUtils;
import com.nickuc.openlogin.common.util.reflection.ReflectionUtils;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.nickuc.openlogin.bukkit.util.BukkitReflection.Util.expandVariables;
import static com.nickuc.openlogin.common.util.reflection.ReflectionUtils.*;

public class BukkitReflection {

    public static final @Nullable Class<?> minecraftServerClass = getClass("net.minecraft.server.MinecraftServer", "{nms}.MinecraftServer");
    public static final @Nullable Class<?> craftServerClass = getClass("{obc}.CraftServer");
    public static final @Nullable Class<?> craftPlayerClass = getClass("{obc}.entity.CraftPlayer");
    public static final @Nullable Method getHandleMethod = craftPlayerClass != null ? getMethod(craftPlayerClass, "getHandle") : null;

    public static final @Nullable Class<?> entityPlayerClass = getClassOrFallback(() ->
                    getHandleMethod != null ? getHandleMethod.getReturnType() : null,
            "net.minecraft.server.level.EntityPlayer", "net.minecraft.server.level.ServerPlayer", "{nms}.EntityPlayer");
    public static final @Nullable Class<?> playerConnectionClass = getClassOrFallback(() ->
                    entityPlayerClass != null ? Arrays
                            .stream(entityPlayerClass.getDeclaredFields())
                            .map(Field::getType)
                            .filter(type -> type.getSimpleName().contains("NetHandler"))
                            .findFirst().orElse(null) : null,
            "net.minecraft.server.network.PlayerConnection", "net.minecraft.server.network.ServerGamePacketListenerImpl", "{nms}.PlayerConnection");
    public static final @Nullable Method sendPacketMethod;

    public static final @Nullable Field playerConnectionField = entityPlayerClass != null && playerConnectionClass != null ? getField(entityPlayerClass, playerConnectionClass, 0) : null;
    public static final @Nullable Field playerNetworkManager;

    public static final Method soundValueOf;

    static {
        Class<?> sendPacketMethodClass;
        if (BukkitVersion.getVersion().isNewerOrEqual(BukkitVersion.v1_18) && playerConnectionClass != null) {
            Class<?> networkManager = getClass("net.minecraft.network.NetworkManager", "net.minecraft.network.Connection", "{nms}.NetworkManager");
            playerNetworkManager = getField(playerConnectionClass, networkManager, 0);
            sendPacketMethodClass = networkManager;
        } else {
            playerNetworkManager = null;
            sendPacketMethodClass = playerConnectionClass;
        }

        Class<?> packetClass = getClass("net.minecraft.network.protocol.Packet", "{nms}.Packet");
        sendPacketMethod = packetClass != null && sendPacketMethodClass != null ?
                getTypedMethod(sendPacketMethodClass, null, void.class, packetClass) : null;

        soundValueOf = ReflectionUtils.getMethod(Sound.class, "valueOf", String.class);
    }

    public static Class<?> getClassOrFallback(Callable<Class<?>> fallback, String... classes) {
        Class<?> clazz = getClass(classes);
        if (clazz != null) {
            return clazz;
        }

        try {
            return fallback.call();
        } catch (Exception e) {
            throw new RuntimeException("Cannot call fallback class" + (classes.length > 0 ? " for " + classes[0] : 0) + "!", e);
        }
    }

    @Nullable
    public static Class<?> getClass(String... classes) {
        for (int i = 0; i < classes.length; i++) {
            classes[i] = expandVariables(classes[i]);
        }

        Class<?> clazz;
        for (String c : classes) {
            try {
                if ((clazz = ClassUtils.getClass(expandVariables(c))) != null) {
                    return clazz;
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
        return null;
    }

    @Nullable
    public static Object getHandle(Player player) {
        return getHandleMethod != null ? ReflectionUtils.invoke(getHandleMethod, player) : null;
    }

    public static void sendPacket(Player player, Object packet) throws ReflectiveOperationException {
        if (packet == null) throw new IllegalArgumentException("Packet cannot be null!");
        if (playerConnectionField == null) throw new IllegalArgumentException("Cannot find playerConnectionField to invoke sendPacket method!");

        Object entityPlayer = getHandle(player);
        if (entityPlayer == null) return;

        Object playerConnection = playerConnectionField.get(entityPlayer);
        if (BukkitVersion.getVersion().isNewerOrEqual(BukkitVersion.v1_18)) {
            if (playerNetworkManager == null) throw new IllegalArgumentException("Cannot find playerNetworkManager to invoke sendPacket method!");

            Object networkManager = playerNetworkManager.get(playerConnection);
            ReflectionUtils.invoke(sendPacketMethod, networkManager, packet);
        } else {
            ReflectionUtils.invoke(sendPacketMethod, playerConnection, packet);
        }
    }

    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class Util {

        private static final Pattern MATCH_VARIABLE = Pattern.compile("\\{([^}]+)}");

        private static final String OBC_PREFIX = Bukkit.getServer().getClass().getPackage().getName();
        private static final String NMS_PREFIX = OBC_PREFIX.replace("org.bukkit.craftbukkit", "net.minecraft.server");
        private static final String NM_PREFIX = OBC_PREFIX.replace("org.bukkit.craftbukkit", "net.minecraft");
        private static final String VERSION = OBC_PREFIX.replace("org.bukkit.craftbukkit", "").replace(".", "");

        public static String expandVariables(String name) {
            StringBuffer output = new StringBuffer();
            Matcher matcher = MATCH_VARIABLE.matcher(name);

            while (matcher.find()) {
                String variable = matcher.group(1);
                String replacement;

                // Expand all detected variables
                switch (variable) {
                    case "nms":
                        replacement = NMS_PREFIX;
                        break;
                    case "nm":
                        replacement = NM_PREFIX;
                        break;
                    case "obc":
                        replacement = OBC_PREFIX;
                        break;
                    case "version":
                        replacement = VERSION;
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown variable: " + variable);
                }

                // Assume the expanded variables are all packages, and append a dot
                if (!replacement.isEmpty() && matcher.end() < name.length() && name.charAt(matcher.end()) != '.') {
                    replacement += ".";
                }
                matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
            }

            matcher.appendTail(output);
            return output.toString();
        }
    }
}

