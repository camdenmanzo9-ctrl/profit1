package com.snowy.playernets;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlayerNetsClient implements ClientModInitializer {
    // ===== EDIT THESE =====
    static final double RAKE_TARGET = 1e9;
    static final String RAKE_LABEL = "$1B";
    static final String RAKE_RULE = "10% over $1B lost";
    // ======================

    static final int[] NETS_POS = {-1, 8}, NETS_D = {-1, 8};   // x = -1 means "stick to the right edge"
    static final int[] RAKE_POS = {8, 8}, RAKE_D = {8, 8};
    static final Map<String, Double> NETS = new LinkedHashMap<>();

    // DonutSMP payment messages
    static final Pattern PAID_YOU = Pattern.compile("^\\W*(\\w{1,16}) paid you \\$([\\d,]+(?:\\.\\d+)?)\\s*([kKmMbBtT])?");
    static final Pattern YOU_PAID = Pattern.compile("^\\W*You paid (\\w{1,16}) \\$([\\d,]+(?:\\.\\d+)?)\\s*([kKmMbBtT])?");

    static final int WHITE = 0xFFF2F3F7, MUTED = 0xFF9AA0AD, GREEN = 0xFF3DF08A, RED = 0xFFFF4D6D, PINK = 0xFFFF3D8B;

    @Override
    public void onInitializeClient() {
        load();

        // Commands typed by you (never sent to the server):
        //  /netshud, /rakehud   <x> <y> | left|right|up|down [amt] | reset
        //  /nets <name> <amount> | set <name> <amount> | remove <name> | clear
        ClientSendMessageEvents.ALLOW_COMMAND.register(cmd -> {
            String[] a = cmd.trim().split("\\s+");
            try {
                switch (a[0].toLowerCase()) {
                    case "netshud" -> move(NETS_POS, NETS_D, a);
                    case "rakehud" -> move(RAKE_POS, RAKE_D, a);
                    case "nets" -> edit(NETS, a);
                    default -> { return true; }
                }
                save();
            } catch (Exception ignored) { }
            return false;
        });

        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> {
            if (!overlay) trackPay(msg.getString());
        });

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("playernets", "panels"), (g, dt) -> render(g));
    }

    // Player pays you = they bet (their net goes down). You pay them = they win (their net goes up).
    static void trackPay(String text) {
        String t = text.trim();
        Matcher m = PAID_YOU.matcher(t);
        boolean theyPaid = m.find();
        if (!theyPaid) {
            m = YOU_PAID.matcher(t);
            if (!m.find()) return;
        }
        Double v = amount(m.group(2) + (m.group(3) == null ? "" : m.group(3)));
        if (v == null) return;
        String name = m.group(1);
        String key = name;
        for (String n : NETS.keySet()) if (n.equalsIgnoreCase(name)) { key = n; break; }
        NETS.merge(key, theyPaid ? -v : v, Double::sum);
        save();
    }

    // ---------- commands ----------
    static void move(int[] p, int[] d, String[] a) {
        if (a.length < 2) return;
        if (a[1].equalsIgnoreCase("reset")) { p[0] = d[0]; p[1] = d[1]; return; }
        if (p[0] < 0) p[0] = Math.max(0, Minecraft.getInstance().getWindow().getGuiScaledWidth() - 228);
        int amt = a.length > 2 ? Integer.parseInt(a[2]) : 10;
        switch (a[1].toLowerCase()) {
            case "left" -> p[0] -= amt;
            case "right" -> p[0] += amt;
            case "up" -> p[1] -= amt;
            case "down" -> p[1] += amt;
            default -> { p[0] = Integer.parseInt(a[1]); p[1] = Integer.parseInt(a[2]); }
        }
        p[0] = Math.max(0, p[0]);
        p[1] = Math.max(0, p[1]);
    }

    static void edit(Map<String, Double> m, String[] a) {
        if (a.length < 2) return;
        String k = a[1].toLowerCase();
        if (k.equals("clear")) { m.clear(); return; }
        if (k.equals("remove") && a.length > 2) {
            String who = a[2];
            m.keySet().removeIf(n -> n.equalsIgnoreCase(who));
            return;
        }
        boolean set = k.equals("set");
        if (set && a.length < 4) return;
        String name = set ? a[2] : a[1];
        Double v = amount(set ? a[3] : (a.length > 2 ? a[2] : null));
        if (v == null) return;
        String key = name;
        for (String n : m.keySet()) if (n.equalsIgnoreCase(name)) { key = n; break; }
        if (set) m.put(key, v); else m.merge(key, v, Double::sum);
    }

    // accepts 412m, -3.99b, +200m, $1,000, 5k, 2t
    static Double amount(String s) {
        if (s == null) return null;
        try {
            s = s.replace(",", "").replace("$", "").trim();
            double mult = 1;
            switch (Character.toLowerCase(s.charAt(s.length() - 1))) {
                case 'k' -> mult = 1e3;
                case 'm' -> mult = 1e6;
                case 'b' -> mult = 1e9;
                case 't' -> mult = 1e12;
                default -> { }
            }
            if (mult != 1) s = s.substring(0, s.length() - 1);
            return Double.parseDouble(s) * mult;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- saving ----------
    static final Path CFG = FabricLoader.getInstance().getConfigDir().resolve("playernets.txt");

    static void save() {
        StringBuilder sb = new StringBuilder();
        sb.append("pos,nets,").append(NETS_POS[0]).append(',').append(NETS_POS[1]).append('\n');
        sb.append("pos,rake,").append(RAKE_POS[0]).append(',').append(RAKE_POS[1]).append('\n');
        NETS.forEach((n, v) -> sb.append("net,").append(n).append(',').append(v).append('\n'));
        try { Files.writeString(CFG, sb.toString()); } catch (Exception ignored) { }
    }

    static void load() {
        try {
            for (String line : Files.readAllLines(CFG)) {
                try {
                    String[] p = line.split(",");
                    switch (p[0]) {
                        case "pos" -> {
                            int[] t = p[1].equals("nets") ? NETS_POS : RAKE_POS;
                            t[0] = Integer.parseInt(p[2]);
                            t[1] = Integer.parseInt(p[3]);
                        }
                        case "net" -> NETS.put(p[1], Double.parseDouble(p[2]));
                        default -> { }
                    }
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
    }

    // ---------- formatting ----------
    static String trim(double v) {
        String s = String.format(Locale.US, "%.2f", v);
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    static String money(double v) {
        double a = Math.abs(v);
        if (a >= 1e12) return "$" + trim(a / 1e12) + "T";
        if (a >= 1e9) return "$" + trim(a / 1e9) + "B";
        if (a >= 1e6) return "$" + trim(a / 1e6) + "M";
        if (a >= 1e3) return "$" + trim(a / 1e3) + "K";
        return "$" + trim(a);
    }

    static String signed(double v) {
        return (v >= 0 ? "+" : "-") + money(v);
    }

    // ---------- drawing ----------
    static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xC80E1016);
        g.outline(x, y, w, h, 0x24FFFFFF);
    }

    static void render(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        Font f = mc.font;
        renderNets(g, f);
        renderRake(g, f);
    }

    static void renderNets(GuiGraphicsExtractor g, Font f) {
        if (NETS.isEmpty()) return;
        List<Map.Entry<String, Double>> rows = new ArrayList<>(NETS.entrySet());
        rows.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        if (rows.size() > 14) rows = rows.subList(0, 14);
        String legend = "+ means the player is up, - means they're down";
        int w = 220;
        for (Map.Entry<String, Double> e : rows)
            w = Math.max(w, f.width(e.getKey()) + f.width(signed(e.getValue())) + 32);
        int x = NETS_POS[0] < 0
                ? Minecraft.getInstance().getWindow().getGuiScaledWidth() - w - 8
                : NETS_POS[0];
        int y = NETS_POS[1];
        int h = 38 + rows.size() * 12;
        panel(g, x, y, w, h);
        g.text(f, "PLAYER NETS", x + 8, y + 7, WHITE, true);

        g.pose().pushMatrix();
        g.pose().translate(x + 8, y + 19);
        g.pose().scale(0.8f, 0.8f);
        g.text(f, legend, 0, 0, MUTED, false);
        g.pose().popMatrix();

        int ry = y + 32;
        for (Map.Entry<String, Double> e : rows) {
            g.text(f, e.getKey(), x + 8, ry, WHITE, false);
            String t = signed(e.getValue());
            g.text(f, t, x + w - 8 - f.width(t), ry, e.getValue() >= 0 ? GREEN : RED, false);
            ry += 12;
        }
    }

    static void renderRake(GuiGraphicsExtractor g, Font f) {
        List<Map.Entry<String, Double>> rows = new ArrayList<>();
        for (Map.Entry<String, Double> e : NETS.entrySet())
            if (e.getValue() < 0) rows.add(Map.entry(e.getKey(), -e.getValue()));
        if (rows.isEmpty()) return;
        rows.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        if (rows.size() > 8) rows = rows.subList(0, 8);
        int x = RAKE_POS[0], y = RAKE_POS[1];
        int w = Math.max(170, f.width("RAKEBACK") + f.width(RAKE_RULE) + 30);
        int h = 26 + rows.size() * 19;
        panel(g, x, y, w, h);
        g.text(f, "RAKEBACK", x + 8, y + 7, WHITE, true);
        g.text(f, RAKE_RULE, x + w - 8 - f.width(RAKE_RULE), y + 7, MUTED, false);
        int ry = y + 21;
        for (Map.Entry<String, Double> e : rows) {
            g.text(f, e.getKey(), x + 8, ry, WHITE, false);
            String t = money(e.getValue()) + " / " + RAKE_LABEL;
            g.text(f, t, x + w - 8 - f.width(t), ry, MUTED, false);
            int by = ry + 10;
            g.fill(x + 8, by, x + w - 8, by + 2, 0x33FFFFFF);
            int bw = (int) ((w - 16) * Math.min(1.0, e.getValue() / RAKE_TARGET));
            if (bw > 0) g.fill(x + 8, by, x + 8 + bw, by + 2, PINK);
            ry += 19;
        }
    }
}
