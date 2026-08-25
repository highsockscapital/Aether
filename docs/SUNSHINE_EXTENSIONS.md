# Sunshine Extensions and Mod Kernel

Sunshine follows Pi Coding Agent's trusted-code extension model, but adds an
Android-specific Mod Kernel. One package can contain any combination of:

- `pi.extensions`: Pi-compatible Agent tools, commands, hooks, and logic.
- `sunshine.extensions`: hot-reloadable TypeScript/JavaScript UI and app logic.
- `sunshine.native`: restart-loaded Kotlin/DEX code with direct Android and
  Compose access.

There is intentionally no permission sandbox. Installing an extension means
trusting it with the Node runtime, Android application context, Sunshine services,
local runtimes, and workspace. Native Mods can use reflection or Android APIs
directly; the public registries are convenience and interoperability APIs, not
security boundaries.

## Package format

```json
{
  "name": "my-sunshine-mod",
  "version": "1.0.0",
  "pi": {
    "extensions": ["./agent.ts"]
  },
  "sunshine": {
    "api": {
      "min": 2,
      "max": 2
    },
    "extensions": ["./android.ts"],
    "native": {
      "api": {
        "min": 1,
        "max": 1
      },
      "classpath": ["./native/mod.dex"],
      "libraryPath": ["./native/lib"],
      "entrypoints": ["com.example.sunshine.MyNativeMod"]
    }
  }
}
```

Script-only packages do not need `sunshine.native`. Native-only zip packages are
also importable. Imported packages live under `~/.sunshine/extensions` inside
Sunshine's managed Alpine filesystem.

Script files under `~/.sunshine/extensions`, `~/.pi/agent/extensions`, and
installed package entrypoint directories are watched and reloaded
automatically. Reloads are atomic: all Script factories must load successfully
before the new runtime replaces the previous one. A failed load or cleanup is
reported on the Extensions page, and a rejected load keeps the last working
runtime active.

Native classpaths from imported extensions and npm-installed packages are
discovered and loaded only during app process startup, so installing, updating,
removing, or changing a Native Mod requires restarting Sunshine.

ZIP imports install runtime npm dependencies before replacing the currently
installed copy. A bundled `node_modules` directory is used as-is; otherwise
Sunshine runs `npm ci --omit=dev` when a lockfile is present and
`npm install --omit=dev` when it is not. Failed dependency installation or
runtime reload restores the previous imported package.

## Script Mod API v2

Install the declarations for editor completion and TypeScript checking:

```bash
npm install --save-dev @highsockscapital/sunshine-extension-api
```

The npm package contains declarations only. Sunshine supplies the implementation
through its trusted Script runtime.

```ts
interface SunshineExtensionAPI {
  readonly apiVersion: 2;
  readonly extension: { id: string; name: string; path: string };
  readonly ui: typeof ui;
  readonly host: {
    invoke(method: string, args?: object): Promise<object>;
  };
  readonly services: {
    list(): Promise<object>;
    describe(service: string): Promise<object>;
    invoke(service: string, method: string, args?: object): Promise<object>;
  };
  readonly state: {
    get(path?: string): Promise<object>;
    patch(path: string, value: unknown): Promise<object>;
    transaction(operations: Array<{
      op?: "set" | "remove";
      path: string;
      value?: unknown;
    }>): Promise<object>;
  };
  readonly storage: {
    get<T>(key: string, fallback?: T): T;
    set(key: string, value: unknown): void;
    delete(key: string): void;
    clear(): void;
    snapshot(): object;
  };
  readonly messages: {
    append(type: string, payload?: object, text?: string): Promise<object>;
  };

  registerSurface(slot: string, definition: SurfaceDefinition): () => void;
  registerComponent(target: string, definition: ComponentDefinition): () => void;
  registerSettings(definition: SettingsDefinition): () => void;
  registerComposerMenuItem(definition: ComposerMenuItemDefinition): () => void;
  registerMessageType(definition: MessageTypeDefinition): () => void;
  registerToolTitle(
    toolName: string,
    runningTitle: string,
    completedTitle: string,
    priority?: number,
  ): () => void;
  registerAction(id: string, handler: ActionHandler): () => void;
  on(event: string, handler: EventHandler): () => void;
  intercept(operation: string, handler: EventHandler): () => void;
  invalidate(): void;
  notify(message: string, level?: "info" | "warning" | "error"): void;
}
```

