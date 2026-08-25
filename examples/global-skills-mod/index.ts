import {
  defineSunshineExtension,
  ui,
} from "@highsockscapital/sunshine-extension-api";

type Skill = {
  id: string;
  name: string;
  description?: string;
  enabled?: boolean;
  selected?: boolean;
  default_selected?: boolean;
};

export const activateSunshine = defineSunshineExtension((sunshine) => {
  sunshine.registerAction("toggle-expanded", ({ expanded }) => {
    sunshine.storage.set("expanded", Boolean(expanded));
  });

  sunshine.registerAction("set-skill", async ({ skill_id, checked }) => {
    await sunshine.services.invoke("skills", "setSelected", {
      skill_id,
      selected: Boolean(checked),
      scope: "both",
    });
  });

  sunshine.registerAction("clear", async () => {
    await sunshine.services.invoke("skills", "setSelection", {
      ids: [],
      scope: "both",
    });
  });

  sunshine.registerComponent("chat.composer.actionTray", {
    id: "global-skill-tray",
    mode: "replace",
    order: 100,
    render: (context) => {
      const skills = (context.skills ?? []) as Skill[];
      const selectedIds = new Set(
        Array.isArray(context.selected_skill_ids)
          ? context.selected_skill_ids.map(String)
          : [],
      );
      const expanded = Boolean(context.storage.expanded);
      const selectedSkills = skills.filter((skill) => selectedIds.has(skill.id));

      return ui.card([
        ui.row([
          ui.column([
            ui.text("Global Skills", {
              style: "label",
              weight: "semibold",
            }),
            ui.text(
              selectedSkills.length === 0
                ? "No defaults"
                : `${selectedSkills.length} enabled for every new chat`,
              {
                style: "small",
                color: "muted",
              },
            ),
          ], {
            weight: 1,
          }),
          ui.button(expanded ? "Collapse" : "Manage", "toggle-expanded", {
            args: { expanded: !expanded },
            tone: "neutral",
          }),
        ], {
          arrangement: "space-between",
          verticalAlignment: "center",
        }),
        ...(expanded
          ? [
              ui.column([
                ...skills
                  .filter((skill) => skill.enabled !== false)
                  .map((skill) =>
                    ui.switch(
                      skill.name,
                      selectedIds.has(skill.id),
                      "set-skill",
                      {
                        subtitle: skill.description ?? "",
                        args: { skill_id: skill.id },
                      },
                    )
                  ),
                ui.button("Clear global selection", "clear", {
                  tone: "neutral",
                  width: "fill",
                }),
              ], {
                spacing: 8,
              }),
            ]
          : selectedSkills.length > 0
            ? [
                ui.text(
                  selectedSkills.map((skill) => skill.name).join(" · "),
                  {
                    style: "small",
                    color: "muted",
                    maxLines: 2,
                  },
                ),
              ]
            : []),
      ], {
        radius: 20,
      });
    },
  });

  sunshine.registerComponent("chat.composer.skillPicker", {
    id: "hide-native-skill-picker",
    mode: "hide",
    order: 100,
  });

  /* Settings are the only native extension page entry point. */
  sunshine.registerSettings({
    id: "global-skills",
    title: "Global Skills",
    subtitle: "Persistent defaults powered by Mod Kernel v2",
    icon: "extension",
    categories: [{ id: "global", title: "Global skills", sections: [{ settings: [] }] }],
  });
});
