import { createHash } from "node:crypto";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import { DefaultPackageManager } from "../node_modules/@earendil-works/pi-coding-agent/dist/core/package-manager.js";
import { SettingsManager } from "../node_modules/@earendil-works/pi-coding-agent/dist/core/settings-manager.js";
import { createJiti } from "jiti/static";
import type {
  SunshineActionContext,
  SunshineComponentDefinition,
  SunshineComponentMode,
  SunshineEventContext,
  SunshineExtensionAPI,
  SunshineExtensionFactory,
  SunshineJsonObject,
  SunshineSettingsDefinition,
  SunshineSettingsSection,
  SunshineSettingsCategory,
  SunshineComposerMenuItemDefinition,
  SunshineMessageTypeDefinition,
  SunshineRenderContext,
  SunshineSurfaceDefinition,
  SunshineUi,
  SunshineView,
} from "../../packages/extension-api/src/index.js";
export type {
  SunshineActionContext,
  SunshineComponentDefinition,
  SunshineComponentMode,
  SunshineEventContext,
  SunshineExtensionAPI,
  SunshineExtensionFactory,
  SunshineJsonObject,
  SunshineSettingsDefinition,
  SunshineSettingsSection,
  SunshineSettingsCategory,
  SunshineComposerMenuItemDefinition,
  SunshineMessageTypeDefinition,
  SunshineRenderContext,
  SunshineSurfaceDefinition,
  SunshineUi,
  SunshineView,
} from "../../packages/extension-api/src/index.js";
import {
  ensureExtensionPackageDependencies,
  packageRootForExtensionPath,
} from "./extension-dependencies.js";

export interface SunshineExtensionTransport {
  requestHost(method: string, args: SunshineJsonObject): Promise<SunshineJsonObject>;
  invalidate(version: number): void;
  notify(message: string, level: "info" | "warning" | "error"): void;
}

interface SunshineExtensionDescriptor {
  id: string;
  name: string;
  path: string;
  explicit: boolean;
  packageSource?: string;
  compatibilityError?: string;
}

interface DiscoveredSunshineEntry {
  path: string;
  name: string;
  explicit: boolean;
  packageSource?: string;
  compatibilityError?: string;
}

interface LoadedSunshineExtension extends SunshineExtensionDescriptor {
  cleanup?: () => void | Promise<void>;
}

interface RegisteredSurface {
  id: string;
  extension: LoadedSunshineExtension;
  slot: string;
  order: number;
  render: SunshineSurfaceDefinition["render"];
}

interface RegisteredComponent {
  id: string;
  extension: LoadedSunshineExtension;
  target: string;
  mode: SunshineComponentMode;
  order: number;
  render: SunshineSurfaceDefinition["render"];
}

interface RegisteredSettings {
  id: string;
  extension: LoadedSunshineExtension;
  definition: SunshineSettingsDefinition;
}

interface RegisteredComposerMenuItem {
  id: string;
  extension: LoadedSunshineExtension;
  definition: SunshineComposerMenuItemDefinition;
}

interface RegisteredMessageType {
  id: string;
  extension: LoadedSunshineExtension;
  type: string;
  title: string;
  icon: string;
  render: SunshineMessageTypeDefinition["render"];
}

interface RegisteredToolTitle {
  id: string;
  extension: LoadedSunshineExtension;
  toolName: string;
  runningTitle: string;
  completedTitle: string;
  priority: number;
  sequence: number;
}

interface RegisteredAction {
  extension: LoadedSunshineExtension;
  localId: string;
  generatedBySettings?: string;
  handler: (
    payload: SunshineJsonObject,
    context: SunshineActionContext,
  ) => unknown | Promise<unknown>;
}

interface RegisteredEventHandler {
  extension: LoadedSunshineExtension;
  handler: (
    payload: SunshineJsonObject,
    context: SunshineEventContext,
  ) => unknown | Promise<unknown>;
}

interface SunshineExtensionError {
  path: string;
  extension_id?: string;
  phase: "load" | "render" | "action" | "event";
  error: string;
}

interface SunshineRuntimeState {
  cwd: string;
  extensions: LoadedSunshineExtension[];
  surfaces: Map<string, RegisteredSurface>;
  components: Map<string, RegisteredComponent>;
  settings: Map<string, RegisteredSettings>;
  composerMenuItems: Map<string, RegisteredComposerMenuItem>;
  messageTypes: Map<string, RegisteredMessageType>;
  toolTitles: Map<string, RegisteredToolTitle>;
  toolTitleSequence: number;
  actions: Map<string, RegisteredAction>;
  events: Map<string, RegisteredEventHandler[]>;
  errors: SunshineExtensionError[];
}

export interface SunshineAppExtensionLoadResult {
  reloaded: boolean;
  errors: SunshineExtensionError[];
}

const SUNSHINE_API_VERSION = 2;
const SUNSHINE_AGENT_DIRECTORY = path.join(os.homedir(), ".pi", "agent");
const SUNSHINE_EXTENSION_ROOT = path.join(os.homedir(), ".sunshine", "extensions");
const PI_EXTENSION_ROOT = path.join(SUNSHINE_AGENT_DIRECTORY, "extensions");
const SUNSHINE_STORAGE_FILE = path.join(os.homedir(), ".sunshine", "app-extension-state.json");
const SUNSHINE_JITI_CACHE = path.join(os.homedir(), ".sunshine", "cache", "jiti");
const WATCH_IGNORED_DIRECTORIES = new Set([
  ".cache",
  ".git",
  ".hg",
  ".svn",
  "native",
  "node_modules",
]);
const EXTENSION_FILE_PATTERN = /\.(?:[cm]?[jt]s)$/i;
const INDEX_FILE_NAMES = [
  "index.ts",
  "index.js",
  "index.mts",
  "index.mjs",
  "index.cts",
  "index.cjs",
];

const emptyTransport: SunshineExtensionTransport = {
  async requestHost() {
    throw new Error("The Sunshine app host is not connected.");
  },
  invalidate() {},
  notify() {},
};

let transport: SunshineExtensionTransport = emptyTransport;
let runtime: SunshineRuntimeState = createEmptyRuntime(process.cwd());
let runtimeVersion = 0;
let latestHostContext: SunshineJsonObject = {};
let persistedStorage = readPersistedStorage();
let runtimeOperationQueue: Promise<void> = Promise.resolve();
let extensionWatchers: fs.FSWatcher[] = [];
let extensionWatchTimer: NodeJS.Timeout | undefined;
let currentLoadOptions: {
  disabledExtensionPaths?: string[];
  disabledPackageSources?: string[];
} = {};

function createEmptyRuntime(cwd: string): SunshineRuntimeState {
  return {
    cwd,
    extensions: [],
    surfaces: new Map(),
    components: new Map(),
    settings: new Map(),
    composerMenuItems: new Map(),
    messageTypes: new Map(),
    toolTitles: new Map(),
    toolTitleSequence: 0,
    actions: new Map(),
    events: new Map(),
    errors: [],
  };
}