Factories may be async and may return a cleanup function. Extension storage is
persisted in `~/.sunshine/app-extension-state.json`.

### API compatibility

Script packages may constrain the integer Script API version:

```json
{
  "sunshine": {
    "api": {
      "min": 2,
      "max": 2,
      "allowNewer": false
    }
  }
}
```

An exact integer such as `"api": 2` is also accepted. Missing declarations
remain compatible for legacy packages. An incompatible or malformed range
rejects the atomic reload and leaves the last working runtime active.

Native Mods use an independent version range under `sunshine.native.api`.
The current Native API version is `1`. `allowNewer: true` lets a Mod opt into
loading on a runtime newer than its declared maximum.

For a package shared with Pi, keep the Pi factory as the default export and
export the Sunshine factory as `activateSunshine` or `sunshine`:

```ts
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { defineSunshineExtension, ui } from "@highsockscapital/sunshine-extension-api";

export default function activatePi(pi: ExtensionAPI) {
  pi.registerCommand("hello", {
    description: "Pi-compatible command",
    handler: async (_args, ctx) => ctx.ui.notify("Hello from Pi", "info"),
  });
}

export const activateSunshine = defineSunshineExtension((sunshine) => {
  sunshine.registerSurface("chat.composer.top", {
    id: "hello",
    render: () => ui.button("Insert prompt", "insert"),
  });
  sunshine.registerAction("insert", () =>
    sunshine.state.patch("draft_input", "Explain this repository.")
  );
});
```

## Tool titles

Script Mods can provide the user-facing title shown on a Pi tool card while
the tool is running and after it completes. Tool-name matching is
case-insensitive, and script-registered titles share the same priority-ordered
registry as Native Mod titles. A higher `priority` wins; for equal priorities
the latest script registration wins.

```ts
sunshine.registerToolTitle(
  "web_search",
  "Searching the web",
  "Searched the web",
  200,
);
```

`priority` defaults to `100`. The returned cleanup function removes the title
mapping. Tool names, running titles, and completed titles must be non-blank.
Script title registrations hot-reload with the extension and are automatically
removed when the extension is reloaded, disabled, or uninstalled.

## UI surfaces

Surfaces add content without replacing the built-in UI.

| Slot | Location |
| --- | --- |
| `app.overlay` | Full application overlay |
| `chat.top` | Under the chat top bar |
| `chat.empty` | Empty-conversation surface |
| `chat.list.start` | Before committed messages |
| `chat.list.end` | After messages and pending work |
| `chat.composer.top` | Directly above the composer |
| `settings.hub` | Top of the settings hub |
| `drawer.header` | Fixed below the drawer title and search controls |
| `drawer.footer` | Fixed full-width content above the floating new-chat action |
| `drawer.list.end` | After the conversation list |
| `drawer` | Legacy conversation-drawer list content |

```ts
sunshine.registerSurface("chat.composer.top", {
  id: "status",
  order: 10,
  render: ({ is_running, draft_input, storage }) =>
    ui.row([
      ui.text(is_running ? "Agent running" : "Ready"),
      ui.text(`${String(draft_input).length} chars`),
      ui.text(`Count: ${storage.count ?? 0}`),
    ]),
});
```

The drawer header and footer remain fixed while the conversation list scrolls.
The legacy `drawer` slot remains supported and is rendered at the list tail in
this order: `drawer`, `drawer.list.end`, then built-in extension page launchers.

The `drawer.opened` event carries an empty event payload and the current
extension context. On mobile it fires once for each closed-to-open transition,
including swipe gestures. On tablet it fires once when the permanent drawer
enters composition; stable-open recomposition does not repeat it.

```ts
sunshine.on("drawer.opened", (_data, context) => {
  sunshine.notify(`Drawer opened on ${context.screen}`);
});
```

## Replace, wrap, hide, before, and after

Components modify a named built-in Compose target:

| Target | Built-in UI |
| --- | --- |
| `app.content` | Entire routed Sunshine application content |
| `chat.screen` | Complete chat screen |
| `settings.screen` | Complete settings screen |
| `chat.composer.actionTray` | Selected Skill/MCP/Agent Mode tray |
| `chat.composer.skillPicker` | Skill rows in the composer plus menu |

