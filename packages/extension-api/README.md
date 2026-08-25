# @highsockscapital/sunshine-extension-api

TypeScript declarations for Sunshine Script Extensions.

```bash
npm install --save-dev @highsockscapital/sunshine-extension-api
```

```ts
import { defineSunshineExtension, ui } from "@highsockscapital/sunshine-extension-api";

export const activateSunshine = defineSunshineExtension((sunshine) => {
  sunshine.registerSurface("chat.composer.top", {
    render: () => ui.text("Hello from Sunshine"),
  });
});
```

Extensions register data-only settings schemas. This is the only native page
registration API; extensions cannot create full-screen or drawer pages. Sunshine renders settings with the
same scaffold, cards, spacing, typography, and controls as its built-in
Settings pages, in a dedicated group between Reliability and Agent Skills.
Values are stored per extension, page, and setting and restored across reloads:

```ts
sunshine.registerSettings({
  id: "preferences",
  title: "Preferences",
  sections: [{
    title: "General",
    settings: [
      { id: "enabled", label: "Enabled", type: "toggle", default: true },
      { id: "endpoint", label: "Endpoint", type: "text", placeholder: "https://..." },
      { id: "mode", label: "Mode", type: "select", options: [
        { value: "fast", label: "Fast" },
        { value: "quality", label: "Quality" },
      ] },
    ],
  }],
});
```

Settings may optionally contain child categories. The host shows the category
list first and then renders the selected category with the same native controls:

```ts
sunshine.registerSettings({
  id: "preferences",
  title: "Preferences",
  categories: [
    { id: "general", title: "General", sections: [{ settings: [] }] },
    { id: "advanced", title: "Advanced", sections: [{ settings: [] }] },
  ],
});
```

Supported controls are `text`, `password`, `textarea`, `number`, `toggle`,
`select`/`dropdown`, `segmented`, `tab`/`tabs`, `slider`, `button`, `link`,
`label`, `divider`, and `spacer`. Extensions do not provide page padding, card shapes, typography,
or other page-level styling.

The chat composer plus menu and transcript support extension-owned entries:

```ts
sunshine.registerComposerMenuItem({
  id: "summarize",
  title: "Summarize thread",
  icon: "auto",
  action: "summarize",
});
sunshine.registerMessageType({
  type: "summary",
  title: "Summary",
  render: ({ message }) => ui.card([
    ui.text(String(message.title ?? "Summary")),
    ui.text(String(message.body ?? ""), { color: "muted" }),
  ]),
});
sunshine.registerAction("show-summary", () =>
  sunshine.messages.append("summary", { title: "Done", body: "Thread summarized." }));
```

This package intentionally contains declarations only. Sunshine injects the
runtime implementation when it loads an Extension.

The npm package major version matches `sunshine.apiVersion`. Package `2.x`
describes Script API version 2.
