package com.saysky.zparty.hot;

import aoc.kingdoms.lukasz.jakowski.FileManager;
import aoc.kingdoms.lukasz.jakowski.Game;
import aoc.kingdoms.lukasz.map.IdeologiesManager;
import aoc.kingdoms.lukasz.map.civilization.Civilization;
import aoc.kingdoms.lukasz.map.civilization.CivilizationEventsData_Variables;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Json;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * ZParty runtime for the hot-plugin host (no method patching at all).
 *
 * <p>Everything is driven from the FRAME event:
 * <ul>
 *   <li>world identity change (Game.getCiv(0)) == new game / loaded save</li>
 *   <li>civ variable list scan == event commands (add_variable=zparty:...)</li>
 *   <li>variable mirroring == event triggers (has_variable=zparty:...)</li>
 *   <li>game day change == sidecar save</li>
 * </ul>
 *
 * <p>A party is only a wrapper around one Governments.json ideology; switching the
 * ruling party calls IdeologiesManager.changeGovernmentType(). Religion and culture
 * are never touched.
 */
final class ZPartyCore {

    static final int STATE_VERSION = 1;
    private static final String PREFIX = "zparty:";    /** persisted state inside the civ variable list (travels with the save game) */
    private static final String ST_SUPPORT = PREFIX + "s:"; /* zparty:s:<tag>:<permille> */
    private static final String ST_RULING = PREFIX + "r:";  /* zparty:r:<tag> */
    private static final String ST_CAMPAIGN = PREFIX + "c:"; /* zparty:c:<tag>:<daysLeft> */
    private static final String ST_COOLDOWN = PREFIX + "d:"; /* zparty:d:<untilDay> */
    private static final String ST_ELECTION = PREFIX + "e:"; /* zparty:e:<nextDay>:<cooldownDay> */

    /* ------------------------------------------------------------------ DTO */

    static final class Party {
        public String Tag;
        public String Name;
        /** Governments.json ideology this party maps to (same name as the game field) */
        public String ExtraTag;
        public String Desc;
        public int[] Color;
        /* legacy config / game\Parties.json names (still accepted) */
        public String Ideology;
        public String Extra_Tag;
        public String Ideology_Tag;
        public String Icon_Tag;

        String tag() {
            return Tag != null && Tag.length() > 0 ? Tag : Extra_Tag;
        }

        String ideology() {
            if (ExtraTag != null && ExtraTag.length() > 0) return ExtraTag;
            if (Ideology != null && Ideology.length() > 0) return Ideology;
            return Ideology_Tag;
        }

        String name() {
            return Name != null && Name.length() > 0 ? Name : tag();
        }
    }

    static final class ShareCfg {
        public String Tag;
        /** fixed value (“0.35”) or a range (“0.20~0.30”) rolled once per new game */
        public String Share;
    }

    static final class CountryCfg {
        public String Civ;
        public String Ruling;
        /** null = not written (inherit the DefaultCountry list); empty = only the ruling party */
        public List<ShareCfg> Support;
    }

    static final class Config {
        public boolean Enabled = true;
        public boolean ShowInGovernmentPage = true;
        /** where the widget is mounted: missions / missiontree / missions_court / government (or full class name) */
        public List<String> UiPages = new ArrayList<String>();
        public int MaxRows = 6;
        /** civ tags that get trigger variables mirrored; empty = player only */
        public List<String> MirrorCivs = new ArrayList<String>();
        /** support thresholds mirrored for has_variable=zparty:support:<tag>:<op>:<x> */
        public float[] SupportThresholds = new float[] {0.5f};

        /* ---- party campaign (click a row) ---- */
        /** what the campaign costs: legacy | gold | diplomacy */
        public String CostStat = "legacy";
        public float CostAmount = 100f;
        /** support gained per game day (0.02 = +2 percentage points) */
        public float SupportPerDay = 0.02f;
        /** campaign length in days */
        public int CampaignDays = 10;
        /** days the buttons stay silent after a campaign ends */
        public int CooldownDays = 30;

        /* ---- randomness ---- */
        /** chance that a campaign actually starts (0..1) */
        public float ActionSuccessChance = 0.75f;
        /** share of the cost refunded when a campaign fails (0..1) */
        public float ActionFailRefund = 0.5f;
        /** support the target party loses when a campaign fails */
        public float ActionFailPenalty = 0.01f;
        /** cooldown after a failure (0 = use CooldownDays) */
        public int ActionFailCooldownDays = 0;

        /* ---- elections ---- */
        public boolean ElectionsEnabled = true;
        /** automatic election every N days (0 = off) */
        public int ElectionIntervalDays = 365;
        public String ElectionCostStat = "diplomacy";
        public float ElectionCostAmount = 50f;
        /** cooldown of the "force early election" button */
        public int ElectionCooldownDays = 90;
        /** the early-election button is only usable when days-left <= this value
         *  (0 = election day only, negative = no limit) */
        public int ElectionMaxAdvanceDays = -1;
        /** weight = share^exponent (higher = favourites win more often) */
        public float ElectionWeightExponent = 1.0f;
        /** extra weight for the current ruling party (1 = none) */
        public float ElectionIncumbentBonus = 1.0f;
        /** random +-noise applied to each weight (0 = none, 0.5 = +-50%) */
        public float ElectionNoise = 0.35f;
        /** winner becomes the ruling party (changes the government) */
        public boolean ElectionChangeRuling = true;
        /** also run scheduled elections for AI civs (writes one variable per civ) */
        public boolean ElectionAllCivs = false;

        /* ---- polling / perf ---- */
        /** scan civ variable lists for zparty: commands every N frames */
        public int PollFrames = 8;
        /** refresh trigger mirror variables every N frames */
        public int MirrorFrames = 16;
        /** check whether the UI block is still mounted every N frames */
        public int UiCheckFrames = 8;
        public List<Party> Parties = new ArrayList<Party>();
        public List<CountryCfg> Countries = new ArrayList<CountryCfg>();
        /** template for every civ without its own Countries entry (Ruling is ignored) */
        public CountryCfg DefaultCountry;
    }

    /**
     * 独立的 AI 配置文件（{@code hotplugins/resources/<插件名>/zparty-ai.json}）。
     * 缺失则用本类默认值；不影响主配置的读取。
     */
    static final class AiConfig {
        /** 是否让 AI 参与政党系统（抖动/漂移/自愈/选举） */
        public boolean Enabled = true;
        /** AI 是否也定期选举（false = 只在漂移/自愈时按支持率直接换党） */
        public boolean Elections = false;
        /** 把 AI 政党状态写进存档变量（关 = 存档更小，AI 状态每次读档按模板重生成） */
        public boolean Persist = false;
        /** 初始支持率抖动（百分比，±40 = ±40%）——这是“多样性”的主要来源 */
        public int Jitter = 40;
        /** 漂移间隔（游戏日）；0 = 关闭漂移 */
        public int DriftDays = 60;
        /** 每次漂移转移的份额（0.02 = 2 个百分点） */
        public float DriftAmount = 0.02f;
        /** 每次漂移最多处理多少个 AI 国家（开销上限） */
        public int DriftCivs = 24;
        /** 每次最多修几个“无党籍政体”（每修一个 = 一次原版换政体任务，很贵） */
        public int FixMax = 4;
        /** 漂移方向偏向次强党的概率（0.5 = 完全随机） */
        public float DriftBias = 0.6f;
        /** AI 自己的选举周期；0 = 用主配置的 ElectionIntervalDays */
        public int ElectionIntervalDays = 0;
        /** AI 首次大选的随机错峰上限（天），避免全世界同一天投票 */
        public int ElectionDelayDays = 240;
        /** 任意政党最低份额（漂移/抖动不会把它压到 0 以下） */
        public float MinShare = 0.01f;
        /** 政体不属于任何政党（例如被原版 AI 改成“临时政府”）时，按支持率自动纠正 */
        public boolean FixStrayGovernments = true;
        /**
         * 覆写主配置的 {@code PollFrames}（事件命令扫描间隔）：0 = 跟随主配置。
         * 调大 = 扫描更省，代价是 add_variable 效果最多延迟这么多帧。
         */
        public int PollFrames = 0;
    }

    static final class Nation {
        int civ;
        String ruling;
        final LinkedHashMap<String, Float> support = new LinkedHashMap<String, Float>();
        /** running campaign: party tag + remaining days */
        String campaignParty;
        int campaignLeft;
        int campaignTotal;
        /** buttons are silent until this game-day value */
        int cooldownDay;
        /** next automatic election (game day) */
        int nextElectionDay;
        /** "force election" button is silent until this game day */
        int electionCooldownDay;
        /** pending vanilla changeGovernmentType cost refund */
        int refundIdeology = -1;
        float refundGold;
        float refundLegacy;
        int refundWait;
    }

    /* ---------------------------------------------------------------- state */

    private final com.saysky.hotplugloader.api.HotContext ctx;
    private Config config;
    private final LinkedHashMap<Integer, Nation> nations = new LinkedHashMap<Integer, Nation>();
    private final LinkedHashMap<Integer, HashSet<String>> mirrored = new LinkedHashMap<Integer, HashSet<String>>();
    private Object world;
    private long revision = 1L;
    private boolean dirty;
    /** number of nations with a pre-credited vanilla government-change cost */
    private int govCreditPending;
    /** HotVarApi 事件桥已尝试注册（无论成功与否都不再重试） */
    private boolean hotVarHooked;
    /** 独立 AI 配置（zparty-ai.json） */
    private AiConfig aiConfig;
    /** 上次 AI 漂移的游戏日 / 轮询游标 */
    private int lastAiDay;
    private int aiCursor = 1;
    private int lastDay = -1;
    private volatile long frameNo;
    private boolean defaultLogged;
    private boolean legacyDefaultWarned;
    private final java.util.Random rnd = new java.util.Random();
    private Field fElements;
    private Field fCount;

