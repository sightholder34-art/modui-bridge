package dev.modui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Reports screen, key binds and server commands to the launcher and runs its requests on the main thread. */
public class ModUiClient {
    private static final int MAX_COMMANDS = 400;
    private static final int MAX_SUBS = 40;

    private final Bridge bridge = new Bridge();
    private String lastCtx = "";
    private String lastBinds = "";
    private String lastCmds = "";
    private int tick;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !bridge.enabled() || !bridge.connected) return;
        Minecraft mc = Minecraft.getInstance();

        if (bridge.justConnected) {
            bridge.justConnected = false;
            lastCtx = lastBinds = lastCmds = "";
        }

        sendContext(mc);
        if (tick++ % 20 == 0) {
            sendBinds(mc);
            sendCommands(mc);
        }

        JsonObject req;
        while ((req = bridge.requests.poll()) != null) {
            handle(mc, req);
        }
    }

    private void sendContext(Minecraft mc) {
        Screen s = mc.screen;
        JsonObject o = new JsonObject();
        o.addProperty("t", "ctx");
        o.addProperty("screen", s == null ? "" : s.getClass().getName());
        o.addProperty("world", mc.level != null);
        String json = o.toString();
        if (!json.equals(lastCtx)) {
            lastCtx = json;
            bridge.write(o);
        }
    }

    private void sendBinds(Minecraft mc) {
        JsonArray list = new JsonArray();
        for (KeyMapping km : mc.options.keyMappings) {
            InputConstants.Key key = km.getKey();
            InputConstants.Type type = key.getType();
            int kind = type == InputConstants.Type.MOUSE ? 1 : 0;
            boolean usable = !km.isUnbound() && (type == InputConstants.Type.KEYSYM || type == InputConstants.Type.MOUSE);
            JsonObject b = new JsonObject();
            b.addProperty("name", km.getName());
            b.addProperty("title", I18n.get(km.getName()));
            b.addProperty("cat", I18n.get(km.getCategory()));
            b.addProperty("glfw", usable ? key.getValue() : -1);
            b.addProperty("type", kind);
            KeyModifier mod = km.getKeyModifier();
            b.addProperty("mod", mod == null ? "NONE" : mod.name());
            b.addProperty("label", km.getTranslatedKeyMessage().getString());
            list.add(b);
        }
        JsonObject o = new JsonObject();
        o.addProperty("t", "binds");
        o.add("list", list);
        String json = o.toString();
        if (!json.equals(lastBinds)) {
            lastBinds = json;
            bridge.write(o);
        }
    }

    private void sendCommands(Minecraft mc) {
        JsonArray list = new JsonArray();
        ClientPacketListener conn = mc.getConnection();
        if (conn != null) {
            CommandDispatcher<SharedSuggestionProvider> d = conn.getCommands();
            List<CommandNode<SharedSuggestionProvider>> roots = new ArrayList<>(d.getRoot().getChildren());
            roots.sort(Comparator.comparing(CommandNode::getName));
            for (CommandNode<SharedSuggestionProvider> n : roots) {
                if (list.size() >= MAX_COMMANDS) break;
                JsonObject c = new JsonObject();
                c.addProperty("n", n.getName());
                c.addProperty("run", n.getCommand() != null);
                boolean args = false;
                JsonArray subs = new JsonArray();
                for (CommandNode<SharedSuggestionProvider> child : n.getChildren()) {
                    if (child instanceof LiteralCommandNode) {
                        if (subs.size() < MAX_SUBS) subs.add(child.getName());
                    } else {
                        args = true;
                    }
                }
                c.addProperty("args", args);
                c.add("subs", subs);
                list.add(c);
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("t", "cmds");
        o.add("list", list);
        String json = o.toString();
        if (!json.equals(lastCmds)) {
            lastCmds = json;
            bridge.write(o);
        }
    }

    private static String clean(String text) {
        if (text == null) return null;
        text = text.trim();
        if (text.isEmpty() || text.length() > 256) return null;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch < 32 || ch == 127 || ch == '§') return null;
        }
        return text;
    }

    private void handle(Minecraft mc, JsonObject req) {
        String t = req.has("t") ? req.get("t").getAsString() : "";
        switch (t) {
            case "chat": {
                String text = clean(req.has("text") ? req.get("text").getAsString() : null);
                if (text != null && mc.player != null) {
                    mc.player.connection.sendChat(text);
                }
                break;
            }
            case "cmd": {
                String text = clean(req.has("text") ? req.get("text").getAsString() : null);
                if (text != null && text.startsWith("/")) text = text.substring(1);
                if (text != null && !text.isEmpty() && mc.player != null) {
                    mc.player.connection.sendCommand(text);
                }
                break;
            }
            case "close":
                if (mc.screen != null) mc.setScreen(null);
                break;
            case "hotbar": {
                int slot = req.has("slot") ? req.get("slot").getAsInt() : -1;
                if (slot >= 0 && slot <= 8 && mc.player != null) {
                    mc.player.getInventory().selected = slot;
                }
                break;
            }
            default:
                break;
        }
    }
}