function removeExtensionRegistrations(
  runtimeState: SunshineRuntimeState,
  extension: LoadedSunshineExtension,
): void {
  for (const [id, registration] of runtimeState.surfaces) {
    if (registration.extension === extension) runtimeState.surfaces.delete(id);
  }
  for (const [id, registration] of runtimeState.components) {
    if (registration.extension === extension) runtimeState.components.delete(id);
  }
  for (const [id, registration] of runtimeState.settings) {
    if (registration.extension === extension) runtimeState.settings.delete(id);
  }
  for (const [id, registration] of runtimeState.composerMenuItems) {
    if (registration.extension === extension) runtimeState.composerMenuItems.delete(id);
  }
  for (const [id, registration] of runtimeState.messageTypes) {
    if (registration.extension === extension) runtimeState.messageTypes.delete(id);
  }
  for (const [id, registration] of runtimeState.toolTitles) {
    if (registration.extension === extension) runtimeState.toolTitles.delete(id);
  }
  for (const [id, action] of runtimeState.actions) {
    if (action.extension === extension) runtimeState.actions.delete(id);
  }
  for (const [eventName, handlers] of runtimeState.events) {
    const remaining = handlers.filter((handler) => handler.extension !== extension);
    if (remaining.length > 0) runtimeState.events.set(eventName, remaining);
    else runtimeState.events.delete(eventName);
  }
}

function retainExtensionRegistrations(
  target: SunshineRuntimeState,
  source: SunshineRuntimeState,
  extension: LoadedSunshineExtension,
): void {
  const retain = <T extends { extension: LoadedSunshineExtension }>(
    targetMap: Map<string, T>,
    sourceMap: Map<string, T>,
  ) => {
    for (const [id, registration] of sourceMap) {
      if (registration.extension === extension) targetMap.set(id, registration);
    }
  };
  retain(target.surfaces, source.surfaces);
  retain(target.components, source.components);
  retain(target.settings, source.settings);
  retain(target.composerMenuItems, source.composerMenuItems);
  retain(target.messageTypes, source.messageTypes);
  retain(target.toolTitles, source.toolTitles);
  for (const [id, action] of source.actions) {
    if (action.extension === extension) target.actions.set(id, action);
  }
  for (const [eventName, handlers] of source.events) {
    const retained = handlers.filter((handler) => handler.extension === extension);
    if (retained.length > 0) {
      target.events.set(eventName, [...(target.events.get(eventName) ?? []), ...retained]);
    }
  }
  target.toolTitleSequence = Math.max(target.toolTitleSequence, source.toolTitleSequence);
}

function asObject(value: unknown): SunshineJsonObject {
  return value && typeof value === "object" && !Array.isArray(value)
    ? (value as SunshineJsonObject)
    : {};
}