Supported modes are `before`, `after`, `replace`, `wrap`, and `hide`.
Registrations are ordered by `order`; the last `replace` or `hide` is decisive.
Wrappers are then composed around that center.

```ts
sunshine.registerComponent("chat.composer.actionTray", {
  id: "replacement",
  mode: "replace",
  order: 100,
  render: () => ui.card([
    ui.text("My replacement tray"),
  ]),
});

sunshine.registerComponent("chat.composer.skillPicker", {
  id: "remove-native-picker",
  mode: "hide",
  order: 100,
});
```

A wrapper places `ui.core()` where the wrapped native or replacement content
should render:

```ts
sunshine.registerComponent("chat.composer.actionTray", {
  id: "wrapper",
  mode: "wrap",
  render: () => ui.column([
    ui.text("Before the original"),
    ui.core(),
    ui.text("After the original"),
  ]),
});
```

Additional targets can be introduced by Sunshine core or by future component
registries without changing the API.

## Declarative UI tree and WebView escape hatch

Supported native node types include:

- `text`, `code`
- `column`, `row`, `box`, `scroll`
- `card`
- `button`, `iconButton`
- `switch`, `input`
- `spacer`, `progress`
- `web`
- `core` for wrapper nesting

Rows fill the available width and remain on one line by default. Set
`wrap: true` for narrow Android layouts. `rowSpacing` controls wrapped-line
spacing and `maxItemsInEachRow` caps each line. Button labels render on one
line; an explicit `width` still overrides fill behavior. A direct child of a
non-wrapping row may set `weight` to consume only the remaining horizontal
space while preserving intrinsic-width controls beside it.

Actions are extension-local:

```ts
sunshine.registerAction("increment", ({ amount = 1 }) => {
  const count = sunshine.storage.get("count", 0) + Number(amount);
  sunshine.storage.set("count", count);
});

ui.button("Increment", "increment", {
  args: { amount: 1 },
});
```

For unrestricted HTML micro-UIs, use a WebView node:

```ts
ui.web({
  height: 320,
  html: `
    <button onclick='Sunshine.postMessage(JSON.stringify({
      action: "clicked",
      args: { source: "web" }
    }))'>Run</button>
  `,
});
```

JavaScript, DOM storage, network access, file access, and content access are
enabled. `Sunshine.postMessage(string)` invokes a registered extension action.

## Settings pages

`registerSettings` adds a page to the dedicated Extensions group between
Reliability and Agent Skills. The host renders the page with the same settings
scaffold, cards, spacing, typography, and controls as Sunshine's built-in pages;
extensions provide data only and cannot override page layout or styling.

```ts
sunshine.registerSettings({
  id: "preferences",
  icon: "settings",
  title: "Preferences",
  subtitle: "Extension behavior",
  sections: [{
    title: "General",
    description: "Configure extension behavior",
    settings: [
      { id: "endpoint", type: "text", label: "Endpoint", default: "https://example.com" },
      { id: "enabled", type: "toggle", label: "Enabled", default: true },
      {
        id: "mode",
        type: "select",
        label: "Mode",
        options: [
          { value: "fast", label: "Fast" },
          { value: "quality", label: "Quality" },
        ],
        default: "fast",
      },
    ],
  }],
});
```

The Sunshine Agent can inspect these native Settings Pages with
`sunshine_config_get({ categories: ["extensions"] })`. It can update value
controls through the same registered settings actions used by the native UI:

```json
{
  "category": "extensions",
  "settings": {
    "extension_id": "my-extension",
    "settings_id": "preferences",
    "values": {
      "enabled": true,
      "mode": "quality"
    }
  }
}
```

Pass that payload to `sunshine_config_set`. Text, password, textarea, number,
toggle, selection, tab, and slider values are writable. Buttons, links, labels,
dividers, and spacers are not treated as setting values. This interface only
covers Settings Pages registered through `sunshine.extensions`; compatible Pi
Extension configuration files remain regular files managed by the Agent.

