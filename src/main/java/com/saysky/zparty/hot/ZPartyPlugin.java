package com.saysky.zparty.hot;

import com.saysky.hotplugloader.api.HotContext;
import com.saysky.hotplugloader.api.HotEvents;
import com.saysky.hotplugloader.api.HotListener;
import com.saysky.hotplugloader.api.HotPlugin;
import com.saysky.hotplugloader.api.HotPluginInfo;

/**
 * ZParty hot plugin entry point.
 *
 * <p>No game class is patched: everything runs from the FRAME event.
 * Requires the host to be >= b44 (HotEvents/HotContext API).
 */
@HotPluginInfo(
        id = "com.saysky.zparty",
        name = "ZParty",
        version = "1.0.0",
        trusted = true,
        description = "Lightweight parties: a party is a government. Ruling party == civ ideology.")
public final class ZPartyPlugin implements HotPlugin, HotListener {

    private ZPartyCore core;
    private long frame;
    private boolean uiWarned;

    @Override
    public void init(HotContext context) {
        core = new ZPartyCore(context);
        core.config();
        HotEvents.register(this, HotEvents.FRAME, HotEvents.IMPORT_DONE);
        context.log("ZParty ready: parties=" + core.parties().size() + " enabled=" + core.active());
    }

    @Override
    public void onEvent(String type, Object[] args) {
        if (core == null) return;
        if (HotEvents.FRAME.equals(type)) {
            frame++;
            core.tick(frame);
            int every = core.uiCheckFrames();
            if (frame % every == 0L) ZPartyMenu.tick(core);
            return;
        }
        if (HotEvents.IMPORT_DONE.equals(type)) {
            core.reloadConfig();
            core.config();
            core.log("config reloaded after import: parties=" + core.parties().size());
        }
    }
}