function cloneJson<T>(value: T): T {
  if (value === undefined) return value;
  return JSON.parse(JSON.stringify(value)) as T;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function readJsonFile(filePath: string): SunshineJsonObject | undefined {
  try {
    return asObject(JSON.parse(fs.readFileSync(filePath, "utf8")));
  } catch {
    return undefined;
  }
}

function readPersistedStorage(): Record<string, SunshineJsonObject> {
  return asObject(readJsonFile(SUNSHINE_STORAGE_FILE)) as Record<string, SunshineJsonObject>;
}

function isPathDisabled(
  candidatePath: string,
  disabledPaths: Set<string>,
): boolean {
  const candidate = path.resolve(candidatePath);
  return [...disabledPaths].some((disabledPath) => {
    const relative = path.relative(disabledPath, candidate);
    return relative === "" || (
      !relative.startsWith(`..${path.sep}`) &&
      relative !== ".." &&
      !path.isAbsolute(relative)
    );
  });
}

function writePersistedStorage(): void {
  fs.mkdirSync(path.dirname(SUNSHINE_STORAGE_FILE), { recursive: true });
  const temporaryPath = `${SUNSHINE_STORAGE_FILE}.${process.pid}.tmp`;
  fs.writeFileSync(temporaryPath, JSON.stringify(persistedStorage, null, 2), "utf8");
  fs.renameSync(temporaryPath, SUNSHINE_STORAGE_FILE);
}

function extensionStorage(extensionId: string): SunshineJsonObject {
  const current = asObject(persistedStorage[extensionId]);
  persistedStorage[extensionId] = current;
  return current;
}

function bumpVersion(): void {
  runtimeVersion += 1;
  transport.invalidate(runtimeVersion);
}

async function withRuntimeLock<T>(operation: () => Promise<T>): Promise<T> {
  const previous = runtimeOperationQueue;
  let release: () => void = () => {};
  runtimeOperationQueue = new Promise<void>((resolve) => {
    release = resolve;
  });
  await previous;
  try {
    return await operation();
  } finally {
    release();
  }
}

function recordRuntimeError(
  runtimeState: SunshineRuntimeState,
  error: SunshineExtensionError,
): void {
  runtimeState.errors.push(error);
  if (runtimeState.errors.length > 100) {
    runtimeState.errors.splice(0, runtimeState.errors.length - 100);
  }
}

function stableExtensionId(name: string, entryPath: string): string {
  const slug = name
    .toLowerCase()
    .replace(/[^a-z0-9._-]+/g, "-")
    .replace(/^-+|-+$/g, "") || "extension";
  const hash = createHash("sha256").update(path.resolve(entryPath)).digest("hex").slice(0, 10);
  return `${slug}:${hash}`;
}

function manifestApiCompatibilityError(
  manifest: SunshineJsonObject | undefined,
): string | undefined {
  const sunshine = manifest?.sunshine;
  if (!sunshine || typeof sunshine !== "object" || Array.isArray(sunshine)) {
    return undefined;
  }
  const configured = (sunshine as SunshineJsonObject).api;
  if (configured === undefined) return undefined;
  if (typeof configured === "number") {
    if (!Number.isInteger(configured) || configured <= 0) {
      return "sunshine.api must be a positive integer or an API range object.";
    }
    return configured === SUNSHINE_API_VERSION
      ? undefined
      : `Requires Sunshine Script API ${configured}, but this runtime provides ${SUNSHINE_API_VERSION}.`;
  }
  if (!configured || typeof configured !== "object" || Array.isArray(configured)) {
    return "sunshine.api must be a positive integer or an API range object.";
  }
  const api = configured as SunshineJsonObject;
  const minimum = api.min;
  const maximum = api.max;
  const allowNewer = api.allowNewer === true;
  if (
    minimum !== undefined &&
    (!Number.isInteger(minimum) || Number(minimum) <= 0)
  ) {
    return "sunshine.api.min must be a positive integer.";
  }
  if (
    maximum !== undefined &&
    (!Number.isInteger(maximum) || Number(maximum) <= 0)
  ) {
    return "sunshine.api.max must be a positive integer.";
  }
  if (minimum === undefined && maximum === undefined) {
    return "sunshine.api must declare min, max, or both.";
  }
  if (
    minimum !== undefined &&
    maximum !== undefined &&
    Number(minimum) > Number(maximum)
  ) {
    return "sunshine.api.min cannot be greater than sunshine.api.max.";
  }
  if (minimum !== undefined && SUNSHINE_API_VERSION < Number(minimum)) {
    return `Requires Sunshine Script API ${minimum} or newer, but this runtime provides ${SUNSHINE_API_VERSION}.`;
  }
  if (
    maximum !== undefined &&
    SUNSHINE_API_VERSION > Number(maximum) &&
    !allowNewer
  ) {
    return `Supports Sunshine Script API through ${maximum}, but this runtime provides ${SUNSHINE_API_VERSION}.`;
  }
  return undefined;
}

function manifestSunshineEntries(
  directory: string,
  packageSource?: string,
): Array<{
  path: string;
  name: string;
  explicit: true;
  compatibilityError?: string;
}> {
  const manifest = readJsonFile(path.join(directory, "package.json"));
  const sunshine = asObject(manifest?.sunshine);
  const configured = sunshine.extensions;
  if (!Array.isArray(configured)) return [];
  const compatibilityError = manifestApiCompatibilityError(manifest);
  const packageName =
    (typeof manifest?.name === "string" && manifest.name.trim()) ||
    path.basename(directory);
  return configured
    .filter((entry): entry is string => typeof entry === "string" && entry.trim().length > 0)
    .map((entry) => ({
      path: path.resolve(directory, entry),
      name: packageName,
      explicit: true as const,
      packageSource,
      compatibilityError,
    }))
    .filter((entry) => fs.existsSync(entry.path));
}

function implicitSunshineEntry(
  candidatePath: string,
): Array<{
  path: string;
  name: string;
  explicit: false;
  compatibilityError?: string;
}> {
  let stat: fs.Stats;
  try {
    stat = fs.statSync(candidatePath);
  } catch {
    return [];
  }
  if (stat.isFile()) {
    return EXTENSION_FILE_PATTERN.test(candidatePath)
      ? [{ path: path.resolve(candidatePath), name: path.basename(candidatePath, path.extname(candidatePath)), explicit: false }]
      : [];
  }
  if (!stat.isDirectory()) return [];
  const configured = manifestSunshineEntries(candidatePath);
  if (configured.length > 0) return [];
  const manifest = readJsonFile(path.join(candidatePath, "package.json"));
  const compatibilityError = manifestApiCompatibilityError(manifest);
  for (const fileName of INDEX_FILE_NAMES) {
    const entryPath = path.join(candidatePath, fileName);
    if (fs.existsSync(entryPath)) {
      return [{
        path: entryPath,
        name: path.basename(candidatePath),
        explicit: false,
        compatibilityError,
      }];
    }
  }
  return [];
}

function entriesInRoot(
  root: string,
): DiscoveredSunshineEntry[] {
  let entries: fs.Dirent[];
  try {
    entries = fs.readdirSync(root, { withFileTypes: true });
  } catch {
    return [];
  }
  return entries
    .filter((entry) => !entry.name.startsWith(".sunshine-import-"))
    .sort((left, right) => left.name.localeCompare(right.name))
    .flatMap<DiscoveredSunshineEntry>((entry) => {
      const candidatePath = path.join(root, entry.name);
      if (entry.isDirectory()) {
        const configured = manifestSunshineEntries(candidatePath);
        if (configured.length > 0) return configured;
      }
      return implicitSunshineEntry(candidatePath);
    });
}

function createPackageManager(cwd: string): DefaultPackageManager {
  const settingsManager = SettingsManager.create(cwd, SUNSHINE_AGENT_DIRECTORY, {
    projectTrusted: false,
  });
  return new DefaultPackageManager({
    cwd,
    agentDir: SUNSHINE_AGENT_DIRECTORY,
    settingsManager,
  });
}

function packageSunshineEntries(
  cwd: string,
): DiscoveredSunshineEntry[] {
  const packageManager = createPackageManager(cwd);
  return packageManager
    .listConfiguredPackages()
    .filter((configuredPackage) => configuredPackage.scope === "user")
    .flatMap((configuredPackage) => {
      if (!configuredPackage.installedPath) return [];
      return manifestSunshineEntries(
        configuredPackage.installedPath,
        configuredPackage.source,
      ).map((entry) => ({
        ...entry,
        packageSource: configuredPackage.source,
      }));
    });
}

async function discoverSunshineExtensionEntries(
  cwd: string,
  loadOptions: {
    disabledExtensionPaths?: string[];
    disabledPackageSources?: string[];
  } = {},
): Promise<SunshineExtensionDescriptor[]> {
  const disabledExtensionPaths = new Set(
    (loadOptions.disabledExtensionPaths ?? []).map((entry) => path.resolve(entry)),
  );
  const disabledPackageSources = new Set(loadOptions.disabledPackageSources ?? []);
  const entries = [
    ...entriesInRoot(SUNSHINE_EXTENSION_ROOT),
    ...entriesInRoot(PI_EXTENSION_ROOT),
    ...packageSunshineEntries(cwd),
  ];
  const seen = new Set<string>();
  return entries.flatMap((entry) => {
    const resolvedPath = path.resolve(entry.path);
    if (
      isPathDisabled(resolvedPath, disabledExtensionPaths) ||
      (entry.packageSource && disabledPackageSources.has(entry.packageSource))
    ) {
      return [];
    }
    if (seen.has(resolvedPath)) return [];
    seen.add(resolvedPath);
    return [{
      id: stableExtensionId(entry.name, resolvedPath),
      name: entry.name,
      path: resolvedPath,
      explicit: entry.explicit,
      packageSource: entry.packageSource,
      compatibilityError: entry.compatibilityError,
    }];
  });
}

function normalizeSurfaceDefinition(
  definition:
    | SunshineSurfaceDefinition
    | SunshineView
    | ((context: SunshineRenderContext) => SunshineView | Promise<SunshineView>),
): SunshineSurfaceDefinition {
  if (typeof definition === "function") return { render: definition };
  if (
    definition &&
    typeof definition === "object" &&
    !Array.isArray(definition) &&
    (
      Object.prototype.hasOwnProperty.call(definition, "render") ||
      Object.prototype.hasOwnProperty.call(definition, "tree") ||
      Object.prototype.hasOwnProperty.call(definition, "order") ||
      Object.prototype.hasOwnProperty.call(definition, "id") ||
      Object.prototype.hasOwnProperty.call(definition, "mode")
    )
  ) {
    return definition as SunshineSurfaceDefinition;
  }
  return { tree: definition as SunshineView };
}

function scopedId(extensionId: string, localId: string): string {
  return `${extensionId}:${localId.trim()}`;
}

function renderValue(definition: SunshineSurfaceDefinition): SunshineSurfaceDefinition["render"] {
  return definition.render ?? definition.tree;
}

type SunshineSetting = SunshineSettingsSection["settings"][number];

function settingDefault(setting: SunshineSetting): unknown {
  if (setting.default !== undefined) return setting.default;
  switch (setting.type) {
    case "toggle": return false;
    case "number":
    case "slider": return setting.min ?? 0;
    default: return "";
  }
}

function settingStorageKey(settingsId: string, settingId: string): string {
  return `settings:${settingsId}:${settingId}`;
}

function normalizeSettingValue(
  setting: SunshineSetting,
  value: unknown,
): unknown {
  switch (setting.type) {
    case "toggle": return value === true || value === "true";
    case "number":
    case "slider": {
      const parsed = typeof value === "number" ? value : Number(value);
      if (!Number.isFinite(parsed)) return settingDefault(setting);
      const minimum = setting.min ?? parsed;
      const clamped = Math.min(setting.max ?? parsed, Math.max(minimum, parsed));
      const step = setting.step;
      if (!step || !Number.isFinite(step) || step <= 0) return clamped;
      const snapped = minimum + Math.round((clamped - minimum) / step) * step;
      return Math.min(setting.max ?? snapped, Math.max(minimum, Number(snapped.toFixed(10))));
    }
    case "select":
    case "dropdown":
    case "segmented":
    case "tab":
    case "tabs": {
      const candidate = String(value ?? "");
      return setting.options?.some((option) => option.value === candidate)
        ? candidate
        : settingDefault(setting);
    }
    default: return String(value ?? "");
  }
}

function createApiEventRegistration(
  runtimeState: SunshineRuntimeState,
  extension: LoadedSunshineExtension,
  eventName: string,
  handler: RegisteredEventHandler["handler"],
): () => void {
  const registration = { extension, handler };
  const handlers = runtimeState.events.get(eventName) ?? [];
  handlers.push(registration);
  runtimeState.events.set(eventName, handlers);
  return () => {
    const current = runtimeState.events.get(eventName) ?? handlers;
    const updated = current.filter((entry) => entry !== registration);
    if (updated.length > 0) runtimeState.events.set(eventName, updated);
    else runtimeState.events.delete(eventName);
  };
}

function createRenderContext(extension: LoadedSunshineExtension): SunshineRenderContext {
  return {
    ...cloneJson(latestHostContext),
    extension: {
      id: extension.id,
      name: extension.name,
      path: extension.path,
    },
    storage: cloneJson(extensionStorage(extension.id)),
  };
}

function createApi(
  runtimeState: SunshineRuntimeState,
  extension: LoadedSunshineExtension,
): SunshineExtensionAPI {
  const invalidate = () => {
    if (runtime === runtimeState) bumpVersion();
  };
  return {
    apiVersion: SUNSHINE_API_VERSION,
    extension: {
      id: extension.id,
      name: extension.name,
      path: extension.path,
    },
    ui,
    host: {
      invoke(method, args = {}) {
        return transport.requestHost(method, cloneJson(args));
      },
    },
    services: {
      list() {
        return transport.requestHost("kernel.listServices", {});
      },
      describe(service) {
        return transport.requestHost("kernel.describeService", { service });
      },
      invoke(service, method, args = {}) {
        return transport.requestHost("service.invoke", {
          service,
          method,
          args: cloneJson(args),
        });
      },
    },
    state: {
      get(path = "") {
        return transport.requestHost("state.get", { path });
      },
      patch(path, value) {
        return transport.requestHost("state.transaction", {
          operations: [{ op: "set", path, value: cloneJson(value) }],
        });
      },
      transaction(operations) {
        return transport.requestHost("state.transaction", {
          operations: cloneJson(operations),
        });
      },
    },
    storage: {
      get<T>(key: string, fallback?: T): T {
        const storage = extensionStorage(extension.id);
        return (Object.prototype.hasOwnProperty.call(storage, key)
          ? cloneJson(storage[key])
          : fallback) as T;
      },
      set(key: string, value: unknown) {
        extensionStorage(extension.id)[key] = cloneJson(value);
        writePersistedStorage();
        invalidate();
      },
      delete(key: string) {
        delete extensionStorage(extension.id)[key];
        writePersistedStorage();
        invalidate();
      },
      clear() {
        persistedStorage[extension.id] = {};
        writePersistedStorage();
        invalidate();
      },
      snapshot() {
        return cloneJson(extensionStorage(extension.id));
      },
    },
    messages: {
      append(type, payload = {}, text = "") {
        const normalizedType = type.trim();
        if (!normalizedType) throw new Error("Sunshine custom messages require a type.");
        return transport.requestHost("app.appendCustomMessage", {
          type: normalizedType,
          payload: cloneJson(payload),
          text,
        });
      },
    },
    registerSurface(slot, rawDefinition) {
      const definition = normalizeSurfaceDefinition(rawDefinition);
      const localId = definition.id?.trim() || `${slot}-${runtimeState.surfaces.size + 1}`;
      const id = scopedId(extension.id, localId);
      runtimeState.surfaces.set(id, {
        id,
        extension,
        slot: slot.trim(),
        order: Number.isFinite(definition.order) ? Number(definition.order) : 0,
        render: renderValue(definition),
      });
      invalidate();
      return () => {
        if (runtimeState.surfaces.delete(id)) invalidate();
      };
    },
    registerComponent(target, rawDefinition) {
      const definition = normalizeSurfaceDefinition(rawDefinition) as SunshineComponentDefinition;
      const normalizedTarget = target.trim();
      if (!normalizedTarget) {
        throw new Error("Sunshine extension components require a target.");
      }
      const localId = definition.id?.trim() ||
        `${normalizedTarget}-${runtimeState.components.size + 1}`;
      const id = scopedId(extension.id, localId);
      const requestedMode = definition.mode?.trim().toLowerCase();
      const mode: SunshineComponentMode = (
        requestedMode === "before" ||
        requestedMode === "after" ||
        requestedMode === "replace" ||
        requestedMode === "wrap" ||
        requestedMode === "hide"
      ) ? requestedMode : "wrap";
      runtimeState.components.set(id, {
        id,
        extension,
        target: normalizedTarget,
        mode,
        order: Number.isFinite(definition.order) ? Number(definition.order) : 0,
        render: mode === "hide" ? undefined : renderValue(definition),
      });
      invalidate();
      return () => {
        if (runtimeState.components.delete(id)) invalidate();
      };
    },
    registerSettings(definition) {
      const localId = definition.id.trim();
      if (!localId) throw new Error("Sunshine extension settings require an id.");
      if (!definition.title.trim()) throw new Error("Sunshine extension settings require a title.");
      if (!Array.isArray(definition.sections) && !Array.isArray(definition.categories)) {
        throw new Error("Sunshine extension settings require sections, categories, or both.");
      }
      const seenSettingIds = new Set<string>();
      const normalizeSections = (sections: SunshineSettingsSection[]) => sections.map((section) => ({
        ...section,
        settings: section.settings.map((setting) => {
          const id = setting.id.trim();
          const type = setting.type ?? "text";
          const label = setting.label?.trim() ?? setting.title?.trim() ?? "";
          const labelFree = type === "divider" || type === "spacer" || type === "item-card" || type === "card" || type === "empty-state" || type === "choice" || type === "radio" || type === "action-row" || type === "chips" || type === "detail-line" || type === "key-value" || type === "pill" || type === "badge" || type === "result-card" || type === "callout";
          if (!id || (!label && !labelFree)) {
            throw new Error("Sunshine extension settings require ids and visible controls require labels.");
          }
          if (seenSettingIds.has(id)) {
            throw new Error(`Sunshine extension setting ids must be unique: ${id}.`);
          }
          seenSettingIds.add(id);
          const storage = extensionStorage(extension.id);
          const storageKey = settingStorageKey(localId, id);
          if (!Object.prototype.hasOwnProperty.call(storage, storageKey)) {
            storage[storageKey] = Object.prototype.hasOwnProperty.call(storage, id)
              ? cloneJson(storage[id])
              : cloneJson(settingDefault(setting));
          }
          return { ...setting, id, label };
        }),
      }));
      const normalized: SunshineSettingsDefinition = {
        ...cloneJson(definition),
        id: localId,
        title: definition.title.trim(),
        sections: Array.isArray(definition.sections) ? normalizeSections(definition.sections) : undefined,
        categories: Array.isArray(definition.categories)
          ? definition.categories.map((category) => {
            const id = category.id.trim();
            if (!id || !category.title.trim() || !Array.isArray(category.sections)) {
              throw new Error("Sunshine extension settings categories require ids, titles, and sections.");
            }
            return { ...category, id, title: category.title.trim(), sections: normalizeSections(category.sections) };
          })
          : undefined,
      };
      const id = scopedId(extension.id, localId);
      const registration = { id, extension, definition: normalized };
      runtimeState.settings.set(id, registration);
      const generatedActions = new Map<string, RegisteredAction>();
      const allSections = [
        ...(normalized.sections ?? []),
        ...(normalized.categories ?? []).flatMap((category) => category.sections),
      ];
      for (const section of allSections) {
        for (const setting of section.settings) {
          const settingAction = `settings:${localId}:${setting.id}`;
          const actionId = scopedId(extension.id, settingAction);
          const existingAction = runtimeState.actions.get(actionId);
          if (existingAction && existingAction.generatedBySettings !== id) continue;
          const generatedAction: RegisteredAction = {
            extension,
            localId: settingAction,
            generatedBySettings: id,
            handler: (payload) => {
              const candidate = payload.value !== undefined ? payload.value : payload.checked;
              const storage = extensionStorage(extension.id);
              const storageKey = settingStorageKey(localId, setting.id);
              if (candidate !== undefined) {
                storage[storageKey] = cloneJson(
                  normalizeSettingValue(setting, candidate),
                );
                writePersistedStorage();
              }
              return { setting: setting.id, value: cloneJson(storage[storageKey]) };
            },
          };
          runtimeState.actions.set(actionId, generatedAction);
          generatedActions.set(actionId, generatedAction);
        }
      }
      writePersistedStorage();
      invalidate();
      return () => {
        if (runtimeState.settings.get(id) === registration) {
          runtimeState.settings.delete(id);
          for (const [actionId, action] of generatedActions) {
            if (runtimeState.actions.get(actionId) === action) runtimeState.actions.delete(actionId);
          }
          invalidate();
        }
      };
    },
    registerComposerMenuItem(definition) {
      const localId = definition.id.trim();
      if (!localId) throw new Error("Sunshine composer menu items require an id.");
      if (!definition.title.trim()) throw new Error("Sunshine composer menu items require a title.");
      const id = scopedId(extension.id, localId);
      runtimeState.composerMenuItems.set(id, {
        id,
        extension,
        definition: { ...cloneJson(definition), id: localId, title: definition.title.trim() },
      });
      invalidate();
      return () => {
        if (runtimeState.composerMenuItems.delete(id)) invalidate();
      };
    },
    registerComposerMenu(definition) {
      return this.registerComposerMenuItem(definition);
    },
    registerMessageType(definition) {
      const type = definition.type.trim();
      if (!type) throw new Error("Sunshine message types require a type.");
      if (!definition.render) throw new Error("Sunshine message types require a renderer.");
      const id = scopedId(extension.id, type);
      runtimeState.messageTypes.set(id, {
        id,
        extension,
        type,
        title: definition.title ?? type,
        icon: definition.icon ?? "extension",
        render: definition.render,
      });
      invalidate();
      return () => {
        if (runtimeState.messageTypes.delete(id)) invalidate();
      };
    },
    registerCustomMessage(definition) {
      return this.registerMessageType(definition);
    },
    registerToolTitle(toolName, runningTitle, completedTitle, priority = 100) {
      const normalizedToolName = toolName.trim();
      const normalizedRunningTitle = runningTitle.trim();
      const normalizedCompletedTitle = completedTitle.trim();
      if (!normalizedToolName) throw new Error("Sunshine tool titles require a tool name.");
      if (!normalizedRunningTitle) throw new Error("Sunshine tool titles require a running title.");
      if (!normalizedCompletedTitle) throw new Error("Sunshine tool titles require a completed title.");
      const normalizedPriority = Number.isFinite(priority)
        ? Math.trunc(Number(priority))
        : 100;
      const sequence = runtimeState.toolTitleSequence + 1;
      runtimeState.toolTitleSequence = sequence;
      const localId = `${normalizedToolName}-${sequence}`;
      const id = scopedId(extension.id, localId);
      runtimeState.toolTitles.set(id, {
        id,
        extension,
        toolName: normalizedToolName,
        runningTitle: normalizedRunningTitle,
        completedTitle: normalizedCompletedTitle,
        priority: normalizedPriority,
        sequence,
      });
      invalidate();
      return () => {
        if (runtimeState.toolTitles.delete(id)) invalidate();
      };
    },
    registerAction(id, handler) {
      const localId = id.trim();
      if (!localId) throw new Error("Sunshine extension actions require an id.");
      const idScoped = scopedId(extension.id, localId);
      runtimeState.actions.set(idScoped, { extension, localId, handler });
      return () => {
        runtimeState.actions.delete(idScoped);
      };
    },
    on(event, handler) {
      const eventName = event.trim();
      if (!eventName) throw new Error("Sunshine extension event handlers require an event name.");
      return createApiEventRegistration(runtimeState, extension, eventName, handler);
    },
    intercept(operation, handler) {
      const operationName = operation.trim();
      if (!operationName) {
        throw new Error("Sunshine operation interceptors require an operation name.");
      }
      return createApiEventRegistration(
        runtimeState,
        extension,
        `operation:${operationName}`,
        handler,
      );
    },
    invalidate,
    notify(message, level = "info") {
      transport.notify(message, level);
    },
  };
}

async function loadFactory(
  descriptor: SunshineExtensionDescriptor,
): Promise<SunshineExtensionFactory | undefined> {
  const jiti = createJiti(import.meta.url, {
    moduleCache: false,
    fsCache: SUNSHINE_JITI_CACHE,
    tryNative: false,
    virtualModules: {
      "@highsockscapital/sunshine-extension-api": sunshineExtensionApiModule,
      "@sunshine/extension-api": sunshineExtensionApiModule,
      "@sunshine/android-extension": sunshineExtensionApiModule,
    },
  });
  const imported = await jiti.import<Record<string, unknown>>(descriptor.path);
  const namedFactory = imported.activateSunshine ?? imported.sunshine;
  const factory = typeof namedFactory === "function"
    ? namedFactory
    : descriptor.explicit && typeof imported.default === "function"
      ? imported.default
      : undefined;
  return factory as SunshineExtensionFactory | undefined;
}

async function cleanupRuntime(
  previous: SunshineRuntimeState,
  errorTarget: SunshineRuntimeState = previous,
  preservedExtensions: Set<LoadedSunshineExtension> = new Set(),
): Promise<void> {
  for (const extension of [...previous.extensions].reverse()) {
    if (preservedExtensions.has(extension)) continue;
    if (!extension.cleanup) continue;
    try {
      await extension.cleanup();
    } catch (error) {
      recordRuntimeError(errorTarget, {
        path: extension.path,
        extension_id: extension.id,
        phase: "load",
        error: `Cleanup failed: ${errorMessage(error)}`,
      });
    }
  }
}

export function configureSunshineExtensionTransport(nextTransport: SunshineExtensionTransport): void {
  transport = nextTransport;
}

function closeExtensionWatchers(): void {
  for (const watcher of extensionWatchers.splice(0)) {
    watcher.close();
  }
  if (extensionWatchTimer) {
    clearTimeout(extensionWatchTimer);
    extensionWatchTimer = undefined;
  }
}

function extensionWatchRoot(extensionPath: string): string {
  const resolvedPath = path.resolve(extensionPath);
  for (const root of [SUNSHINE_EXTENSION_ROOT, PI_EXTENSION_ROOT]) {
    const relative = path.relative(root, resolvedPath);
    if (
      relative === "" ||
      relative === ".." ||
      relative.startsWith(`..${path.sep}`) ||
      path.isAbsolute(relative)
    ) {
      continue;
    }
    const segments = relative.split(path.sep).filter(Boolean);
    return segments.length > 1 ? path.join(root, segments[0]) : root;
  }
  return path.dirname(resolvedPath);
}

function collectExtensionWatchDirectories(
  root: string,
  directories: Set<string>,
): void {
  let entries: fs.Dirent[];
  try {
    if (!fs.statSync(root).isDirectory()) return;
    directories.add(path.resolve(root));
    entries = fs.readdirSync(root, { withFileTypes: true });
  } catch {
    return;
  }
  for (const entry of entries) {
    if (
      !entry.isDirectory() ||
      WATCH_IGNORED_DIRECTORIES.has(entry.name) ||
      entry.name.startsWith(".sunshine-import-")
    ) {
      continue;
    }
    collectExtensionWatchDirectories(path.join(root, entry.name), directories);
  }
}

function configureExtensionWatchers(runtimeState: SunshineRuntimeState): void {
  closeExtensionWatchers();
  const watchDirectories = new Set<string>();
  for (const root of [SUNSHINE_EXTENSION_ROOT, PI_EXTENSION_ROOT]) {
    if (fs.existsSync(root)) watchDirectories.add(root);
  }
  for (const extension of runtimeState.extensions) {
    collectExtensionWatchDirectories(extensionWatchRoot(extension.path), watchDirectories);
  }
  for (const directory of watchDirectories) {
    try {
      const watcher = fs.watch(directory, () => {
        if (extensionWatchTimer) clearTimeout(extensionWatchTimer);
        extensionWatchTimer = setTimeout(() => {
          extensionWatchTimer = undefined;
          void loadSunshineAppExtensions(runtime.cwd, currentLoadOptions);
        }, 200);
        extensionWatchTimer.unref();
      });
      watcher.unref();
      extensionWatchers.push(watcher);
    } catch {
      // A failed watcher must not prevent extensions from loading.
    }
  }
}

async function loadSunshineAppExtensionsUnlocked(
  cwd: string,
  loadOptions: {
    disabledExtensionPaths?: string[];
    disabledPackageSources?: string[];
  } = {},
): Promise<SunshineAppExtensionLoadResult> {
  const previous = runtime;
  const candidate = createEmptyRuntime(cwd);
  const descriptors = await discoverSunshineExtensionEntries(cwd, loadOptions);
  const installedDependencyRoots = new Set<string>();
  const preservedExtensions = new Set<LoadedSunshineExtension>();
  let successfulLoads = 0;
  for (const descriptor of descriptors) {
    const extension: LoadedSunshineExtension = { ...descriptor };
    try {
      const packageRoot = packageRootForExtensionPath(descriptor.path, SUNSHINE_EXTENSION_ROOT);
      if (packageRoot && !installedDependencyRoots.has(packageRoot)) {
        installedDependencyRoots.add(packageRoot);
        await ensureExtensionPackageDependencies(packageRoot);
      }
      if (descriptor.compatibilityError) {
        throw new Error(descriptor.compatibilityError);
      }
      const factory = await loadFactory(descriptor);
      if (!factory) continue;
      const cleanup = await factory(createApi(candidate, extension));
      if (typeof cleanup === "function") extension.cleanup = cleanup;
      candidate.extensions.push(extension);
      successfulLoads += 1;
    } catch (error) {
      removeExtensionRegistrations(candidate, extension);
      recordRuntimeError(candidate, {
        path: descriptor.path,
        extension_id: descriptor.id,
        phase: "load",
        error: errorMessage(error),
      });
      const previousExtension = previous.extensions.find((entry) =>
        path.resolve(entry.path) === path.resolve(descriptor.path)
      );
      if (previousExtension) {
        retainExtensionRegistrations(candidate, previous, previousExtension);
        candidate.extensions.push(previousExtension);
        preservedExtensions.add(previousExtension);
      }
    }
  }
  if (candidate.errors.length > 0 && successfulLoads === 0) {
    await cleanupRuntime(candidate, candidate, preservedExtensions);
    for (const error of candidate.errors) recordRuntimeError(previous, error);
    bumpVersion();
    return {
      reloaded: false,
      errors: candidate.errors,
    };
  }
  runtime = candidate;
  currentLoadOptions = cloneJson(loadOptions);
  configureExtensionWatchers(candidate);
  await cleanupRuntime(previous, candidate, preservedExtensions);
  bumpVersion();
  return {
    reloaded: true,
    errors: candidate.errors,
  };
}

export async function loadSunshineAppExtensions(
  cwd: string,
  loadOptions: {
    disabledExtensionPaths?: string[];
    disabledPackageSources?: string[];
  } = {},
): Promise<SunshineAppExtensionLoadResult> {
  return withRuntimeLock(() => loadSunshineAppExtensionsUnlocked(cwd, loadOptions));
}

async function renderRegisteredView(
  extension: LoadedSunshineExtension,
  render: SunshineSurfaceDefinition["render"],
  phasePath: string,
): Promise<SunshineView> {
  try {
    const value = typeof render === "function"
      ? await render(createRenderContext(extension))
      : render;
    return cloneJson(value);
  } catch (error) {
    recordRuntimeError(runtime, {
      path: phasePath,
      extension_id: extension.id,
      phase: "render",
      error: errorMessage(error),
    });
    return {
      type: "card",
      tone: "error",
      children: [
        {
          type: "text",
          text: `Extension render failed: ${errorMessage(error)}`,
          color: "error",
        },
      ],
    };
  }
}

async function renderRegisteredMessage(
  registration: RegisteredMessageType,
  message: SunshineJsonObject,
): Promise<SunshineView> {
  try {
    const render = registration.render;
    const value = typeof render === "function"
      ? await render({
        ...createRenderContext(registration.extension),
        message: { ...message, ...asObject(message.payload) },
      })
      : render;
    return cloneJson(value);
  } catch (error) {
    recordRuntimeError(runtime, {
      path: registration.extension.path,
      extension_id: registration.extension.id,
      phase: "render",
      error: errorMessage(error),
    });
    return { type: "card", tone: "error", children: [{ type: "text", text: errorMessage(error) }] };
  }
}

async function sunshineAppExtensionSnapshotUnlocked(
  hostContext: SunshineJsonObject = {},
): Promise<SunshineJsonObject> {
  latestHostContext = cloneJson(hostContext);
  const surfaces = [];
  for (const surface of [...runtime.surfaces.values()].sort((left, right) =>
    left.order - right.order || left.id.localeCompare(right.id)
  )) {
    surfaces.push({
      id: surface.id,
      extension_id: surface.extension.id,
      extension_name: surface.extension.name,
      slot: surface.slot,
      order: surface.order,
      tree: await renderRegisteredView(surface.extension, surface.render, surface.id),
    });
  }
  const components = [];
  for (const component of [...runtime.components.values()].sort((left, right) =>
    left.order - right.order || left.id.localeCompare(right.id)
  )) {
    components.push({
      id: component.id,
      extension_id: component.extension.id,
      extension_name: component.extension.name,
      target: component.target,
      mode: component.mode,
      order: component.order,
      tree: component.mode === "hide"
        ? null
        : await renderRegisteredView(component.extension, component.render, component.id),
    });
  }
  const composerMenuItems = [...runtime.composerMenuItems.values()]
    .sort((left, right) =>
      (Number(left.definition.order) || 0) - (Number(right.definition.order) || 0) ||
      left.id.localeCompare(right.id)
    )
    .map((item) => ({
      id: item.id,
      local_id: item.definition.id,
      extension_id: item.extension.id,
      extension_name: item.extension.name,
      title: item.definition.title,
      subtitle: item.definition.subtitle ?? "",
      icon: item.definition.icon ?? "extension",
      order: Number.isFinite(item.definition.order) ? Number(item.definition.order) : 0,
      action: item.definition.action ?? item.definition.id,
      args: cloneJson(item.definition.args ?? {}),
      selected: item.definition.selected === true,
    }));
  const settings = [...runtime.settings.values()]
    .sort((left, right) =>
      (Number(left.definition.order) || 0) - (Number(right.definition.order) || 0) ||
      left.id.localeCompare(right.id)
    )
    .map((item) => ({
      id: item.id,
      local_id: item.definition.id,
      extension_id: item.extension.id,
      extension_name: item.extension.name,
      title: item.definition.title,
      subtitle: item.definition.subtitle ?? "",
      icon: item.definition.icon ?? "settings",
      order: Number.isFinite(item.definition.order) ? Number(item.definition.order) : 0,
      trailing_icon: item.definition.trailingIcon ?? "",
      trailing_action: item.definition.trailingAction ?? "",
      trailing_category: item.definition.trailingCategory ?? "",
      trailing_args: item.definition.trailingArgs ?? {},
      sections: (item.definition.sections ?? []).map((section) => ({
        ...cloneJson(section),
        settings: section.settings.map((setting) => ({
          ...cloneJson(setting),
          value: cloneJson(
            extensionStorage(item.extension.id)[
              settingStorageKey(item.definition.id, setting.id)
            ],
          ),
        })),
      })),
      categories: (item.definition.categories ?? []).map((category) => ({
        ...cloneJson(category),
        trailing_icon: category.trailingIcon ?? "",
        trailing_action: category.trailingAction ?? "",
        trailing_category: category.trailingCategory ?? "",
        trailing_args: category.trailingArgs ?? {},
        hidden: category.hidden === true,
        sections: category.sections.map((section) => ({
          ...cloneJson(section),
          settings: section.settings.map((setting) => ({
            ...cloneJson(setting),
            value: cloneJson(extensionStorage(item.extension.id)[settingStorageKey(item.definition.id, setting.id)]),
          })),
        })),
      })),
    }));
  const messageTypes = [...runtime.messageTypes.values()]
    .sort((left, right) => left.type.localeCompare(right.type))
    .map((item) => ({
      id: item.id,
      type: item.type,
      extension_id: item.extension.id,
      extension_name: item.extension.name,
      title: item.title,
      icon: item.icon,
    }));
  const toolTitles = [...runtime.toolTitles.values()]
    .sort((left, right) =>
      left.priority - right.priority ||
      left.sequence - right.sequence ||
      left.id.localeCompare(right.id)
    )
    .map((item) => ({
      id: item.id,
      extension_id: item.extension.id,
      extension_name: item.extension.name,
      tool_name: item.toolName,
      running_title: item.runningTitle,
      completed_title: item.completedTitle,
      priority: item.priority,
      sequence: item.sequence,
    }));
  const customMessages = [];
  const contextMessages = Array.isArray(hostContext.custom_messages)
    ? hostContext.custom_messages
    : [];
  for (const rawMessage of contextMessages) {
    const message = asObject(rawMessage);
    const type = typeof message.type === "string" ? message.type : "";
    const registration = [...runtime.messageTypes.values()].find((item) => item.type === type);
    if (!registration) continue;
    customMessages.push({
      id: typeof message.id === "string" ? message.id : `${registration.id}:${customMessages.length}`,
      type,
      extension_id: registration.extension.id,
      tree: await renderRegisteredMessage(registration, message),
    });
  }
  return {
    api_version: SUNSHINE_API_VERSION,
    version: runtimeVersion,
    extensions: runtime.extensions.map((extension) => ({
      id: extension.id,
      name: extension.name,
      path: extension.path,
    })),
    surfaces,
    components,
    settings,
    composer_menu_items: composerMenuItems,
    message_types: messageTypes,
    tool_titles: toolTitles,
    custom_messages: customMessages,
    event_names: [...runtime.events.keys()].sort(),
    errors: runtime.errors,
  };
}

export async function sunshineAppExtensionSnapshot(
  hostContext: SunshineJsonObject = {},
): Promise<SunshineJsonObject> {
  return withRuntimeLock(() => sunshineAppExtensionSnapshotUnlocked(hostContext));
}

async function invokeSunshineAppExtensionActionUnlocked(
  extensionId: string,
  actionId: string,
  payload: SunshineJsonObject,
  hostContext: SunshineJsonObject = {},
): Promise<SunshineJsonObject> {
  latestHostContext = cloneJson(hostContext);
  const scopedActionId = scopedId(extensionId, actionId);
  const id = runtime.actions.has(scopedActionId) ? scopedActionId : actionId;
  const action = runtime.actions.get(id);
  if (!action) throw new Error(`Unknown Sunshine extension action: ${actionId}`);
  try {
    const result = await action.handler(
      cloneJson(payload),
      {
        ...createRenderContext(action.extension),
        action: action.localId,
      },
    );
    bumpVersion();
    return {
      invoked: true,
      action: action.localId,
      result: cloneJson(result),
    };
  } catch (error) {
    recordRuntimeError(runtime, {
      path: action.extension.path,
      extension_id: action.extension.id,
      phase: "action",
      error: errorMessage(error),
    });
    bumpVersion();
    throw error;
  }
}

export async function invokeSunshineAppExtensionAction(
  extensionId: string,
  actionId: string,
  payload: SunshineJsonObject,
  hostContext: SunshineJsonObject = {},
): Promise<SunshineJsonObject> {
  return withRuntimeLock(() =>
    invokeSunshineAppExtensionActionUnlocked(
      extensionId,
      actionId,
      payload,
      hostContext,
    )
  );
}

async function dispatchSunshineAppExtensionEventUnlocked(
  eventName: string,
  payload: SunshineJsonObject,
  hostContext: SunshineJsonObject = {},
): Promise<SunshineJsonObject> {
  latestHostContext = cloneJson(hostContext);
  const handlers = runtime.events.get(eventName) ?? [];
  let chainedPayload = cloneJson(payload);
  let cancelled = false;
  let reason = "";
  const results: unknown[] = [];
  for (const registration of handlers) {
    try {
      const rawResult = await registration.handler(
        cloneJson(chainedPayload),
        {
          ...createRenderContext(registration.extension),
          event: eventName,
        },
      );
      results.push(cloneJson(rawResult));
      const result = asObject(rawResult);
      if (result.cancel === true || result.cancelled === true) {
        cancelled = true;
        reason = typeof result.reason === "string" ? result.reason : reason;
      }
      const explicitPayload = asObject(result.payload);
      const patch = Object.keys(explicitPayload).length > 0
        ? explicitPayload
        : Object.fromEntries(
          Object.entries(result).filter(([key]) =>
            !["cancel", "cancelled", "reason", "result"].includes(key)
          ),
        );
      if (Object.keys(patch).length > 0) {
        chainedPayload = { ...chainedPayload, ...cloneJson(patch) };
      }
      if (cancelled) break;
    } catch (error) {
      recordRuntimeError(runtime, {
        path: registration.extension.path,
        extension_id: registration.extension.id,
        phase: "event",
        error: errorMessage(error),
      });
    }
  }
  if (handlers.length > 0) bumpVersion();
  return {
    event: eventName,
    handled: handlers.length > 0,
    cancelled,
    reason,
    payload: chainedPayload,
    results,
  };
}

export async function dispatchSunshineAppExtensionEvent(
  eventName: string,
  payload: SunshineJsonObject,
  hostContext: SunshineJsonObject = {},
): Promise<SunshineJsonObject> {
  return withRuntimeLock(() =>
    dispatchSunshineAppExtensionEventUnlocked(eventName, payload, hostContext)
  );
}

export function sunshineAppExtensionCountForManifest(
  manifest: SunshineJsonObject | undefined,
): number {
  const extensions = asObject(manifest?.sunshine).extensions;
  return Array.isArray(extensions)
    ? extensions.filter((entry) => typeof entry === "string" && entry.trim().length > 0).length
    : 0;
}

function node(type: string, properties: SunshineJsonObject = {}): SunshineJsonObject {
  return { type, ...properties };
}

export const ui = {
  node,
  text(text: string, properties: SunshineJsonObject = {}) {
    return node("text", { text, ...properties });
  },
  code(text: string, properties: SunshineJsonObject = {}) {
    return node("code", { text, ...properties });
  },
  column(children: SunshineView[], properties: SunshineJsonObject = {}) {
    return node("column", { children, ...properties });
  },
  row(children: SunshineView[], properties: SunshineJsonObject = {}) {
    return node("row", { children, ...properties });
  },
  box(children: SunshineView[], properties: SunshineJsonObject = {}) {
    return node("box", { children, ...properties });
  },
  card(children: SunshineView[], properties: SunshineJsonObject = {}) {
    return node("card", { children, ...properties });
  },
  button(label: string, action: string, properties: SunshineJsonObject = {}) {
    return node("button", { label, action, ...properties });
  },
  iconButton(icon: string, action: string, properties: SunshineJsonObject = {}) {
    return node("iconButton", { icon, action, ...properties });
  },
  switch(label: string, checked: boolean, action: string, properties: SunshineJsonObject = {}) {
    return node("switch", { label, checked, action, ...properties });
  },
  input(value: string, action: string, properties: SunshineJsonObject = {}) {
    return node("input", { value, action, ...properties });
  },
  spacer(size = 8, properties: SunshineJsonObject = {}) {
    return node("spacer", { size, ...properties });
  },
  progress(value?: number, properties: SunshineJsonObject = {}) {
    return node("progress", { value, ...properties });
  },
  web(properties: SunshineJsonObject) {
    return node("web", properties);
  },
  core(properties: SunshineJsonObject = {}) {
    return node("core", properties);
  },
} as const satisfies SunshineUi;

export function defineSunshineExtension<T extends SunshineExtensionFactory>(factory: T): T {
  return factory;
}

export const sunshineExtensionApiModule = {
  defineSunshineExtension,
  ui,
};