Extensions have exactly one native page registration API: `registerSettings`.
There is no `registerPage`, `registerSettingsPage`, drawer-page, or full-screen
page API. Extensions cannot create pages outside the native Settings flow.

`registerSettings` supports either the legacy flat `sections` form or optional
`categories`. When categories are present, Sunshine first shows a native list of
child settings pages, then renders the selected category's sections and controls.
Use one form or the other, not both. Setting IDs must be unique within a
settings registration and values remain persisted per extension, settings ID,
and setting ID.

```ts
sunshine.registerSettings({
  id: "preferences",
  title: "Preferences",
  categories: [
    {
      id: "general",
      title: "General",
      sections: [{ settings: [{ id: "enabled", label: "Enabled", type: "toggle", default: true }] }],
    },
    { id: "advanced", title: "Advanced", sections: [{ settings: [] }] },
  ],
});
```

Supported control types are `text`, `password`, `textarea`, `number`,
`toggle`, `select`/`dropdown`, `segmented`, `tab`/`tabs`, `slider`, `button`,
`link`, `label`, `divider`, and `spacer`. Value controls are persisted automatically per
extension, page, and setting ID. Buttons dispatch their `action` and `args`;
links open `url`, or dispatch `action` when no URL is supplied.

## Service Registry

Services are discoverable and replaceable. The active implementation for an ID
is the highest-priority registration; removing it reveals the next
implementation. Sunshine core services use a deliberately low priority so a
Native Mod can replace them with the default Native Mod priority.

```ts
const catalog = await sunshine.services.list();
const skillsApi = await sunshine.services.describe("skills");
const skills = await sunshine.services.invoke("skills", "list");
```

Built-in services:

### `skills`

- `list`
- `getSelection`
- `setSelection({ ids, scope, session_id? })`
- `setSelected({ skill_id, selected, scope, session_id? })`

Scopes are `current`, `default`/`global`, or `both`/`current_and_default`.
`both` updates current and default selections independently.

### `state`

- `get({ path })`
- `transaction({ operations })`

## Public state transactions

Supported paths currently include:

- `draft_input`
- `selected_skill_ids`
- `default_skill_ids`
- `agent_mode_enabled`
- `selected_model_key`
- `screen`

```ts
await sunshine.state.transaction([
  { path: "draft_input", value: "Review the current changes." },
  { path: "agent_mode_enabled", value: true },
]);
```

Transactions apply operations in order. They provide a common mutation API,
not database-level rollback across unrelated Android repositories.

## Operation interception

Script Mods register with `sunshine.intercept()`. Native interceptors and Script
interceptors form one chain; Native interceptors run first, followed by Script
handlers in extension registration order.

Built-in operations:

- `chat.new`
- `skills.selection`

```ts
sunshine.intercept("chat.new", ({ selected_skill_ids }) => ({
  selected_skill_ids,
}));

sunshine.intercept("skills.selection", ({ skill_id, selected }) => {
  if (skill_id === "locked-skill" && !selected) {
    return { cancel: true, reason: "This Skill is locked by the Mod." };
  }
});
```

Returning top-level fields merges them into the chained payload. Returning
`{ payload: {...} }` explicitly supplies payload fields. Returning
`{ cancel: true, reason }` stops the operation.

## Native Kotlin/DEX Mods

Native Mods implement:

```kotlin
interface SunshineNativeMod {
    fun onLoad(context: SunshineNativeModContext)
    fun onUnload()
}
```

The context intentionally exposes:

- the Android `Application`;
- the package root and Mod classloader;
- the complete `SunshineModKernel`;
- convenience registration methods for services, operation interceptors, and
  native Compose components;
- Sunshine's diagnostic logger.

This is the unrestricted tier. A Native Mod may ignore the convenience APIs
and directly use Android APIs, reflection, JNI, or Sunshine implementation
classes.

### Native service replacement

```kotlin
class MyNativeMod : SunshineNativeMod {
    override fun onLoad(context: SunshineNativeModContext) {
        context.registerService(
            id = "skills",
            priority = 500,
            methods = listOf(SunshineModServiceMethod("list")),
        ) { method, args ->
            JSONObject().put("provided_by", context.modId)
        }
    }
}
```

### Native operation interceptor

