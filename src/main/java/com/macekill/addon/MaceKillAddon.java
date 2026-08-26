package com.macekill.addon;

import com.macekill.addon.modules.AutoFuckModule;
import com.macekill.addon.modules.AutoRise;
import com.macekill.addon.modules.AutoTotemToHotbar;
import com.macekill.addon.modules.MaceAura;
import com.macekill.addon.modules.MaceBreakerPro;
import com.macekill.addon.modules.MaceMissLite;
import com.macekill.addon.modules.NearestPlayerHUD;
import com.macekill.addon.modules.Rise;
import com.macekill.addon.modules.SpeedModule;
import com.macekill.addon.modules.AutoShulkerBox;
import com.macekill.addon.modules.SpearKill;
import com.macekill.addon.modules.TpMace;
import com.macekill.addon.modules.XinTpMace;
import com.macekill.addon.modules.AutoGGModule;
import com.macekill.addon.modules.AutoMineModule;
import com.macekill.addon.modules.CreativeGiveModule;
import com.macekill.addon.modules.CustomPotionModule;
import com.macekill.addon.modules.MaceDMG;
import com.macekill.addon.modules.MaceKillModule;
import com.macekill.addon.modules.AntiAirMiss;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MaceKillAddon extends MeteorAddon {
    public static final Logger LOG = LoggerFactory.getLogger("Qazr-Addons");
    public static final Category CATEGORY = new Category("Qazr1234");

    @Override
    public void onInitialize() {
        LOG.info("Qazr Addons initializing...");

        // ---- 重锤战斗 ----
        Modules.get().add(new MaceKillModule());
        Modules.get().add(new MaceAura());
        Modules.get().add(new MaceMissLite());
        Modules.get().add(new XinTpMace());
        Modules.get().add(new TpMace());
        Modules.get().add(new MaceBreakerPro());
        Modules.get().add(new MaceDMG());
        Modules.get().add(new AntiAirMiss());

        // ---- 长矛战斗 ----
        Modules.get().add(new SpearKill());

        // ---- 移动辅助 ----
        Modules.get().add(new AutoRise());
        Modules.get().add(new Rise());
        Modules.get().add(new SpeedModule());

        // ---- 自动化 ----
        Modules.get().add(new AutoShulkerBox());
        Modules.get().add(new AutoMineModule());
        Modules.get().add(new CreativeGiveModule());
        Modules.get().add(new AutoTotemToHotbar());

        // ---- 聊天/信息 ----
        Modules.get().add(new AutoGGModule());
        Modules.get().add(new AutoFuckModule());
        Modules.get().add(new CustomPotionModule());
        Modules.get().add(new NearestPlayerHUD());

        LOG.info("Qazr Addons initialized successfully! ({} modules)", Modules.get().getGroup(CATEGORY).size());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.macekill.addon";
    }
}
