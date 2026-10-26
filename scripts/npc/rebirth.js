/* Rebirth and retained skill bindings, opened with @rebirth or @reborn. */
var Bindings = Java.type("client.RebirthSkillBindings");
var state = "menu";
var skills;
var keys;
var selectedSkill;
var selectedKey;

function start() {
    state = "menu";
    cm.sendSimple("#eRebirth#n\r\nCompleted rebirths: #b" + cm.getPlayer().getReborns()
        + "#k\r\n\r\nAt level 200, start again at level 1 as a Beginner. Keep your STR, DEX, INT, LUK, HP/MP, AP/SP, items, mesos, learned skills, and key bindings. Temporary buffs are cleared.\r\n\r\n"
        + "Return to a town, unequip your non-cash gear, leave your party, and close any trade, shop, or event before rebirthing. Your equipment stays in your inventory.\r\n\r\n"
        + "#L0##bRebirth this character#k#l\r\n"
        + "#L1##bBind a learned skill to a key#k#l");
}

function action(mode, type, selection) {
    if (mode != 1) {
        cm.dispose();
        return;
    }

    if (state == "menu") {
        if (selection == 0) {
            state = "confirmRebirth";
            cm.sendYesNo("Return to #blevel 1 as a Beginner#k in Henesys now?\r\n\r\nYour stats, HP/MP, AP/SP, items, mesos, learned skills, and key bindings are kept. Temporary buffs are cleared, and job advancement quests reset so you can choose a new path. Unequip non-cash gear first; it stays in your inventory. This starts a new leveling run and increases your rebirth count by one.");
        } else if (selection == 1) {
            skills = Bindings.getBindableSkills(cm.getPlayer());
            if (skills.isEmpty()) {
                state = "done";
                cm.sendOk("After your first rebirth, you can bind your learned active skills here. Passive, event-only, expired, and GM skills cannot be bound.");
                return;
            }
            state = "chooseSkill";
            var text = "Choose a learned skill. Normal weapon requirements still apply; job-specific passives and special job mechanics may require their original job.\r\n\r\n";
            for (var i = 0; i < skills.size(); i++) {
                var id = skills.get(i).getId();
                text += "#L" + i + "##s" + id + "# #q" + id + "##l\r\n";
            }
            cm.sendSimple(text);
        } else {
            cm.dispose();
        }
    } else if (state == "confirmRebirth") {
        var player = cm.getPlayer();
        state = "done";
        cm.dispose();
        var error = player.rebirth();
        if (error != null) {
            player.dropMessage(5, String(error));
        }
    } else if (state == "chooseSkill") {
        if (selection < 0 || selection >= skills.size()) {
            cm.dispose();
            return;
        }
        selectedSkill = skills.get(selection).getId();
        keys = Bindings.getKeys();
        state = "chooseKey";
        var text = "Choose a key for #q" + selectedSkill + "#. Its current binding will be replaced.\r\n\r\n";
        for (var i = 0; i < keys.size(); i++) {
            text += "#L" + i + "#" + keys.get(i).name() + "#l  ";
            if ((i + 1) % 6 == 0) {
                text += "\r\n";
            }
        }
        cm.sendSimple(text);
    } else if (state == "chooseKey") {
        if (selection < 0 || selection >= keys.size()) {
            cm.dispose();
            return;
        }
        selectedKey = keys.get(selection);
        state = "confirmBinding";
        cm.sendYesNo("Bind #q" + selectedSkill + "# to #b" + selectedKey.name()
            + "#k? This replaces that key's current binding.");
    } else if (state == "confirmBinding") {
        var error = Bindings.bind(cm.getPlayer(), selectedSkill, selectedKey.scanCode());
        state = "done";
        cm.sendOk(error == null ? "Your skill is now bound to " + selectedKey.name() + "." : String(error));
    } else {
        cm.dispose();
    }
}