    ZPartyCore(com.saysky.hotplugloader.api.HotContext ctx) {
        this.ctx = ctx;
    }

    /* -------------------------------------------------------------- logging */

    void log(String s) {
        try {
            ctx.log(s);
        } catch (Throwable ignored) {
        }
    }

    long revision() {
        return revision;
    }

    long frameNo() {
        return frameNo;
    }

    /* --------------------------------------------------------------- config */

    private static Json json() {
        Json j = new Json();
        j.setIgnoreUnknownFields(true);
        j.setUsePrototypes(false);
        return j;
    }

    void reloadConfig() {
        config = null;
        aiConfig = null;
        lastAiDay = 0;
        aiCursor = 1;
        nations.clear();
        mirrored.clear();
        world = null;
        log("config reloaded");
    }

    Config config() {
        if (config == null) config = readConfig();
        return config;
    }

    /** 独立的 AI 配置：缺失文件时全部用默认值（相当于“AI 全开但不下发选举”）。 */
    AiConfig ai() {
        if (aiConfig == null) aiConfig = readAiConfig();
        return aiConfig;
    }

    private AiConfig readAiConfig() {
        AiConfig a = null;
        try {
            File own = new File(ctx.dataDir(), "zparty-ai.json");
            if (own.exists()) {
                a = json().fromJson(AiConfig.class, new FileHandle(own).readString("UTF-8"));
                if (a != null) log("config: dataDir/zparty-ai.json");
            }
        } catch (Throwable t) {
            log("config dataDir/zparty-ai.json failed: " + t);
        }
        if (a == null) {
            a = new AiConfig();
            log("config: zparty-ai.json missing, AI defaults in use");
        }
        if (a.DriftCivs < 0) a.DriftCivs = 0;
        if (a.FixMax < 0) a.FixMax = 0;
        if (a.FixMax > 32) a.FixMax = 32;
        if (a.DriftDays < 0) a.DriftDays = 0;
        if (a.Jitter < 0) a.Jitter = 0;
        if (a.Jitter > 95) a.Jitter = 95;
        if (a.MinShare < 0f) a.MinShare = 0f;
        if (a.MinShare > 0.2f) a.MinShare = 0.2f;
        if (a.PollFrames < 0) a.PollFrames = 0;
        if (a.PollFrames > 600) a.PollFrames = 600;
        return a;
    }

    private Config readConfig() {
        Config c = null;
        /* 1) plugin private config (hotplugins/resources/<id>/zparty.json) */
        try {
            File own = new File(ctx.dataDir(), "zparty.json");
            if (own.exists()) {
                c = json().fromJson(Config.class, new FileHandle(own).readString("UTF-8"));
                if (c != null) log("config: dataDir/zparty.json");
            }
        } catch (Throwable t) {
            log("config dataDir failed: " + t);
        }
        /* 2) game resource (override/rainfall/zparty.json) */
        if (c == null) {
            try {
                FileHandle fh = FileManager.loadFile("rainfall/zparty.json");
                if (fh != null && fh.exists()) {
                    c = json().fromJson(Config.class, fh.readString("UTF-8"));
                    if (c != null) log("config: rainfall/zparty.json");
                }
            } catch (Throwable t) {
                log("config zparty.json failed: " + t);
            }
        }
        if (c == null) {
            try {
                FileHandle fh = FileManager.loadFile("game/Parties.json");
                if (fh != null && fh.exists()) {
                    c = json().fromJson(Config.class, fh.readString("UTF-8"));
                    if (c != null) log("config: game/Parties.json (fallback)");
                }
            } catch (Throwable t) {
                log("config Parties.json failed: " + t);
            }
        }
        if (c == null) {
            c = new Config();
            c.Enabled = false;
        }
        if (c.Parties == null) c.Parties = new ArrayList<Party>();
        if (c.Countries == null) c.Countries = new ArrayList<CountryCfg>();
        if (c.MirrorCivs == null) c.MirrorCivs = new ArrayList<String>();
        if (c.UiPages == null) c.UiPages = new ArrayList<String>();
        if (c.UiPages.size() == 0) c.UiPages.add("government");
        if (c.SupportThresholds == null || c.SupportThresholds.length == 0) {
            c.SupportThresholds = new float[] {0.5f};
        }
        if (c.MaxRows <= 0) c.MaxRows = 6;

        List<Party> keep = new ArrayList<Party>();
        for (int i = 0; i < c.Parties.size(); i++) {
            Party p = c.Parties.get(i);
            if (p == null) continue;
            if (p.tag() == null || p.tag().length() == 0) {
                log("party skipped (no Tag/Extra_Tag) #" + i);
                continue;
            }
            if (p.ideology() == null || p.ideology().length() == 0) {
                log("party skipped (no ExtraTag) " + p.tag());
                continue;
            }
            keep.add(p);
        }
        c.Parties = keep;
        return c;
    }

    boolean active() {
        Config c = config();
        return c != null && c.Enabled && c.Parties.size() > 0;
    }

    boolean showInPage() {
        Config c = config();
        return c != null && c.ShowInGovernmentPage;
    }

    int uiCheckFrames() {
        Config c = config();
        return c == null ? 8 : Math.max(1, c.UiCheckFrames);
    }

    /** Maps UiPages entries to menu class names. */
    String[] uiPageClasses() {
        Config c = config();
        if (c == null || c.UiPages == null) return new String[0];
        String[] out = new String[c.UiPages.size()];
        for (int i = 0; i < c.UiPages.size(); i++) {
            out[i] = pageClass(c.UiPages.get(i));
        }
        return out;
    }

    private static String pageClass(String key) {
        if (key == null) return "";
        String k = key.trim();
        if (k.indexOf('.') > 0) return k; /* full class name */
        String low = k.toLowerCase(Locale.ROOT);
        if (low.equals("missions")) return "aoc.kingdoms.lukasz.menusInGame.InGame_Missions";
        if (low.equals("missiontree")) return "aoc.kingdoms.lukasz.menusInGame.InGame_MissionTree";
        if (low.equals("missions_court")) return "aoc.kingdoms.lukasz.menusInGame.Court.InGame_Court_Missions";
        if (low.equals("government")) return "aoc.kingdoms.lukasz.menusInGame.Court.InGame_Court_Government";
        if (low.equals("civ") || low.equals("civ_government")) return "aoc.kingdoms.lukasz.menusInGame.Civ.InGame_Civ_Government";
        return k;
    }

    List<Party> parties() {
        return config().Parties;
    }

    Party byTag(String tag) {
        if (tag == null) return null;
        List<Party> list = parties();
        for (int i = 0; i < list.size(); i++) {
            if (tag.equals(list.get(i).tag())) return list.get(i);
        }
        return null;
    }

    int indexOf(String tag) {
        if (tag == null) return -1;
        List<Party> list = parties();
        for (int i = 0; i < list.size(); i++) {
            if (tag.equals(list.get(i).tag())) return i;
        }
        return -1;
    }

    /**
     * Parties that really exist in this country: a party is present when it is listed in
     * the config {@code Support} (Countries[].Support / DefaultCountry.Support) or when it
     * is the ruling party. {@code Parties} is only the tag -&gt; name/color/ExtraTag index.
     * Result is ordered by support, biggest first.
     */
    List<Party> partiesFor(int civ) {
        List<Party> out = new ArrayList<Party>();
        Nation n = nation(civ);
        if (n == null) return out;
        List<Party> all = parties();
        for (int i = 0; i < all.size(); i++) {
            Party p = all.get(i);
            if (p == null) continue;
            String t = p.tag();
            Float f = n.support.get(t);
            float v = f == null ? 0f : f.floatValue();
            boolean ruling = n.ruling != null && n.ruling.equalsIgnoreCase(t);
            if (v > 0.0001f || ruling) out.add(p);
        }
        /* insertion sort by support desc (tiny list) */
        for (int i = 1; i < out.size(); i++) {
            Party p = out.get(i);
            float v = shareOf(n, p.tag());
            int j = i - 1;
            while (j >= 0 && shareOf(n, out.get(j).tag()) < v) {
                out.set(j + 1, out.get(j));
                j--;
            }
            out.set(j + 1, p);
        }
        return out;
    }

    private static float shareOf(Nation n, String tag) {
        if (n == null || tag == null) return 0f;
        Float f = n.support.get(tag);
        return f == null ? 0f : f.floatValue();
    }

    /* ------------------------------------------------- event trigger bridge */

