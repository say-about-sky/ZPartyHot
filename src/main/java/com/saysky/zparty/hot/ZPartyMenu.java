package com.saysky.zparty.hot;

import aoc.kingdoms.lukasz.jakowski.CFG;
import aoc.kingdoms.lukasz.jakowski.Game;
import aoc.kingdoms.lukasz.menu.Menu;
import aoc.kingdoms.lukasz.menu_element.MenuElement;
import aoc.kingdoms.lukasz.menu_element.button.ButtonGame;
import aoc.kingdoms.lukasz.menu_element.menuElementHover.MenuElement_Hover;
import aoc.kingdoms.lukasz.menu_element.menuElementHover.MenuElement_HoverElement;
import aoc.kingdoms.lukasz.menu_element.menuElementHover.MenuElement_HoverElement_Type;
import aoc.kingdoms.lukasz.menu_element.menuElementHover.MenuElement_HoverElement_Type_Color;
import aoc.kingdoms.lukasz.menu_element.menuElementHover.MenuElement_HoverElement_Type_Space;
import aoc.kingdoms.lukasz.menu_element.menuElementHover.MenuElement_HoverElement_Type_Text;
import aoc.kingdoms.lukasz.menu_element.menuElementHover.MenuElement_HoverElement_Type_TextTitle_BG;
import aoc.kingdoms.lukasz.menu_element.pieChart.PieChart;
import aoc.kingdoms.lukasz.menu_element.pieChart.PieChart_Data;
import aoc.kingdoms.lukasz.menu_element.pieChart.PieChart_Value;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

import java.util.ArrayList;
import java.util.List;

/**
 * Self-healing government page widget: every N frames, if the government menu is alive
 * and does not contain our elements yet, they are appended (same idea as the host's
 * MainMenuMount, but for the in-game government page).
 */
final class ZPartyMenu {

    interface Mark {
    }

    private static boolean missingLogged;

    private ZPartyMenu() {
    }