```kotlin
context.intercept("chat.new", priority = 500) { payload, _ ->
    SunshineModOperationDecision(
        payload = payload.put("selected_skill_ids", JSONArray()),
    )
}
```

Use `"*"` to observe/intercept every operation exposed through the registry.

### Native tool titles

Native Mods can also provide tool-card titles. Prefer
`sunshine.registerToolTitle()` in Script Mods unless the mapping must be loaded
before the Script runtime starts or needs to coexist with another Native-only
feature. Native and Script registrations share one priority-ordered registry.

```kotlin
context.registerToolTitle(
    toolName = "weather",
    runningTitle = "Checking the weather",
    completedTitle = "Checked the weather",
    priority = 200,
)
```

The returned cleanup function removes the mapping. Titles must be non-blank.

### Native Compose replacement

Native component renderers are compiled with the Compose compiler and receive
the complete `SunshineUiState`, a JSON public-state snapshot, an async host
bridge, and a `next` composable:

```kotlin
context.registerComponent(
    target = "chat.composer.actionTray",
    id = "native-tray",
    mode = SunshineNativeComponentMode.Replace,
    priority = 500,
    renderer = object : SunshineNativeComponentRenderer {
        @Composable
        override fun render(
            context: SunshineNativeComponentContext,
            next: @Composable () -> Unit,
        ) {
            Text("Rendered from a dynamically loaded Native Mod")
        }
    },
)
```

The Native modes have the same `Before`, `After`, `Replace`, `Wrap`, and `Hide`
semantics as Script components. Native components surround the Script
component pipeline, so a Native replacement can decisively replace both core
and Script-provided content.

### Classpath format

`DexClassLoader` accepts `.dex`, `.apk`, or jar/zip files containing
`classes.dex`. A plain JVM `.class` jar is not sufficient; run Android D8/R8 on
the compiled output. Compose renderers must be compiled with a Compose compiler
compatible with the Sunshine build they target.

Native Mods currently use an intentionally source/ABI-sensitive API. They
should be rebuilt when Sunshine implementation classes or the Compose/Kotlin
toolchain changes. This is comparable to version-specific Minecraft Mods.

## Native Mod Safe Mode

Before loading any Native Mod, Sunshine arms a persistent startup marker. It is
cleared only after Mod loading finishes and the first Activity remains stable
for several seconds.

If the process ends while that marker is armed, the next start:

- skips all Native Mods;
- keeps Script/Pi extensions enabled;
- records the last entrypoint being loaded when available;
- shows Native Mod Safe Mode on the Extensions page.

The user can re-enable Native Mods for the next startup from that page. A
manual “start in Safe Mode next time” action is also available. Safe Mode is a
crash-loop recovery mechanism, not a security sandbox.

## Issue #27 example

[`examples/global-skills-mod`](../examples/global-skills-mod) demonstrates that
the requested global/collapsible Skill workflow can be implemented entirely as
a Script Mod:

- replaces `chat.composer.actionTray`;
- hides `chat.composer.skillPicker`;
- reads and writes Sunshine's real native Skill selection service;
- persists defaults for every new chat;
- adds a full Global Skills page.

This is also the reference example for combining declarative UI replacement
with native application state instead of maintaining a parallel extension-only
state.

## Existing host methods

The lower-level `sunshine.host.invoke()` bridge remains available:

| Method | Purpose |
| --- | --- |
| `app.getState` | Read settings, sessions, draft, Skills, and runtime UI state |
| `app.setDraftInput` | Replace composer text |
| `app.appendDraftInput` | Append composer text |
| `app.sendMessage` | Set optional text and submit, queue, or steer |
| `app.newChat` | Start a draft conversation |
| `app.selectSession` | Select a session by ID |
| `app.openScreen` | Open `chat` or `settings` |
| `app.pauseGeneration` | Pause the current generation |
| `app.setReasoningEffort` | Change reasoning effort |
| `app.setAgentMode` | Toggle Agent Mode |
| `app.setModel` | Select a model key |
| `app.notify` | Show an Android toast |
| `settings.get` | Read settings and providers |
| `settings.patch` | Patch supported settings |
| `runtime.execute` | Execute an arbitrary Alpine/Termux command |

Prefer discoverable services and state transactions for reusable Mods; use host
methods for app-specific operations that do not yet have a service.
