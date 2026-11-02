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
    static final double RAKE_PCT = 0.10;          // rakeback = 10% of what they lost...
    static final String RAKE_RULE = "10% back · min loss $1B";   // ...once they've lost at least RAKE_TARGET
    // ======================

    static final int[] PROFIT_POS = {-1, 8}, PROFIT_D = {-1, 8};   // x = -1 means "stick to the right edge"
    static final int[] NETS_POS = {-1, 190}, NETS_D = {-1, 190};
    static final int[] RAKE_POS = {8, 8}, RAKE_D = {8, 8};
    static final int[] RECENT_POS = {8, 260}, RECENT_D = {8, 260};
    static final Map<String, Double> NETS = new LinkedHashMap<>();
    static double IN_TOTAL = 0, OUT_TOTAL = 0;                       // money paid to you / paid out by you
    static final List<Double> HISTORY = new ArrayList<>();           // running profit after each payment
    static final List<Map.Entry<String, Double>> RECENT = new ArrayList<>();   // newest first; + = they paid you, - = you paid them

    // DonutSMP payment messages (a few wordings are accepted)
    static final String NAME_RE = "(?<name>[.\\w]{1,17})";
    static final String AMT_RE = "\\$(?<num>[\\d,]+(?:\\.\\d+)?)\\s*(?<suf>[kKmMbBtT])?";
    static final String PRE = "^(?:\\[[^\\]]*\\]\\s*|[^\\p{L}\\p{N}.])*";
    static final Pattern[] IN = {
        Pattern.compile(PRE + NAME_RE + " paid you " + AMT_RE),
        Pattern.compile(PRE + NAME_RE + " sent you " + AMT_RE),
        Pattern.compile(PRE + "You received " + AMT_RE + " from " + NAME_RE)
    };
    static final Pattern[] OUT = {
        Pattern.compile(PRE + "You paid " + NAME_RE + " " + AMT_RE),
        Pattern.compile(PRE + "You sent " + AMT_RE + " to " + NAME_RE)
    };

    static final int WHITE = 0xFFF2F3F7, MUTED = 0xFF9AA0AD, GREEN = 0xFF3DF08A, RED = 0xFFFF4D6D, PINK = 0xFFFF3D8B;

    @Override
    public void onInitializeClient() {
        load();

        // Commands typed by you (never sent to the server):
        //  /profithud, /netshud, /rakehud, /recenthud   <x> <y> | left|right|up|down [amt] | reset
        //  /nets <name> <amount> | set <name> <amount> | remove <name> | clear
        ClientSendMessageEvents.ALLOW_COMMAND.register(cmd -> {
            String[] a = cmd.trim().split("\\s+");
            try {
                switch (a[0].toLowerCase()) {
                    case "profithud" -> move(PROFIT_POS, PROFIT_D, a);
                    case "netshud" -> move(NETS_POS, NETS_D, a);
                    case "rakehud" -> move(RAKE_POS, RAKE_D, a);
                    case "recenthud" -> move(RECENT_POS, RECENT_D, a);
                    case "nets" -> edit(NETS, a);
                    default -> { return true; }
                }
                save();
            } catch (Exception ignored) { }
            return false;
        });

        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> {
            if (overlay) return;
            String text = msg.getString();
            if (!trackPay(text) && text.contains("$")) logLine(text);
        });

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("playernets", "panels"), (g, dt) -> render(g));
    }

    // Player pays you = they bet (their net goes down). You pay them = they win (their net goes up).
    static boolean trackPay(String text) {
        String t = text.trim();
        Matcher m = null;
        boolean in = true;
        for (Pattern p : IN) {
            Matcher x = p.matcher(t);
            if (x.find()) { m = x; break; }
        }
        if (m == null) {
            in = false;
            for (Pattern p : OUT) {
                Matcher x = p.matcher(t);
                if (x.find()) { m = x; break; }
            }
        }
        if (m == null) return false;
        Double v = amount(m.group("num") + (m.group("suf") == null ? "" : m.group("suf")));
        if (v == null) return false;
        String name = m.group("name");
        String key = name;
        for (String n : NETS.keySet()) if (n.equalsIgnoreCase(name)) { key = n; break; }
        NETS.merge(key, in ? -v : v, Double::sum);
        if (in) IN_TOTAL += v; else OUT_TOTAL += v;
        HISTORY.add(IN_TOTAL - OUT_TOTAL);
        while (HISTORY.size() > 150) HISTORY.remove(0);
        RECENT.add(0, Map.entry(name, in ? v : -v));
        while (RECENT.size() > 6) RECENT.remove(RECENT.size() - 1);
        save();
        return true;
    }

    // Lines with a $ that we did NOT recognise are saved here, so the wording can be checked.
    static void logLine(String text) {
        try {
            if (Files.exists(LOG) && Files.size(LOG) > 100_000) return;
            Files.writeString(LOG, text + "\n", java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) { }
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
        if (k.equals("clear")) { m.clear(); RECENT.clear(); IN_TOTAL = 0; OUT_TOTAL = 0; HISTORY.clear(); return; }
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
    static final Path LOG = FabricLoader.getInstance().getConfigDir().resolve("playernets-log.txt");

    static void save() {
        StringBuilder sb = new StringBuilder();
        sb.append("pos,profit,").append(PROFIT_POS[0]).append(',').append(PROFIT_POS[1]).append('\n');
        sb.append("tot,").append(IN_TOTAL).append(',').append(OUT_TOTAL).append('\n');
        for (double h : HISTORY) sb.append("hist,").append(h).append('\n');
        sb.append("pos,nets,").append(NETS_POS[0]).append(',').append(NETS_POS[1]).append('\n');
        sb.append("pos,rake,").append(RAKE_POS[0]).append(',').append(RAKE_POS[1]).append('\n');
        sb.append("pos,recent,").append(RECENT_POS[0]).append(',').append(RECENT_POS[1]).append('\n');
        for (Map.Entry<String, Double> e : RECENT) sb.append("rec,").append(e.getKey()).append(',').append(e.getValue()).append('\n');
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
                            int[] t = p[1].equals("nets") ? NETS_POS : p[1].equals("recent") ? RECENT_POS : p[1].equals("profit") ? PROFIT_POS : RAKE_POS;
                            t[0] = Integer.parseInt(p[2]);
                            t[1] = Integer.parseInt(p[3]);
                        }
                        case "net" -> NETS.put(p[1], Double.parseDouble(p[2]));
                        case "tot" -> { IN_TOTAL = Double.parseDouble(p[1]); OUT_TOTAL = Double.parseDouble(p[2]); }
                        case "hist" -> HISTORY.add(Double.parseDouble(p[1]));
                        case "rec" -> RECENT.add(Map.entry(p[1], Double.parseDouble(p[2])));
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
        renderProfit(g, f);
        renderNets(g, f);
        renderRake(g, f);
        renderRecent(g, f);
    }

    static String fit(Font f, String s, int maxW) {
        if (f.width(s) <= maxW) return s;
        while (s.length() > 1 && f.width(s + "..") > maxW) s = s.substring(0, s.length() - 1);
        return s + "..";
    }

    static void drawChart(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        int n = HISTORY.size();
        double min = 0, max = 0;
        for (double v : HISTORY) { min = Math.min(min, v); max = Math.max(max, v); }
        if (max - min < 1) max = min + 1;
        int yz = y + (int) (max / (max - min) * (h - 1));
        g.fill(x, yz, x + w, yz + 1, 0x22FFFFFF);
        if (n < 2) return;
        for (int i = 0; i < w; i++) {
            double t = (double) i * (n - 1) / (w - 1);
            int i0 = (int) Math.floor(t);
            int i1 = Math.min(n - 1, i0 + 1);
            double v = HISTORY.get(i0) + (HISTORY.get(i1) - HISTORY.get(i0)) * (t - i0);
            int yy = y + (int) ((max - v) / (max - min) * (h - 1));
            int top = Math.min(yy, yz), bot = Math.max(yy, yz) + 1;
            boolean up = v >= 0;
            g.fill(x + i, top, x + i + 1, bot, up ? 0x403DF08A : 0x40FF4D6D);
            g.fill(x + i, yy, x + i + 1, yy + 1, up ? GREEN : RED);
        }
    }

    static void renderProfit(GuiGraphicsExtractor g, Font f) {
        if (HISTORY.isEmpty() && NETS.isEmpty()) return;
        double profit = IN_TOTAL - OUT_TOTAL;
        int w = 260, h = 176;
        int x = PROFIT_POS[0] < 0
                ? Minecraft.getInstance().getWindow().getGuiScaledWidth() - w - 8
                : PROFIT_POS[0];
        int y = PROFIT_POS[1];
        panel(g, x, y, w, h);
        g.text(f, "PROFIT", x + 8, y + 7, WHITE, true);

        g.pose().pushMatrix();
        g.pose().translate(x + 8, y + 19);
        g.pose().scale(2f, 2f);
        g.text(f, signed(profit), 0, 0, profit >= 0 ? GREEN : RED, true);
        g.pose().popMatrix();

        int ry = y + 40;
        g.text(f, "In", x + 8, ry, MUTED, false);
        g.text(f, money(IN_TOTAL), x + 8 + f.width("In "), ry, WHITE, false);
        int ox = x + 110;
        g.text(f, "Out", ox, ry, MUTED, false);
        g.text(f, money(OUT_TOTAL), ox + f.width("Out "), ry, WHITE, false);

        drawChart(g, x + 8, y + 54, w - 16, 44);

        List<Map.Entry<String, Double>> ups = new ArrayList<>();
        List<Map.Entry<String, Double>> downs = new ArrayList<>();
        for (Map.Entry<String, Double> e : NETS.entrySet()) {
            if (e.getValue() > 0) ups.add(e);
            else if (e.getValue() < 0) downs.add(e);
        }
        ups.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        downs.sort(Map.Entry.<String, Double>comparingByValue());

        int colW = (w - 24) / 2;
        int hy = y + 106;
        g.text(f, "TOP WINNERS", x + 8, hy, GREEN, true);
        g.text(f, "TOP LOSERS", x + 16 + colW, hy, RED, true);
        for (int i = 0; i < 5; i++) {
            int r = hy + 12 + i * 10;
            if (i < ups.size()) {
                Map.Entry<String, Double> e = ups.get(i);
                String amt = signed(e.getValue());
                g.text(f, fit(f, e.getKey(), colW - f.width(amt) - 6), x + 8, r, WHITE, false);
                g.text(f, amt, x + 8 + colW - f.width(amt), r, GREEN, false);
            }
            if (i < downs.size()) {
                Map.Entry<String, Double> e = downs.get(i);
                String amt = signed(e.getValue());
                g.text(f, fit(f, e.getKey(), colW - f.width(amt) - 6), x + 16 + colW, r, WHITE, false);
                g.text(f, amt, x + 16 + colW + colW - f.width(amt), r, RED, false);
            }
        }
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
        int w = Math.max(190, f.width("RAKEBACK") + f.width(RAKE_RULE) + 30);
        int h = 26 + rows.size() * 19;
        panel(g, x, y, w, h);
        g.text(f, "RAKEBACK", x + 8, y + 7, WHITE, true);
        g.text(f, RAKE_RULE, x + w - 8 - f.width(RAKE_RULE), y + 7, MUTED, false);
        int ry = y + 21;
        for (Map.Entry<String, Double> e : rows) {
            double loss = e.getValue();
            boolean owed = loss >= RAKE_TARGET;
            g.text(f, e.getKey(), x + 8, ry, WHITE, false);
            String t = owed ? "owed " + money(loss * RAKE_PCT) : money(loss) + " / " + RAKE_LABEL;
            g.text(f, t, x + w - 8 - f.width(t), ry, owed ? GREEN : MUTED, false);
            int by = ry + 10;
            g.fill(x + 8, by, x + w - 8, by + 2, 0x33FFFFFF);
            int bw = (int) ((w - 16) * Math.min(1.0, loss / RAKE_TARGET));
            if (bw > 0) g.fill(x + 8, by, x + 8 + bw, by + 2, PINK);
            ry += 19;
        }
    }

    static void renderRecent(GuiGraphicsExtractor g, Font f) {
        if (RECENT.isEmpty()) return;
        int w = 190;
        for (Map.Entry<String, Double> e : RECENT)
            w = Math.max(w, f.width(e.getKey()) + f.width(signed(e.getValue())) + 48);
        int x = RECENT_POS[0] < 0
                ? Minecraft.getInstance().getWindow().getGuiScaledWidth() - w - 8
                : RECENT_POS[0];
        int y = RECENT_POS[1];
        int h = 26 + RECENT.size() * 12;
        panel(g, x, y, w, h);
        g.text(f, "RECENT PAYMENTS", x + 8, y + 7, WHITE, true);
        int ry = y + 21;
        for (Map.Entry<String, Double> e : RECENT) {
            boolean got = e.getValue() >= 0;
            g.text(f, got ? "<-" : "->", x + 8, ry, got ? GREEN : RED, false);
            g.text(f, e.getKey(), x + 28, ry, WHITE, false);
            String t = signed(e.getValue());
            g.text(f, t, x + w - 8 - f.width(t), ry, got ? GREEN : RED, false);
            ry += 12;
        }
    }
}