    static void tick(ZPartyCore core) {
        try {
            if (!core.active() || !core.showInPage()) return;
            String[] targets = core.uiPageClasses();
            if (targets.length == 0) return;
            boolean found = false;
            for (int t = 0; t < targets.length; t++) {
                String cls = targets[t];
                if (cls == null || cls.length() == 0) continue;
                Menu menu = findMenu(cls);
                if (menu == null) continue;
                found = true;
                mount(core, menu, cls);
            }
            if (!found && !missingLogged) {
                try {
                    if (Game.menuManager != null && Game.menuManager.getInGame()) {
                        missingLogged = true;
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < targets.length; i++) {
                            if (i > 0) sb.append(", ");
                            sb.append(targets[i].substring(targets[i].lastIndexOf('.') + 1));
                        }
                        core.log("ui: target page not open yet (looking for " + sb + ")");
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            core.log("ui: " + t);
        }
    }

    private static void mount(ZPartyCore core, Menu menu, String cls) {
        List elems = core.menuElements(menu);
        if (elems == null || elems.isEmpty()) return;
        for (int i = 0; i < elems.size(); i++) {
            if (elems.get(i) instanceof Mark) return;
        }
        /* hard cap: never let a broken menu grow our block forever */
        if (elems.size() > 256) return;
        int civ = core.courtCiv();
        if (civ <= 0) return;

        int pad = Math.max(4, CFG.PADDING);
        int width = menu.getWidth() - pad * 2;
        if (width <= pad * 6) return;

        /* only the parties that actually exist in this country (see ZPartyCore.partiesFor) */
        List<ZPartyCore.Party> parts = core.partiesFor(civ);
        int rows = Math.min(parts.size(), Math.max(1, core.config().MaxRows));
        int pieSize = Math.min(width / 4, 110);
        if (pieSize < 40) pieSize = 40;
        int rowH = Math.max(CFG.BUTTON_HEIGHT / 3, pieSize / Math.max(1, rows));
        int btnH = Math.max(24, CFG.BUTTON_HEIGHT);
        int blockH = Math.max(pieSize, rows * rowH) + pad + btnH;
        int top = pad;

        /* push the vanilla elements down so our block sits at the top of the page and
           stays inside the visible area (otherwise MenuElement.isInView stays false) */
        for (int i = 0; i < elems.size(); i++) {
            Object o = elems.get(i);
            if (!(o instanceof MenuElement)) continue;
            MenuElement e = (MenuElement) o;
            e.setPosY(e.getPosY() + blockH + pad);
        }

        elems.add(new PartyPie(core, civ, pad, top, pieSize, pieSize));

        int rowX = pad + pieSize + pad;
        int rowW = width - pieSize - pad;
        if (rowW >= pad * 4) {
            for (int i = 0; i < rows; i++) {
                ZPartyCore.Party p = parts.get(i);
                elems.add(new PartyRow(core, civ, p.tag(), rowX, top + i * rowH, rowW, rowH));
            }
        }

        int extraY = top + Math.max(rows * rowH, pieSize) + pad;
        int halfW = (width - pad) / 2;
        if (halfW >= pad * 4) {
            elems.add(new ActionRow(core, civ, true, pad, extraY, halfW, btnH));
            elems.add(new ActionRow(core, civ, false, pad + halfW + pad, extraY, width - halfW - pad, btnH));
        }

        core.setMenuElementsSize(menu, elems.size());
        menu.setHeight(menu.getHeight() + blockH + pad);
        menu.updateScrollable();
        /* required: without this the new elements keep isInView=false and are never drawn */
        menu.updateMenuElements_IsInView();
        menu.update();
        core.log("ui: widget mounted on " + cls.substring(cls.lastIndexOf('.') + 1)
                + " (civ=" + civ + ", elements=" + elems.size() + ")");
    }

    private static Menu findMenu(String cls) {
        try {
            if (Game.menuManager == null) return null;
            List<Menu> active = Game.menuManager.getActiveMenu();
            if (active == null) return null;
            for (int i = 0; i < active.size(); i++) {
                Menu m = active.get(i);
                if (m == null) continue;
                if (cls.equals(m.getClass().getName())) return m;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /* ------------------------------------------------------------------- pie */

    private static MenuElement_Hover buildHover(ZPartyCore core, int civ) {
        List<MenuElement_HoverElement> rows = new ArrayList<MenuElement_HoverElement>();
        List<MenuElement_HoverElement_Type> title = new ArrayList<MenuElement_HoverElement_Type>();
        title.add(new MenuElement_HoverElement_Type_TextTitle_BG("政党支持率"));
        rows.add(new MenuElement_HoverElement(title));

        List<ZPartyCore.Party> parts = core.partiesFor(civ);
        String ruling = core.ruling(civ);
        for (int i = 0; i < parts.size(); i++) {
            ZPartyCore.Party p = parts.get(i);
            List<MenuElement_HoverElement_Type> line = new ArrayList<MenuElement_HoverElement_Type>();
            line.add(new MenuElement_HoverElement_Type_Color(colorOf(p)));
            line.add(new MenuElement_HoverElement_Type_Space());
            StringBuilder sb = new StringBuilder();
            sb.append(p.name()).append("  ");
            sb.append(ZPartyCore.percent(core.share(civ, p.tag())));
            if (ruling != null && ruling.equals(p.tag())) sb.append("（执政）");
            line.add(new MenuElement_HoverElement_Type_Text(sb.toString(), CFG.FONT_REGULAR));
            rows.add(new MenuElement_HoverElement(line));
        }
        return new MenuElement_Hover(rows);
    }

    private static Color colorOf(ZPartyCore.Party p) {
        int[] c = p.Color;
        if (c != null && c.length >= 3) {
            return new Color(c255(c[0]), c255(c[1]), c255(c[2]), 1f);
        }
        int h = p.tag() == null ? 0 : Math.abs(p.tag().hashCode());
        return new Color(0.25f + (h % 7) * 0.10f, 0.25f + ((h / 7) % 7) * 0.10f, 0.25f + ((h / 49) % 7) * 0.10f, 1f);
    }

    private static float c255(int v) {
        if (v < 0) v = 0;
        if (v > 255) v = 255;
        return v / 255f;
    }

    static final class PartyPie extends PieChart implements Mark {
        private final ZPartyCore core;
        private final int civ;
        private long built = -1L;

        PartyPie(ZPartyCore core, int civ, int x, int y, int w, int h) {
            super(x, y, w, h, new PartyData(core, civ), null);
            this.core = core;
            this.civ = civ;
            this.built = core.revision();
        }

        public void draw(SpriteBatch oSB, int iTranslateX, int iTranslateY, boolean isActive, boolean scrollableY) {
            if (built != core.revision()) {
                pieChartData = new PartyData(core, civ);
                built = core.revision();
                try {
                    menuElementHover = buildHover(core, civ);
                } catch (Throwable t) {
                    core.log("hover: " + t);
                }
            }
            super.draw(oSB, iTranslateX, iTranslateY, isActive, scrollableY);
        }

        public void buildElementHover() {
            try {
                menuElementHover = buildHover(core, civ);
            } catch (Throwable t) {
                core.log("hover: " + t);
            }
        }
    }

    static final class PartyData extends PieChart_Data {
        private final float[] r;
        private final float[] g;
        private final float[] b;
        private final float[] empty = new float[] {0.43f, 0.43f, 0.43f};

        PartyData(ZPartyCore core, int civ) {
            super();
            List<ZPartyCore.Party> parts = core.parties();
            float[] shares = core.shares(civ);
            r = new float[parts.size()];
            g = new float[parts.size()];
            b = new float[parts.size()];
            for (int i = 0; i < parts.size(); i++) {
                float[] c = color(parts.get(i));
                r[i] = c[0];
                g[i] = c[1];
                b[i] = c[2];
                if (i < shares.length && shares[i] > 0.0001f) {
                    addPieChartValues(new PieChart_Value(i, shares[i]));
                }
            }
            if (getPieChartValuesSize() == 0) {
                addPieChartValues(new PieChart_Value(-1, 1f));
            }
            sortAndBuild_PieChartValues();
        }

        private static float[] color(ZPartyCore.Party p) {
            int[] c = p.Color;
            if (c != null && c.length >= 3) {
                return new float[] {c255(c[0]), c255(c[1]), c255(c[2])};
            }
            int h = p.tag() == null ? 0 : Math.abs(p.tag().hashCode());
            return new float[] {(0.25f + (h % 7) * 0.10f), (0.25f + ((h / 7) % 7) * 0.10f), (0.25f + ((h / 49) % 7) * 0.10f)};
        }

        private static float c255(int v) {
            if (v < 0) v = 0;
            if (v > 255) v = 255;
            return v / 255f;
        }

        private int dataId(int i) {
            if (i < 0 || i >= getPieChartValuesSize()) return -1;
            PieChart_Value v = getPieChartValue(i);
            return v == null ? -1 : v.getDataID();
        }

        public float getPieChartValue_ColorR(int i) {
            int id = dataId(i);
            return id >= 0 && id < r.length ? r[id] : empty[0];
        }

        public float getPieChartValue_ColorG(int i) {
            int id = dataId(i);
            return id >= 0 && id < g.length ? g[id] : empty[1];
        }

        public float getPieChartValue_ColorB(int i) {
            int id = dataId(i);
            return id >= 0 && id < b.length ? b[id] : empty[2];
        }
    }

    /* ------------------------------------------------------------------- row */

    static final class PartyRow extends ButtonGame implements Mark {
        private final ZPartyCore core;
        private final int civ;
        private final String tag;
        private long lastCheck;
        private String lastText;

        PartyRow(ZPartyCore core, int civ, String tag, int x, int y, int w, int h) {
            super(text(core, civ, tag), CFG.FONT_REGULAR, -1, x, y, w, h, core.canAct(civ, tag));
            this.core = core;
            this.civ = civ;
            this.tag = tag;
            this.lastText = text(core, civ, tag);
        }

        private static String text(ZPartyCore core, int civ, String tag) {
            ZPartyCore.Party p = core.byTag(tag);
            String name = p == null ? tag : p.name();
            StringBuilder sb = new StringBuilder();
            sb.append(name).append("  ").append(ZPartyCore.percent(core.share(civ, tag)));
            String r = core.ruling(civ);
            if (tag.equals(r)) sb.append("  *");
            sb.append(core.actionStatus(civ, tag));
            return sb.toString();
        }

        public void draw(SpriteBatch oSB, int iTranslateX, int iTranslateY, boolean isActive, boolean scrollableY) {
            long f = core.frameNo();
            if (lastCheck == 0L || f - lastCheck >= 15L) {
                lastCheck = f;
                String t = text(core, civ, tag);
                if (!t.equals(lastText)) {
                    lastText = t;
                    try {
                        setText(t);
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    setClickable(core.canAct(civ, tag));
                } catch (Throwable ignored) {
                }
            }
            super.draw(oSB, iTranslateX, iTranslateY, isActive, scrollableY);
        }

        public void actionElement() {
            try {
                String err = core.startCampaign(civ, tag);
                if (err != null) {
                    toast(err);
                } else {
                    core.log("ui: campaign started -> " + tag + " (civ=" + civ + ")");
                }
            } catch (Throwable t) {
                core.log("row click: " + t);
            }
        }
    }

    static final class ActionRow extends ButtonGame implements Mark {
        private final ZPartyCore core;
        private final int civ;
        private final boolean force;
        private long lastCheck;
        private String lastText;

        ActionRow(ZPartyCore core, int civ, boolean force, int x, int y, int w, int h) {
            super(text(core, civ, force), CFG.FONT_REGULAR, -1, x, y, w, h,
                    force && core.canForceElection(civ));
            this.core = core;
            this.civ = civ;
            this.force = force;
            this.lastText = text(core, civ, force);
        }

        private static String text(ZPartyCore core, int civ, boolean force) {
            return force ? core.electionButtonText(civ) : core.nextElectionText(civ);
        }

        public void draw(SpriteBatch oSB, int iTranslateX, int iTranslateY, boolean isActive, boolean scrollableY) {
            long f = core.frameNo();
            if (lastCheck == 0L || f - lastCheck >= 15L) {
                lastCheck = f;
                String t = text(core, civ, force);
                if (!t.equals(lastText)) {
                    lastText = t;
                    try {
                        setText(t);
                    } catch (Throwable ignored) {
                    }
                }
                if (force) {
                    try {
                        setClickable(core.canForceElection(civ));
                    } catch (Throwable ignored) {
                    }
                }
            }
            super.draw(oSB, iTranslateX, iTranslateY, isActive, scrollableY);
        }

        public void actionElement() {
            if (!force) return;
            try {
                String err = core.forceElection(civ);
                if (err != null) toast(err);
            } catch (Throwable t) {
                core.log("election click: " + t);
            }
        }
    }

    private static void toast(String msg) {
        try {
            if (Game.menuManager != null) Game.menuManager.addToast(msg);
        } catch (Throwable ignored) {
        }
    }
}