    /**
     * Registers a live trigger provider with the LoaderSettings HotVarApi patch
     * (com.saysky.hotvar.HotVars), so events can ask
     * {@code has_variable=zparty:support:mon:gt:0.50} without mirroring variables.
     * Purely optional: when the patch is not installed we keep using the variables.
     */
    void hookEventApi() {
        if (hotVarHooked) return;
        try {
            Class<?> hv = Class.forName("com.saysky.hotvar.HotVars");
            java.lang.reflect.Method reg = hv.getMethod("register", String.class, Object.class);
            reg.invoke(null, "zparty:", this);
            reg.invoke(null, "zanparty:", this);
            hotVarHooked = true;
            log("event api: live triggers registered (HotVarApi installed)");
        } catch (Throwable t) {
            hotVarHooked = true; /* do not retry every frame */
            log("event api: HotVarApi not installed, variable mirror only ("
                    + t.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Called reflectively by {@code com.saysky.hotvar.HotVars} for every trigger string
     * that starts with {@code zparty:} / {@code zanparty:}.
     *
     * <p>Supported forms:</p>
     * <ul>
     *   <li>{@code zparty:ruling:<party>}</li>
     *   <li>{@code zparty:support:<party>:<gt|ge|lt|le|eq|ne>:<0..1>}</li>
     * </ul>
     */
    public boolean test(String var, int civID) {
        try {
            if (var == null || civID <= 0) return false;
            String s = var;
            if (s.startsWith("zanparty:")) s = s.substring(9);
            else if (s.startsWith("zparty:")) s = s.substring(7);
            else return false;
            if (s.length() == 0) return false;
            if (s.startsWith("ruling:")) {
                String tag = s.substring(7).trim();
                if (tag.length() == 0) return false;
                String r = ruling(civID);
                return r != null && r.equalsIgnoreCase(tag);
            }
            if (s.startsWith("support:")) {
                int a = s.indexOf(':', 8);
                if (a < 0) return false;
                String tag = s.substring(8, a).trim();
                int b = s.indexOf(':', a + 1);
                if (b < 0) return false;
                String op = s.substring(a + 1, b).trim();
                float x = parseFloat(s.substring(b + 1).trim(), -1f);
                if (x < 0f) return false;
                if (byTag(tag) == null) return false;
                float v = share(civID, tag);
                if (op.equalsIgnoreCase("gt")) return v > x;
                if (op.equalsIgnoreCase("ge")) return v >= x;
                if (op.equalsIgnoreCase("lt")) return v < x;
                if (op.equalsIgnoreCase("le")) return v <= x;
                if (op.equalsIgnoreCase("eq")) return Math.abs(v - x) <= 0.005f;
                if (op.equalsIgnoreCase("ne")) return Math.abs(v - x) > 0.005f;
                return false;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /* ------------------------------------------------------------- ideology */

    int ideologyId(String ideologyTag) {
        IdeologiesManager m = Game.ideologiesManager;
        if (m == null || ideologyTag == null) return -1;
        String want = ideologyTag.trim();
        if (want.length() == 0) return -1;
        try {
            int n = m.getIdeologiesSize();
            /*
             * NOTE: IdeologiesManager.getIdeologyID(String) is meant for *civ tags*
             * ("rus_\u51c6") and returns 0 (= the first ideology, e.g. \u4e34\u65f6\u653f\u5e9c) when
             * the string does not look like one - never use it for a bare Extra_Tag.
             */
            for (int i = 0; i < n; i++) {
                IdeologiesManager.Ideology io = m.getIdeology(i);
                if (io == null) continue;
                if (want.equals(io.Extra_Tag)) return i;
            }
            for (int i = 0; i < n; i++) {
                IdeologiesManager.Ideology io = m.getIdeology(i);
                if (io == null || io.Extra_Tag == null) continue;
                if (io.Extra_Tag.length() > 0 && want.equalsIgnoreCase(io.Extra_Tag)) return i;
            }
            for (int i = 0; i < n; i++) {
                IdeologiesManager.Ideology io = m.getIdeology(i);
                if (io == null) continue;
                if (want.equals(io.Name)) return i;
            }
        } catch (Throwable t) {
            log("ideologyId failed: " + t);
            return -1;
        }
        log("ideologyId: no ideology matches '" + want + "' (see Governments.json Extra_Tag)");
        return -1;
    }

    String currentIdeologyTag(int civ) {
        try {
            Civilization c = Game.getCiv(civ);
            IdeologiesManager m = Game.ideologiesManager;
            if (c == null || m == null) return "";
            IdeologiesManager.Ideology io = m.getIdeology(c.getIdeologyID());
            if (io == null || io.Extra_Tag == null) return "";
            return io.Extra_Tag;
        } catch (Throwable t) {
            return "";
        }
    }

    String currentPartyTag(int civ) {
        String tag = currentIdeologyTag(civ);
        if (tag.length() == 0) return null;
        List<Party> list = parties();
        for (int i = 0; i < list.size(); i++) {
            if (tag.equals(list.get(i).ideology())) return list.get(i).tag();
        }
        if (tag.startsWith("_")) {
            String s = tag.substring(1);
            for (int i = 0; i < list.size(); i++) {
                if (s.equals(list.get(i).ideology())) return list.get(i).tag();
            }
        }
        return null;
    }

    /* --------------------------------------------------------------- nation */

    Nation nation(int civ) {
        Nation n = nations.get(Integer.valueOf(civ));
        if (n != null) return n;
        if (!active()) return null;
        n = newNation(civ);
        String derived = currentPartyTag(civ);
        n.ruling = derived;
        if (n.ruling != null) n.support.put(n.ruling, Float.valueOf(1f));
        applyConfigCountry(n);
        nations.put(Integer.valueOf(civ), n);
        return n;
    }

    private static Nation newNation(int civ) {
        Nation n = new Nation();
        n.civ = civ;
        return n;
    }

    private void applyConfigCountry(Nation n) {
        Config c = config();
        if (c == null || n == null) return;
        String civTag = civTag(n.civ);
        CountryCfg cc = findCountry(c, civTag);
        if (cc == null) return;
        boolean isDefault = (cc == c.DefaultCountry);

        /* which support template applies:
           - the entry writes Support (even an empty array) -> that one
             ([] = only the ruling party exists = single party state)
           - the entry only writes Ruling -> DefaultCountry template */
        List<ShareCfg> tpl = cc.Support;
        if (tpl == null && !isDefault && c.DefaultCountry != null) tpl = c.DefaultCountry.Support;

        n.support.clear();
        if (!isDefault && cc.Ruling != null && cc.Ruling.length() > 0) {
            n.ruling = cc.Ruling;
        }
        if (tpl != null) {
            applySupportTemplate(n, tpl);
        }
        if (n.support.size() == 0 && n.ruling != null) {
            n.support.put(n.ruling, Float.valueOf(1f));
        }
        jitterAi(n);
        if (isDefault && !defaultLogged) {
            defaultLogged = true;
            log("config: DefaultCountry template in use (ruling party keeps the rest)");
        }
    }

    /* -------------------------------------------------------------------- AI */

    private static int playerCiv() {
        try {
            return Game.player == null ? -1 : Game.player.iCivID;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 给 AI 国家的初始支持率加随机抖动（多样性的主要来源）。 */
    private void jitterAi(Nation n) {
        AiConfig a = ai();
        if (n == null || !a.Enabled || a.Jitter <= 0 || n.support.isEmpty()) return;
        if (isPlayer(n.civ)) return;
        float j = a.Jitter / 100f;
        List<Party> list = parties();
        for (int i = 0; i < list.size(); i++) {
            String t = list.get(i).tag();
            if (n.ruling != null && n.ruling.equalsIgnoreCase(t)) continue;
            Float cur = n.support.get(t);
            if (cur == null) continue;
            float k = 1f + j * (rnd.nextFloat() * 2f - 1f);
            float v = cur.floatValue() * k;
            if (v < a.MinShare) v = a.MinShare;
            n.support.put(t, Float.valueOf(round2(clamp(v))));
        }
        normalize(n);
    }

    /**
     * AI 推进：每 {@code DriftDays} 天处理一批国家
     * —— 政体不属于任何政党的（例如被原版 AI 改成“临时政府”）先按支持率纠正；
     * 其余做一步支持率漂移，使各国长期出现不同的执政党。
     */
    private void tickAi(int today) {
        AiConfig a = ai();
        if (!a.Enabled || !active()) return;
        boolean fix = a.FixStrayGovernments;
        boolean drift = a.DriftDays > 0 && a.DriftAmount > 0f && a.DriftCivs > 0;
        if (!fix && !drift) return;
        int interval = a.DriftDays > 0 ? a.DriftDays : 30;
        if (lastAiDay != 0 && today - lastAiDay < interval) return;
        lastAiDay = today;
        int total;
        try {
            total = Game.getCivsSize();
        } catch (Throwable t) {
            return;
        }
        int p = playerCiv();
        int budget = Math.max(1, a.DriftCivs);
        int fixBudget = a.FixMax;
        int fixed = 0;
        int done = 0;
        int guard = 0;
        while (done < budget && guard < total) {
            guard++;
            if (aiCursor <= 0 || aiCursor >= total) aiCursor = 1;
            int civ = aiCursor++;
            if (civ <= 0 || civ == p) continue;
            try {
                Civilization c = Game.getCiv(civ);
                if (c == null || c.getNumOfProvinces() <= 0) continue;
                /* 检查很便宜，修复很贵（会排一个原版换政体任务）→ 单独限流 */
                if (fix && fixed < fixBudget && fixStray(civ)) {
                    fixed++;
                    done++;
                    continue;
                }
                if (drift) driftOne(civ);
                done++;
            } catch (Throwable t) {
                /* 单国异常不影响其余 */
            }
        }
        if (done > 0) {
            touch();
            /* AI 状态默认不进存档，就别让 persistAll 白跑 */
            if (a.Persist) dirty = true;
        }
    }

    /** AI 政体不属于任何政党 → 按当前支持率把最强党推上去。 */
    private boolean fixStray(int civ) {
        if (isPlayer(civ)) return false;
        if (currentPartyTag(civ) != null) return false;
        List<Party> present = partiesFor(civ);
        if (present.isEmpty()) return false;
        Party best = present.get(0); /* partiesFor 按支持率降序 */
        if (best == null) return false;
        log("ai: civ=" + civ + " government not mapped to a party -> restore " + best.tag());
        setRuling(civ, best.tag());
        return true;
    }

    /** 一步支持率漂移：从最强的非执政党向另一个党（偏次强）转移一小块。 */
    private void driftOne(int civ) {
        AiConfig a = ai();
        Nation n = nation(civ);
        if (n == null) return;
        List<Party> present = partiesFor(civ);
        if (present.size() < 2) return;
        Party src = null;
        float best = -1f;
        for (int i = 0; i < present.size(); i++) {
            Party p = present.get(i);
            if (n.ruling != null && n.ruling.equals(p.tag())) continue;
            float v = shareOf(n, p.tag());
            if (v > best) {
                best = v;
                src = p;
            }
        }
        if (src == null) return;
        Party dst = null;
        if (rnd.nextFloat() < a.DriftBias) {
            float second = -1f;
            for (int i = 0; i < present.size(); i++) {
                Party p = present.get(i);
                if (p == src) continue;
                float v = shareOf(n, p.tag());
                if (v > second) {
                    second = v;
                    dst = p;
                }
            }
        } else {
            dst = present.get(rnd.nextInt(present.size()));
        }
        if (dst == null || dst == src) return;
        float move = Math.min(shareOf(n, src.tag()), a.DriftAmount);
        if (move <= 0f) return;
        float sv = shareOf(n, src.tag()) - move;
        if (sv < a.MinShare) sv = a.MinShare;
        n.support.put(src.tag(), Float.valueOf(round2(sv)));
        n.support.put(dst.tag(), Float.valueOf(round2(shareOf(n, dst.tag()) + move)));
        normalize(n);
    }

    private static boolean isDefaultCiv(String civ) {
        return civ != null && civ.trim().equalsIgnoreCase("default");
    }

    private CountryCfg findCountry(Config c, String civTag) {
        if (civTag != null) {
            for (int i = 0; i < c.Countries.size(); i++) {
                CountryCfg cc = c.Countries.get(i);
                if (cc == null || cc.Civ == null) continue;
                if (cc.Civ.trim().equalsIgnoreCase(civTag)) return cc;
            }
            String base = baseTag(civTag);
            if (base != null && !base.equalsIgnoreCase(civTag)) {
                for (int i = 0; i < c.Countries.size(); i++) {
                    CountryCfg cc = c.Countries.get(i);
                    if (cc == null || cc.Civ == null) continue;
                    if (cc.Civ.trim().equalsIgnoreCase(base)) return cc;
                }
            }
        }
        return c.DefaultCountry;
    }

    /** "ger_\u666e" -> "ger": the game appends the ideology Extra_Tag to the civ tag. */
    static String baseTag(String t) {
        if (t == null) return null;
        int i = t.indexOf('_');
        return i > 0 ? t.substring(0, i) : t;
    }

    /**
     * "others" come from the config; the ruling party takes the rest (at least 35%),
     * so the government of the map never loses its dominant support.
     */
    private void applySupportTemplate(Nation n, List<ShareCfg> shares) {
        String rulingTag = n.ruling != null ? n.ruling : currentPartyTag(n.civ);
        n.support.clear();
        float others = 0f;
        for (int k = 0; shares != null && k < shares.size(); k++) {
            ShareCfg sc = shares.get(k);
            if (sc == null || sc.Tag == null) continue;
            if (rulingTag != null && sc.Tag.trim().equalsIgnoreCase(rulingTag)) continue;
            float v = clamp(shareValue(sc.Share));
            if (v <= 0f) continue;
            n.support.put(sc.Tag.trim(), Float.valueOf(v));
            others += v;
        }
        float rest = 1f - others;
        if (rest < 0.35f) rest = 0.35f;
        if (rulingTag != null) {
            n.ruling = rulingTag;
            n.support.put(rulingTag, Float.valueOf(rest));
        }
        normalize(n);
    }

    String civTag(int civ) {
        try {
            Civilization c = Game.getCiv(civ);
            return c == null ? null : c.getCivTag();
        } catch (Throwable t) {
            return null;
        }
    }

    String ruling(int civ) {
        Nation n = nation(civ);
        return n == null ? null : n.ruling;
    }

    String rulingName(int civ) {
        Nation n = nation(civ);
        if (n == null || n.ruling == null) return "";
        Party p = byTag(n.ruling);
        return p == null ? n.ruling : p.name();
    }

    float share(int civ, String tag) {
        Nation n = nation(civ);
        if (n == null || tag == null) return 0f;
        Float f = n.support.get(tag);
        return f == null ? 0f : f.floatValue();
    }

    float[] shares(int civ) {
        List<Party> list = parties();
        float[] out = new float[list.size()];
        Nation n = nation(civ);
        if (n == null) return out;
        float total = 0f;
        for (int i = 0; i < list.size(); i++) {
            Float f = n.support.get(list.get(i).tag());
            out[i] = f == null ? 0f : f.floatValue();
            total += out[i];
        }
        if (total <= 0.0001f) {
            int idx = indexOf(n.ruling);
            if (idx >= 0) out[idx] = 1f;
            return out;
        }
        for (int i = 0; i < out.length; i++) out[i] = out[i] / total;
        return out;
    }

    private static float clamp(float f) {
        if (f != f) return 0f;
        if (f < 0f) return 0f;
        if (f > 1f) return 1f;
        return f;
    }

    /**
     * Parses a support share: {@code "0.35"} (fixed) or {@code "0.20~0.30"} (random).
     * A range is rolled in 0.01 steps and rounded to 2 decimals, so float drift can never
     * show up in the UI or in the mirrored trigger variables.
     * <p>Called once when a nation state is created (= new game / first mention), the
     * rolled value then goes into the save, so loading a game never re-rolls it.</p>
     */
    private float shareValue(String raw) {
        if (raw == null) return 0f;
        String s = raw.trim();
        if (s.length() == 0) return 0f;
        int sep = s.indexOf('~');
        if (sep < 0) sep = s.indexOf('\uFF5E'); /* full width tilde */
        if (sep < 0) return clamp(parseFloat(s, 0f));
        float a = parseFloat(s.substring(0, sep), 0f);
        float b = parseFloat(s.substring(sep + 1), 0f);
        float lo = clamp(Math.min(a, b));
        float hi = clamp(Math.max(a, b));
        int steps = Math.round((hi - lo) * 100f);
        if (steps <= 0) return round2(lo);
        return round2(clamp(lo + rnd.nextInt(steps + 1) * 0.01f));
    }

    private static float parseFloat(String s, float def) {
        try {
            return Float.parseFloat(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static float round2(float v) {
        return Math.round(v * 100f) / 100f;
    }

    private void normalize(Nation n) {
        if (n == null) return;
        List<Party> list = parties();
        float total = 0f;
        for (int i = 0; i < list.size(); i++) {
            String t = list.get(i).tag();
            Float f = n.support.get(t);
            if (f == null) continue;
            float v = clamp(f.floatValue());
            if (v <= 0f) n.support.remove(t);
            else {
                n.support.put(t, Float.valueOf(v));
                total += v;
            }
        }
        if (total <= 0f) return;
        for (int i = 0; i < list.size(); i++) {
            String t = list.get(i).tag();
            Float f = n.support.get(t);
            if (f == null) continue;
            n.support.put(t, Float.valueOf(f.floatValue() / total));
        }
    }

    private void touch() {
        revision++;
        dirty = true;
    }

    /* ----------------------------------------------------------- government */

    /** current vanilla price of a government change (Electoral changes are free). */
    private static float govCost(boolean gold) {
        try {
            return gold ? aoc.kingdoms.lukasz.jakowski.GameValues.government.CHANGE_GOVERNMENT_COST
                    : aoc.kingdoms.lukasz.jakowski.GameValues.government.CHANGE_GOVERNMENT_COST_LEGACY;
        } catch (Throwable t) {
            return 0f;
        }
    }

    /**
     * The vanilla {@code changeGovernmentType} adds a task that always deducts
     * {@code CHANGE_GOVERNMENT_COST(_LEGACY)} (even with the "free" flag), so we credit
     * the amount up-front; the task then costs nothing. If the task never runs the credit
     * is rolled back. Elections therefore change the government for free.
     */
    private void settleGovCredit() {
        if (govCreditPending == 0) return;
        int left = 0;
        /* no defensive copy: this runs on frames where a switch is in flight */
        for (Nation n : nations.values()) {
            if (n == null || n.refundIdeology < 0) continue;
            try {
                Civilization c = Game.getCiv(n.civ);
                if (c != null && c.getIdeologyID() == n.refundIdeology) {
                    log("gov switch paid by plugin: civ=" + n.civ + " (gold " + n.refundGold
                            + " / legacy " + n.refundLegacy + " neutralised)");
                    n.refundIdeology = -1;
                    continue;
                }
                if (n.refundWait-- <= 0) {
                    if (c != null) {
                        if (n.refundGold > 0f) c.fGold -= n.refundGold;
                        if (n.refundLegacy > 0f) c.fLegacy -= n.refundLegacy;
                    }
                    log("gov switch never happened: civ=" + n.civ + " credit rolled back");
                    n.refundIdeology = -1;
                    continue;
                }
                left++;
            } catch (Throwable ignored) {
                n.refundIdeology = -1;
            }
        }
        govCreditPending = left;
    }

    /** Deducts nothing and keeps the ruling party in sync after an election. */
    boolean setRuling(int civ, String tag) {
        try {
            if (!active() || civ <= 0) return false;
            Party p = byTag(tag);
            if (p == null) {
                log("setRuling: unknown party " + tag);
                return false;
            }
            int id = ideologyId(p.ideology());
            if (id < 0) {
                log("setRuling: ideology not found for " + tag + " (" + p.ideology() + ")");
                return false;
            }
            Civilization c = Game.getCiv(civ);
            if (c == null) return false;
            Nation n = nation(civ);
            if (n == null) return false;
            if (c.getIdeologyID() != id) {
                IdeologiesManager m = Game.ideologiesManager;
                if (m == null) return false;
                /* the vanilla task deducts CHANGE_GOVERNMENT_COST(_LEGACY) even with the
                   "free" flag -> credit it first so the civ never goes negative */
                float costGold = govCost(true);
                float costLegacy = govCost(false);
                n.refundIdeology = id;
                n.refundGold = costGold;
                n.refundLegacy = costLegacy;
                n.refundWait = 900;
                govCreditPending++;
                if (costGold > 0f) c.fGold += costGold;
                if (costLegacy > 0f) c.fLegacy += costLegacy;
                if (!m.changeGovernmentType(civ, id, true)) {
                    if (costGold > 0f) c.fGold -= costGold;
                    if (costLegacy > 0f) c.fLegacy -= costLegacy;
                    n.refundIdeology = -1;
                    n.refundWait = 0;
                    if (govCreditPending > 0) govCreditPending--;
                    log("setRuling: changeGovernmentType rejected civ=" + civ + " id=" + id);
                    return false;
                }
            }
            String previous = n.ruling;
            n.ruling = tag;
            Float cur = n.support.get(tag);
            if (cur == null || cur.floatValue() <= 0.0001f) {
                /* the new ruling party inherits the old government's support instead of
                   leaving a 0% ruling party next to a 100% ex-ruling party */
                Float prev = previous == null ? null : n.support.remove(previous);
                n.support.put(tag, Float.valueOf(prev != null && prev.floatValue() > 0f ? prev.floatValue() : 1f));
            }
            normalize(n);
            touch();
            mirrorOne(civ);
            return true;
        } catch (Throwable t) {
            log("setRuling failed: " + t);
            return false;
        }
    }

    private boolean setShare(int civ, String tag, float v) {
        Nation n = nation(civ);
        if (n == null || byTag(tag) == null) return false;
        n.support.put(tag, Float.valueOf(clamp(v)));
        normalize(n);
        touch();
        return true;
    }

    private boolean addShare(int civ, String tag, float d) {
        Nation n = nation(civ);
        if (n == null || byTag(tag) == null) return false;
        Float cur = n.support.get(tag);
        n.support.put(tag, Float.valueOf(clamp((cur == null ? 0f : cur.floatValue()) + d)));
        normalize(n);
        touch();
        return true;
    }

    private boolean transferShare(int civ, String from, String to, float d) {
        Nation n = nation(civ);
        if (n == null || byTag(from) == null || byTag(to) == null) return false;
        if (from.equals(to)) return true;
        if (d < 0f) d = 0f;
        Float cf = n.support.get(from);
        float f = cf == null ? 0f : cf.floatValue();
        if (f - d < 0f) d = f;
        Float ct = n.support.get(to);
        float t = ct == null ? 0f : ct.floatValue();
        n.support.put(from, Float.valueOf(f - d));
        n.support.put(to, Float.valueOf(t + d));
        normalize(n);
        touch();
        return true;
    }

    private boolean distribution(int civ, String[] tags, float[] vals) {
        Nation n = nation(civ);
        if (n == null || tags == null || vals == null || tags.length != vals.length) return false;
        for (int i = 0; i < tags.length; i++) {
            if (byTag(tags[i]) == null) return false;
        }
        n.support.clear();
        for (int i = 0; i < tags.length; i++) {
            n.support.put(tags[i], Float.valueOf(clamp(vals[i])));
        }
        normalize(n);
        touch();
        return true;
    }

    /* --------------------------------------------------------------- events */

    boolean reserved(String v) {
        if (v == null) return false;
        String s = v.trim();
        if (s.length() < 4) return false;
        String low = s.toLowerCase(Locale.ROOT);
        return low.startsWith(PREFIX) || low.startsWith("zp_");
    }

    private String[] split(String value) {
        String v = value.trim();
        String low = v.toLowerCase(Locale.ROOT);
        if (low.startsWith(PREFIX)) v = v.substring(PREFIX.length());
        else if (low.startsWith("zp_")) v = v.substring(3);
        return v.split(":", -1);
    }

    private static float num(String s) {
        try {
            return Float.parseFloat(s.trim());
        } catch (Throwable t) {
            return 0f;
        }
    }

    private void execute(int civ, String value) {
        try {
            if (!active()) return;
            String[] p = split(value);
            if (p.length == 0) return;
            String key = p[0].trim().toLowerCase(Locale.ROOT);
            if (key.equals("ruling")) {
                if (p.length >= 2) setRuling(civ, p[1].trim());
            } else if (key.equals("set")) {
                if (p.length >= 3) setShare(civ, p[1].trim(), num(p[2]));
            } else if (key.equals("add")) {
                if (p.length >= 3) addShare(civ, p[1].trim(), num(p[2]));
            } else if (key.equals("transfer")) {
                if (p.length >= 4) transferShare(civ, p[1].trim(), p[2].trim(), num(p[3]));
            } else if (key.equals("distribution")) {
                int pairs = (p.length - 1) / 2;
                if (pairs <= 0) return;
                String[] tags = new String[pairs];
                float[] vals = new float[pairs];
                for (int i = 0; i < pairs; i++) {
                    tags[i] = p[1 + i * 2].trim();
                    vals[i] = num(p[2 + i * 2]);
                }
                distribution(civ, tags, vals);
            } else {
                log("unknown command: " + value);
            }
            mirrorOne(civ);
        } catch (Throwable t) {
            log("execute failed: " + value + " / " + t);
        }
    }

    private static boolean compare(float v, String op, float x) {
        if ("gt".equals(op)) return v > x;
        if ("ge".equals(op)) return v >= x;
        if ("eq".equals(op)) return Math.abs(v - x) < 0.0005f;
        if ("ne".equals(op)) return Math.abs(v - x) >= 0.0005f;
        if ("le".equals(op)) return v <= x;
        if ("lt".equals(op)) return v < x;
        return false;
    }

    /* ------------------------------------------------------- world lifecycle */

    /** On a brand new game, apply Countries[].Ruling by really changing the government. */
    private void applyCountriesConfig() {
        try {
            Config cfg = config();
            if (cfg == null) return;
            for (int i = 0; i < cfg.Countries.size(); i++) {
                CountryCfg cc = cfg.Countries.get(i);
                if (cc == null || cc.Civ == null) continue;
                if (isDefaultCiv(cc.Civ)) {
                    if (!legacyDefaultWarned) {
                        legacyDefaultWarned = true;
                        log("config: 'Civ: default' inside Countries is ignored; use top-level DefaultCountry instead");
                    }
                    continue; /* template only */
                }
                if (cc.Ruling == null || cc.Ruling.length() == 0) continue;
                int civ = civByTag(cc.Civ);
                if (civ <= 0) {
                    StringBuilder sb = new StringBuilder();
                    int shown = 0;
                    int total = 0;
                    try {
                        total = Game.getCivsSize();
                        for (int k = 1; k < total && shown < 3; k++) {
                            String t = civTag(k);
                            if (t == null || t.length() == 0) continue;
                            if (shown > 0) sb.append(", ");
                            sb.append(t);
                            shown++;
                        }
                    } catch (Throwable ignored) {
                    }
                    log("config: civ tag '" + cc.Civ + "' not found (examples: " + sb + ")");
                    continue;
                }
                nation(civ);
                if (!cc.Ruling.equals(currentPartyTag(civ))) {
                    if (setRuling(civ, cc.Ruling)) {
                        log("config: ruling applied " + cc.Civ + " -> " + cc.Ruling);
                    }
                }
            }
        } catch (Throwable t) {
            log("applyCountriesConfig failed: " + t);
        }
    }

    private int civByTag(String tag) {
        if (tag == null) return -1;
        try {
            String base = baseTag(tag.trim());
            int n = Game.getCivsSize();
            for (int i = 1; i < n; i++) {
                String t = civTag(i);
                if (t == null || t.length() == 0) continue;
                if (tag.equalsIgnoreCase(t)) return i;
            }
            for (int i = 1; i < n; i++) {
                String t = civTag(i);
                if (t == null || t.length() == 0) continue;
                String b = baseTag(t);
                if (b != null && b.equalsIgnoreCase(base)) return i;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    void tickWorld() {
        Object w = null;
        try {
            w = Game.getCiv(0);
        } catch (Throwable ignored) {
        }
        if (w == world) return;
        world = w;
        nations.clear();
        mirrored.clear();
        lastDay = -1;
        dirty = false;
        touch();
        if (!active()) return;
        if (restoreFromVariables()) {
            log("state restored from save: nations=" + nations.size());
        } else {
            log("new world: no party state in save (lazy init)");
            applyCountriesConfig();
        }
        /* refresh mirror first so stale mirror strings are never treated as commands */
        mirrorAll();
        primeElections();
    }

    /** Optional: create state + election clock for every civ (AI included). */
    private void primeElections() {
        Config cfg = config();
        if (cfg == null || !cfg.ElectionsEnabled) return;
        AiConfig a = ai();
        boolean aiVote = a.Enabled && (a.Elections || cfg.ElectionAllCivs);
        if (!cfg.ElectionAllCivs && !aiVote) return;
        try {
            int today = gameDay();
            int n = Game.getCivsSize();
            int p = playerCiv();
            for (int i = 1; i < n; i++) {
                if (!electionAllowedFor(i, cfg)) continue;
                Nation x = nation(i);
                if (x == null) continue;
                ensureElectionClock(x, today);
                if (i != p && x.nextElectionDay > 0 && a.ElectionDelayDays > 0) {
                    /* 错峰：避免全世界同一天投票（多样性 + 避免同日大量换政体） */
                    x.nextElectionDay += rnd.nextInt(a.ElectionDelayDays + 1);
                }
            }
            log("elections primed for " + (n - 1) + " civ(s), aiVote=" + aiVote);
        } catch (Throwable t) {
            log("primeElections failed: " + t);
        }
    }

    /** AI 是否允许参选（玩家不受 AI 开关影响）。 */
    private boolean electionAllowedFor(int civ, Config cfg) {
        if (cfg == null || !cfg.ElectionsEnabled) return false;
        if (isPlayer(civ)) return true;
        AiConfig a = ai();
        return a.Enabled && (a.Elections || cfg.ElectionAllCivs);
    }

    /** 每个文明自己的选举周期（AI 可以单独配）。 */
    private int electionIntervalFor(int civ, Config cfg) {
        if (cfg == null) return 0;
        AiConfig a = ai();
        if (!isPlayer(civ) && a.ElectionIntervalDays > 0) return a.ElectionIntervalDays;
        return cfg.ElectionIntervalDays;
    }


    /* -------------------------------------------------------- persistence */
    private boolean isState(String s) {
        return s.startsWith(ST_SUPPORT) || s.startsWith(ST_RULING)
                || s.startsWith(ST_CAMPAIGN) || s.startsWith(ST_COOLDOWN)
                || s.startsWith(ST_ELECTION);
    }

    /**
     * Mirrors our nation state into the civ variable list. The game serializes
     * Civilization.eventsDataVariables with the save (Save_Civs_Data_EventsVariables),
     * so the state really travels with the save game.
     */
    private boolean persistOne(int civ) {
        boolean changed = false;
        try {
            Civilization c = Game.getCiv(civ);
            if (c == null || c.eventsDataVariables == null) return false;
            List<String> v = c.eventsDataVariables.v;
            if (v == null) return false;
            Nation n = nations.get(Integer.valueOf(civ));
            HashSet<String> want = new HashSet<String>();
            if (n != null) {
                String derived = currentPartyTag(civ);
                if (n.ruling != null && !n.ruling.equals(derived)) {
                    want.add(ST_RULING + n.ruling);
                }
                boolean nonDefault = n.support.size() != 1 || (n.ruling != null && !n.support.containsKey(n.ruling));
                if (!nonDefault && n.ruling != null) {
                    Float only = n.support.get(n.ruling);
                    nonDefault = only == null || only.floatValue() < 0.999f;
                }
                if (nonDefault) {
                    List<Party> list = parties();
                    for (int i = 0; i < list.size(); i++) {
                        String tag = list.get(i).tag();
                        Float f = n.support.get(tag);
                        if (f == null || f.floatValue() <= 0f) continue;
                        int pm = Math.round(f.floatValue() * 1000f);
                        if (pm <= 0) continue;
                        want.add(ST_SUPPORT + tag + ":" + pm);
                    }
                }
                if (n.campaignLeft > 0 && n.campaignParty != null) {
                    want.add(ST_CAMPAIGN + n.campaignParty + ":" + n.campaignLeft);
                }
                if (n.cooldownDay > gameDay()) {
                    want.add(ST_COOLDOWN + n.cooldownDay);
                }
                if (n.nextElectionDay > 0 || n.electionCooldownDay > 0) {
                    want.add(ST_ELECTION + n.nextElectionDay + ":" + n.electionCooldownDay);
                }
            }
            for (int i = v.size() - 1; i >= 0; i--) {
                String s = v.get(i);
                if (!isState(s)) continue;
                if (!want.contains(s)) {
                    v.remove(i);
                    changed = true;
                }
            }
            for (String s : want) {
                if (!v.contains(s)) {
                    v.add(s);
                    changed = true;
                }
            }
        } catch (Throwable t) {
            log("persist failed civ=" + civ + " / " + t);
        }
        return changed;
    }

    private void persistAll() {
        if (!active()) return;
        int changed = 0;
        try {
            int size = Game.getCivsSize();
            /* keep the save-resident state fresh (AI states are optional) */
            AiConfig aiCfg = ai();
            for (int i = 1; i < size; i++) {
                if (!nations.containsKey(Integer.valueOf(i))) continue;
                if (!aiCfg.Persist && !isPlayer(i)) continue;
                if (persistOne(i)) changed++;
            }
        } catch (Throwable t) {
            log("persistAll failed: " + t);
        }
        dirty = false;
        if (changed > 0) log("state persisted into civ variables: " + changed + " civ(s)");
    }

    /** Rebuilds nation state from the civ variable list (new game / loaded save). */
    private boolean restoreFromVariables() {
        boolean any = false;
        try {
            int size = Game.getCivsSize();
            for (int i = 1; i < size; i++) {
                Civilization c = Game.getCiv(i);
                if (c == null || c.eventsDataVariables == null) continue;
                List<String> v = c.eventsDataVariables.v;
                if (v == null || v.isEmpty()) continue;
                Nation n = null;
                for (int k = 0; k < v.size(); k++) {
                    String s = v.get(k);
                    if (s.startsWith(ST_RULING)) {
                        if (n == null) n = newNation(i);
                        n.ruling = s.substring(ST_RULING.length());
                    } else if (s.startsWith(ST_SUPPORT)) {
                        String body = s.substring(ST_SUPPORT.length());
                        int cut = body.lastIndexOf(':');
                        if (cut <= 0) continue;
                        String tag = body.substring(0, cut);
                        float val = num(body.substring(cut + 1)) / 1000f;
                        if (val <= 0f) continue;
                        if (n == null) n = newNation(i);
                        n.support.put(tag, Float.valueOf(clamp(val)));
                    } else if (s.startsWith(ST_CAMPAIGN)) {
                        String body = s.substring(ST_CAMPAIGN.length());
                        int cut = body.lastIndexOf(':');
                        if (cut <= 0) continue;
                        if (n == null) n = newNation(i);
                        n.campaignParty = body.substring(0, cut);
                        n.campaignLeft = (int) num(body.substring(cut + 1));
                        n.campaignTotal = n.campaignLeft;
                    } else if (s.startsWith(ST_COOLDOWN)) {
                        if (n == null) n = newNation(i);
                        n.cooldownDay = (int) num(s.substring(ST_COOLDOWN.length()));
                    } else if (s.startsWith(ST_ELECTION)) {
                        String body = s.substring(ST_ELECTION.length());
                        int cut = body.indexOf(':');
                        if (n == null) n = newNation(i);
                        if (cut > 0) {
                            n.nextElectionDay = (int) num(body.substring(0, cut));
                            n.electionCooldownDay = (int) num(body.substring(cut + 1));
                        } else {
                            n.nextElectionDay = (int) num(body);
                        }
                    }
                }
                if (n != null) {
                    if (n.support.size() > 0) {
                        if (n.ruling == null) n.ruling = currentPartyTag(i);
                        normalize(n);
                    } else {
                        String base = n.ruling != null ? n.ruling : currentPartyTag(i);
                        if (base != null) {
                            n.ruling = base;
                            n.support.put(base, Float.valueOf(1f));
                        }
                    }
                    nations.put(Integer.valueOf(i), n);
                    any = true;
                }
            }
        } catch (Throwable t) {
            log("restore failed: " + t);
        }
        return any;
    }

    /* ------------------------------------------------- commands (poll list) */

    private void pollCommands() {
        if (!active()) return;
        try {
            int n = Game.getCivsSize();
            for (int i = 1; i < n; i++) {
                Civilization c = Game.getCiv(i);
                if (c == null) continue;
                CivilizationEventsData_Variables data = c.eventsDataVariables;
                if (data == null) continue;
                List<String> v = data.v;
                if (v == null || v.isEmpty()) continue;
                for (int k = v.size() - 1; k >= 0; k--) {
                    String s = v.get(k);
                    if (!reserved(s)) continue;
                    /* persisted state and mirrored trigger strings are read-only */
                    if (isState(s)) continue;
                    if (isMirrored(i, s)) continue;
                    v.remove(k);
                    execute(i, s);
                }
            }
        } catch (Throwable t) {
            log("pollCommands failed: " + t);
        }
    }

    private boolean isMirrored(int civ, String s) {
        HashSet<String> set = mirrored.get(Integer.valueOf(civ));
        return set != null && set.contains(s);
    }

    /* --------------------------------------------------- trigger variable mirror */

    private boolean mirrorCiv(int civ) {
        Config c = config();
        if (c == null) return false;
        if (c.MirrorCivs.size() == 0) {
            try {
                return Game.player != null && Game.player.iCivID == civ;
            } catch (Throwable t) {
                return false;
            }
        }
        String tag = civTag(civ);
        if (tag == null) return false;
        for (int i = 0; i < c.MirrorCivs.size(); i++) {
            String t = c.MirrorCivs.get(i);
            if (t == null) continue;
            if ("*".equals(t)) return true;
            if (tag.equals(t)) return true;
        }
        return false;
    }

    /** Rebuilds the mirror variables of one civ (true ones present, false ones absent). */
    void mirrorOne(int civ) {
        if (!active()) return;
        try {
            Civilization c = Game.getCiv(civ);
            if (c == null || c.eventsDataVariables == null) return;
            List<String> v = c.eventsDataVariables.v;
            if (v == null) return;
            Config cfg = config();
            HashSet<String> prev = mirrored.get(Integer.valueOf(civ));
            if (prev == null) {
                prev = new HashSet<String>();
                mirrored.put(Integer.valueOf(civ), prev);
            }
            HashSet<String> want = new HashSet<String>();
            List<Party> list = parties();
            String r = ruling(civ);
            for (int i = 0; i < list.size(); i++) {
                String tag = list.get(i).tag();
                if (tag.equals(r)) want.add(PREFIX + "ruling:" + tag);
                float sh = share(civ, tag);
                for (int k = 0; k < cfg.SupportThresholds.length; k++) {
                    float x = cfg.SupportThresholds[k];
                    String xs = plain(x);
                    if (sh > x) want.add(PREFIX + "support:" + tag + ":gt:" + xs);
                    if (sh >= x) want.add(PREFIX + "support:" + tag + ":ge:" + xs);
                    if (sh < x) want.add(PREFIX + "support:" + tag + ":lt:" + xs);
                    if (sh <= x) want.add(PREFIX + "support:" + tag + ":le:" + xs);
                }
            }
            /* add missing */
            for (String s : want) {
                if (!prev.contains(s) && !v.contains(s)) v.add(s);
            }
            /* remove stale (only strings we own) */
            for (int i = v.size() - 1; i >= 0; i--) {
                String s = v.get(i);
                if (!reserved(s)) continue;
                if (want.contains(s)) continue;
                if (prev.contains(s) || s.startsWith(PREFIX + "ruling:") || s.startsWith(PREFIX + "support:")) {
                    v.remove(i);
                }
            }
            prev.clear();
            prev.addAll(want);
        } catch (Throwable t) {
            log("mirror failed civ=" + civ + " / " + t);
        }
    }

    private void mirrorAll() {
        if (!active()) return;
        try {
            int n = Game.getCivsSize();
            for (int i = 1; i < n; i++) {
                if (mirrorCiv(i)) mirrorOne(i);
            }
        } catch (Throwable t) {
            log("mirrorAll failed: " + t);
        }
    }

    private static String plain(float f) {
        /* stable, event-author friendly form: 0.5 / 0.25 / 1 */
        if (f == Math.round(f)) return String.valueOf(Math.round(f));
        String s = String.format(Locale.ROOT, "%.3f", Float.valueOf(f));
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /* ------------------------------------------------------------------ tick */

    /* -------------------------------------------------------------- election */

    private boolean isPlayer(int civ) {
        try {
            return Game.player != null && Game.player.iCivID == civ;
        } catch (Throwable t) {
            return false;
        }
    }

    private void toast(String msg) {
        try {
            if (Game.menuManager != null) Game.menuManager.addToast(msg);
        } catch (Throwable ignored) {
        }
    }

    private void ensureElectionClock(Nation n, int today) {
        Config cfg = config();
        if (cfg == null || n == null) return;
        if (n.nextElectionDay > 0) return;
        int iv = electionIntervalFor(n.civ, cfg);
        if (iv <= 0) return;
        n.nextElectionDay = today + iv;
        dirty = true;
    }

    /** Weighted random election. Returns the winner name (null when nothing happened). */
    private String runElection(int civ, boolean forced) {
        Config cfg = config();
        Nation n = nation(civ);
        if (cfg == null || n == null || n.support.isEmpty()) return null;
        List<Party> list = parties();
        int size = list.size();
        if (size == 0) return null;
        double[] w = new double[size];
        double total = 0;
        float exp = Math.max(0.01f, cfg.ElectionWeightExponent);
        for (int i = 0; i < size; i++) {
            Party p = list.get(i);
            float sh = share(civ, p.tag());
            double base = Math.pow(Math.max(sh, 0.0001f), exp);
            if (n.ruling != null && n.ruling.equals(p.tag())) {
                base *= Math.max(0.01f, cfg.ElectionIncumbentBonus);
            }
            if (cfg.ElectionNoise > 0f) {
                base *= 1.0 + cfg.ElectionNoise * (rnd.nextDouble() * 2 - 1);
            }
            if (base < 0) base = 0;
            w[i] = base;
            total += base;
        }
        if (total <= 0) return null;
        double pick = rnd.nextDouble() * total;
        int win = size - 1;
        double acc = 0;
        for (int i = 0; i < size; i++) {
            acc += w[i];
            if (pick <= acc) {
                win = i;
                break;
            }
        }
        Party winner = list.get(win);
        String prev = n.ruling;
        if (cfg.ElectionChangeRuling) setRuling(civ, winner.tag());
        touch();
        persistOne(civ);
        log("election: civ=" + civ + " winner=" + winner.tag()
                + " share=" + Math.round(share(civ, winner.tag()) * 100) + "%"
                + (forced ? " (forced)" : " (scheduled)")
                + (prev != null && !prev.equals(winner.tag()) ? " prev=" + prev : ""));
        return winner.name();
    }

    private void tickElections(int today) {
        if (!active()) return;
        Config cfg = config();
        if (cfg == null || !cfg.ElectionsEnabled) return;
        boolean any = false;
        List<Nation> all = new ArrayList<Nation>(nations.values());
        for (int k = 0; k < all.size(); k++) {
            Nation n = all.get(k);
            if (n == null) continue;
            if (!electionAllowedFor(n.civ, cfg)) continue;
            ensureElectionClock(n, today);
            if (n.nextElectionDay > 0 && today >= n.nextElectionDay) {
                int civ = n.civ;
                int iv = electionIntervalFor(civ, cfg);
                n.nextElectionDay = iv > 0 ? today + iv : 0;
                String w = runElection(civ, false);
                if (w != null && isPlayer(civ)) toast("选举结果：" + w + " 获胜");
                any = true;
            }
        }
        if (any) touch();
    }

    /** Force an early election (UI button). Returns null on success, else a reason. */
    String forceElection(int civ) {
        try {
            Config cfg = config();
            if (cfg == null || !active()) return "插件已禁用";
            if (!cfg.ElectionsEnabled) return "选举已关闭";
            Nation n = nation(civ);
            if (n == null) return "无法读取该国数据";
            int today = gameDay();
            if (n.electionCooldownDay > today) {
                log("forceElection rejected: cooldown " + (n.electionCooldownDay - today) + " day(s) left");
                return "选举冷却：还需 " + (n.electionCooldownDay - today) + " 天";
            }
            int toVote = electionDaysLeft(civ);
            if (toVote > 0 && cfg.ElectionMaxAdvanceDays >= 0 && toVote > cfg.ElectionMaxAdvanceDays) {
                log("forceElection rejected: " + toVote + " day(s) to election, maxAdvance="
                        + cfg.ElectionMaxAdvanceDays);
                return "提前选举：最多提前 " + cfg.ElectionMaxAdvanceDays + " 天";
            }
            Civilization c = Game.getCiv(civ);
            if (c == null) return "无法读取该国数据";
            if (parties().size() == 0 || (n.support.isEmpty() && n.ruling == null)) {
                log("forceElection rejected: no party data for civ " + civ);
                return "no party data";
            }
            float have = readStat(c, cfg.ElectionCostStat);
            if (cfg.ElectionCostAmount > 0f && have + 0.001f < cfg.ElectionCostAmount) {
                log("forceElection rejected: cost " + cfg.ElectionCostStat + " have=" + have
                        + " need=" + cfg.ElectionCostAmount);
                return statName(cfg.ElectionCostStat) + "不足（需 " + Math.round(cfg.ElectionCostAmount)
                        + "，现有 " + Math.round(have) + "）";
            }
            if (cfg.ElectionCostAmount > 0f) {
                writeStat(c, cfg.ElectionCostStat, have - cfg.ElectionCostAmount);
                log("forceElection: paid " + cfg.ElectionCostAmount + " " + cfg.ElectionCostStat);
            }
            String w = runElection(civ, true);
            n.electionCooldownDay = today + Math.max(0, cfg.ElectionCooldownDays);
            n.nextElectionDay = cfg.ElectionIntervalDays > 0 ? today + cfg.ElectionIntervalDays : 0;
            touch();
            persistOne(civ);
            if (w != null) toast("选举结果：" + w + " 获胜");
            return w == null ? "没有可参选的政党" : null;
        } catch (Throwable t) {
            log("forceElection failed: " + t);
            return "内部错误：" + t;
        }
    }

    /** Days left until the next scheduled election (-1 = none configured). */
    int electionDaysLeft(int civ) {
        Config cfg = config();
        Nation n = nation(civ);
        if (cfg == null || n == null || cfg.ElectionIntervalDays <= 0) return -1;
        int today = gameDay();
        ensureElectionClock(n, today);
        if (n.nextElectionDay <= 0) return -1;
        int left = n.nextElectionDay - today;
        return left < 0 ? 0 : left;
    }

    /** True when the "force election" button is usable (drives the greyed-out look). */
    boolean canForceElection(int civ) {
        try {
            Config cfg = config();
            if (cfg == null || !active() || !cfg.ElectionsEnabled) return false;
            if (nation(civ) == null) return false;
            int today = gameDay();
            Nation n = nations.get(Integer.valueOf(civ));
            if (n == null) return false;
            if (n.electionCooldownDay > today) return false;
            int left = electionDaysLeft(civ);
            if (left > 0 && cfg.ElectionMaxAdvanceDays >= 0 && left > cfg.ElectionMaxAdvanceDays) return false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    String electionButtonText(int civ) {
        Config cfg = config();
        if (cfg == null || !cfg.ElectionsEnabled) return "选举：已关闭";
        Nation n = nation(civ);
        if (n == null) return "";
        int today = gameDay();
        if (n.electionCooldownDay > today) {
            return "提前选举：冷却 " + (n.electionCooldownDay - today) + " 天";
        }
        int toVote = electionDaysLeft(civ);
        if (toVote > 0 && cfg.ElectionMaxAdvanceDays >= 0 && toVote > cfg.ElectionMaxAdvanceDays) {
            return "提前选举：最多提前 " + cfg.ElectionMaxAdvanceDays + " 天";
        }
        if (cfg.ElectionCostAmount > 0f) {
            return "强制提前选举（" + Math.round(cfg.ElectionCostAmount) + " " + statName(cfg.ElectionCostStat) + "）";
        }
        return "强制提前选举";
    }

    String nextElectionText(int civ) {
        Config cfg = config();
        if (cfg == null || !cfg.ElectionsEnabled) return "定期选举：关闭";
        if (cfg.ElectionIntervalDays <= 0) return "定期选举：未设置周期";
        Nation n = nation(civ);
        if (n == null) return "";
        int today = gameDay();
        ensureElectionClock(n, today);
        int left = n.nextElectionDay - today;
        if (left < 0) left = 0;
        return "定期选举：还有 " + left + " 天";
    }

    /** Dumps visible menu class names (diagnostics for UiPages). */
    void logMenus() {
        try {
            if (!ctx.logEnabled() || Game.menuManager == null) return;
            java.util.List<aoc.kingdoms.lukasz.menu.Menu> active = Game.menuManager.getActiveMenu();
            if (active == null) return;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < active.size(); i++) {
                aoc.kingdoms.lukasz.menu.Menu m = active.get(i);
                if (m == null) continue;
                sb.append(i).append('=').append(m.getClass().getSimpleName()).append(' ');
            }
            log("active menus: " + sb);
        } catch (Throwable ignored) {
        }
    }

    /** Monotonic game-day value used for cooldowns. */
    private static int gameDay() {
        try {
            return aoc.kingdoms.lukasz.jakowski.Game_Calendar.currentYear * 10000
                    + aoc.kingdoms.lukasz.jakowski.Game_Calendar.currentMonth * 100
                    + aoc.kingdoms.lukasz.jakowski.Game_Calendar.currentDay;
        } catch (Throwable t) {
            return -1;
        }
    }

    /* ------------------------------------------------------------- campaign */

    private static float readStat(Civilization c, String key) {
        String k = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        if (k.equals("gold")) return c.fGold;
        if (k.equals("diplomacy") || k.equals("political")) return c.fDiplomacy;
        return c.fLegacy;
    }

    private static void writeStat(Civilization c, String key, float v) {
        String k = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        if (k.equals("gold")) c.fGold = v;
        else if (k.equals("diplomacy") || k.equals("political")) c.fDiplomacy = v;
        else c.fLegacy = v;
    }

    private static String statName(String key) {
        String k = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        if (k.equals("gold")) return "金币";
        if (k.equals("diplomacy") || k.equals("political")) return "外交点";
        return "传承点";
    }

    /** UI text for one row: "运动中 剩N天" / "冷却 N 天" / "". */
    String actionStatus(int civ, String tag) {
        Nation n = nations.get(Integer.valueOf(civ));
        if (n == null) return "";
        if (n.campaignParty != null && n.campaignLeft > 0) {
            if (tag.equals(n.campaignParty)) return " 运动中(剩" + n.campaignLeft + "天)";
            return "";
        }
        int cd = n.cooldownDay - gameDay();
        if (cd > 0) return " 冷却" + cd + "天";
        Config cfg0 = config();
        if (cfg0 != null && cfg0.CostAmount > 0f) {
            Civilization c0 = Game.getCiv(civ);
            if (c0 != null && readStat(c0, cfg0.CostStat) + 0.001f < cfg0.CostAmount) return " 点数不足";
        }
        return "";
    }

    boolean canAct(int civ, String tag) {
        Nation n = nation(civ);
        if (n == null || byTag(tag) == null) return false;
        if (n.campaignParty != null && n.campaignLeft > 0) return false;
        return n.cooldownDay <= gameDay();
    }

    /**
     * Starts a support campaign for the given party.
     *
     * @return null on success, otherwise a human readable reason
     */
    String startCampaign(int civ, String tag) {
        try {
            Config cfg = config();
            if (cfg == null || !active()) return "插件未启用";
            Nation n = nation(civ);
            if (n == null) return "无法读取该国数据";
            Party p = byTag(tag);
            if (p == null) return "未知政党 " + tag;
            int today = gameDay();
            if (n.campaignParty != null && n.campaignLeft > 0) {
                return "已有一场运动在进行（" + n.campaignLeft + " 天）";
            }
            if (n.cooldownDay > today) {
                return "冷却中：还需 " + (n.cooldownDay - today) + " 天";
            }
            Civilization c = Game.getCiv(civ);
            if (c == null) return "无法读取该国数据";
            float have = readStat(c, cfg.CostStat);
            if (cfg.CostAmount > 0f && have + 0.001f < cfg.CostAmount) {
                log("campaign rejected: cost " + cfg.CostStat + " have=" + have
                        + " need=" + cfg.CostAmount);
                return statName(cfg.CostStat) + "不足（需 " + Math.round(cfg.CostAmount)
                        + "，现有 " + Math.round(have) + "）";
            }
            if (cfg.CostAmount > 0f) writeStat(c, cfg.CostStat, have - cfg.CostAmount);

            /* randomness: a campaign can fizzle out */
            boolean success = cfg.ActionSuccessChance >= 1f
                    || (cfg.ActionSuccessChance > 0f && rnd.nextFloat() < cfg.ActionSuccessChance);
            if (!success) {
                float refund = cfg.CostAmount * clamp(cfg.ActionFailRefund);
                if (refund > 0f) writeStat(c, cfg.CostStat, readStat(c, cfg.CostStat) + refund);
                if (cfg.ActionFailPenalty > 0f) applyDailyGain(n, tag, -cfg.ActionFailPenalty);
                int failCd = cfg.ActionFailCooldownDays > 0 ? cfg.ActionFailCooldownDays : cfg.CooldownDays;
                n.cooldownDay = today + Math.max(0, failCd);
                touch();
                persistOne(civ);
                log("campaign FAILED: civ=" + civ + " party=" + tag + " refund=" + refund
                        + " cooldown=" + failCd);
                return "行动失败：支持度下降，冷却 " + Math.max(0, failCd) + " 天";
            }

            n.campaignParty = tag;
            n.campaignTotal = Math.max(1, cfg.CampaignDays);
            n.campaignLeft = n.campaignTotal;
            touch();
            persistOne(civ);
            log("campaign started: civ=" + civ + " party=" + tag + " days=" + n.campaignLeft
                    + " cost=" + cfg.CostAmount + " " + cfg.CostStat);
            return null;
        } catch (Throwable t) {
            log("startCampaign failed: " + t);
            return "内部错误：" + t;
        }
    }

    /** One day of every running campaign (called on game-day change). */
    private void advanceCampaigns(int today) {
        if (!active()) return;
        Config cfg = config();
        float step = cfg == null ? 0.02f : Math.max(0f, cfg.SupportPerDay);
        int cooldown = cfg == null ? 30 : Math.max(0, cfg.CooldownDays);
        boolean any = false;
        for (Nation n : nations.values()) {
            if (n == null) continue;
            if (n.campaignParty != null && n.campaignLeft > 0 && step > 0f) {
                applyDailyGain(n, n.campaignParty, step);
                n.campaignLeft--;
                if (n.campaignLeft <= 0) {
                    log("campaign finished: civ=" + n.civ + " party=" + n.campaignParty);
                    n.campaignParty = null;
                    n.cooldownDay = today + cooldown;
                }
                any = true;
            } else if (n.campaignParty != null && n.campaignLeft <= 0) {
                n.campaignParty = null;
                n.cooldownDay = today + cooldown;
                any = true;
            }
        }
        if (any) touch();
    }

    /** target += step, the rest lose proportionally so the total stays at 1. */
    private void applyDailyGain(Nation n, String tag, float step) {
        if (step == 0f) return;
        Float curF = n.support.get(tag);
        float cur = curF == null ? 0f : curF.floatValue();
        float target = clamp(cur + step);
        float delta = target - cur;
        if (Math.abs(delta) < 0.00001f) return;
        float others = 0f;
        for (String k : n.support.keySet()) {
            if (k.equals(tag)) continue;
            others += n.support.get(k).floatValue();
        }
        if (others > 0.0001f) {
            List<String> keys = new ArrayList<String>(n.support.keySet());
            for (int i = 0; i < keys.size(); i++) {
                String k = keys.get(i);
                if (k.equals(tag)) continue;
                float v = n.support.get(k).floatValue();
                float nv = clamp(v - delta * (v / others));
                n.support.put(k, Float.valueOf(nv));
            }
        }
        n.support.put(tag, Float.valueOf(target));
        normalize(n);
    }

    void tick(long frame) {
        frameNo = frame;
        tickWorld();
        if (!active()) return;
        if (!hotVarHooked) hookEventApi();
        settleGovCredit();
        int d = gameDay();
        if (d >= 0 && d != lastDay) {
            lastDay = d;
            advanceCampaigns(d);
            tickElections(d);
            tickAi(d);
            /* 倒计时/冷却文本读日历 -> 需要重画，但状态未必变（不改 dirty） */
            revision++;
        }
        Config cfg = config();
        int pollEvery = Math.max(1, cfg == null ? 8 : cfg.PollFrames);
        int aiPoll = ai().PollFrames;
        if (aiPoll > 0) pollEvery = Math.max(1, aiPoll);
        int mirrorEvery = Math.max(1, cfg == null ? 16 : cfg.MirrorFrames);
        if (frame % pollEvery == 0L) pollCommands();
        if (frame % mirrorEvery == 0L) mirrorAll();
        if (frame % 601L == 0L) logMenus();
        /* keep the save-resident state fresh */
        if (frame % 30L == 0L && dirty) persistAll();
    }

    /* ----------------------------------------------------------- menu access */

    List menuElements(Object menu) {
        try {
            if (fElements == null) {
                fElements = aoc.kingdoms.lukasz.menu.Menu.class.getDeclaredField("menuElements");
                fElements.setAccessible(true);
            }
            return (List) fElements.get(menu);
        } catch (Throwable t) {
            log("menuElements failed: " + t);
            return null;
        }
    }

    void setMenuElementsSize(Object menu, int size) {
        try {
            if (fCount == null) {
                fCount = aoc.kingdoms.lukasz.menu.Menu.class.getDeclaredField("iMenuElementsSize");
                fCount.setAccessible(true);
            }
            fCount.setInt(menu, size);
        } catch (Throwable t) {
            log("setMenuElementsSize failed: " + t);
        }
    }

    int courtCiv() {
        try {
            int id = aoc.kingdoms.lukasz.menusInGame.Court.InGame_Court.iActiveCivID;
            if (id > 0) return id;
        } catch (Throwable ignored) {
        }
        try {
            if (Game.player != null && Game.player.iCivID > 0) return Game.player.iCivID;
        } catch (Throwable ignored) {
        }
        return -1;
    }

    static String percent(float f) {
        return String.format(Locale.ROOT, "%.0f%%", Float.valueOf(clamp(f) * 100f));
    }
}
