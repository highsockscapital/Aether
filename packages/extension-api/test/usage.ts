import {
  defineSunshineExtension,
  ui,
  type SunshineExtensionAPI,
} from "@highsockscapital/sunshine-extension-api";

const factory = defineSunshineExtension((sunshine) => {
  sunshine.registerSurface("chat.composer.top", {
    render: ({ storage }) =>
      ui.card([
        ui.text(String(storage.count ?? 0)),
        ui.button("Increment", "increment"),
      ]),
  });
  sunshine.registerAction("increment", () => {
    const count = sunshine.storage.get("count", 0) + 1;
    sunshine.storage.set("count", count);
  });
  sunshine.registerSettings({
    id: "preferences",
    title: "Preferences",
    categories: [{ id: "general", title: "General", sections: [{ settings: [{ id: "enabled", label: "Enabled", type: "toggle", default: true }] }] }],
  });
  sunshine.registerComposerMenuItem({ id: "run", title: "Run", action: "run" });
  sunshine.registerMessageType({ type: "demo", render: ({ message }) => ui.text(String(message.text ?? "")) });
  sunshine.registerToolTitle("demo_search", "Searching demos", "Searched demos", 200);
  sunshine.registerAction("message", () => sunshine.messages.append("demo", { text: "hello" }));
});

const acceptsApi = (_api: SunshineExtensionAPI) => factory;
void acceptsApi;
