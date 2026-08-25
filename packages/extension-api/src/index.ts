export type SunshineJsonObject = Record<string, unknown>;

export type SunshineView =
  | SunshineJsonObject
  | SunshineView[]
  | string
  | null
  | undefined;

export type SunshineRenderContext = SunshineJsonObject & {
  extension: {
    id: string;
    name: string;
    path: string;
  };
  storage: SunshineJsonObject;
};

export interface SunshineSurfaceDefinition {
  id?: string;
  order?: number;
  render?:
    | SunshineView
    | ((
      context: SunshineRenderContext,
    ) => SunshineView | Promise<SunshineView>);
  tree?: SunshineView;
}

export type SunshineSettingType =
  | "text"
  | "password"
  | "textarea"
  | "number"
  | "toggle"
  | "select"
  | "dropdown"
  | "segmented"
  | "tab"
  | "tabs"
  | "slider"
  | "button"
  | "link"
  | "label"
  | "divider"
  | "spacer"
  | "item-card"
  | "card"
  | "empty-state"
  | "choice"
  | "radio"
  | "action-row"
  | "chips"
  | "detail-line"
  | "key-value"
  | "pill"
  | "badge"
  | "result-card"
  | "callout";

export interface SunshineSettingOption {
  value: string;
  label: string;
}

export interface SunshineSettingActionItem {
  label: string;
  action: string;
  args?: SunshineJsonObject;
  category?: string;
  tone?: "primary" | "neutral" | "danger";
  enabled?: boolean;
}

export interface SunshineSettingDetailItem {
  label: string;
  value: string;
}

export interface SunshineSettingDefinition {
  id: string;
  label?: string;
  title?: string;
  description?: string;
  subtitle?: string;
  tag?: string;
  pill?: string;
  badge?: string;
  type?: SunshineSettingType;
  default?: string | number | boolean;
  placeholder?: string;
  options?: SunshineSettingOption[];
  min?: number;
  max?: number;
  step?: number;
  action?: string;
  args?: SunshineJsonObject;
  category?: string;
  url?: string;
  icon?: string;
  tone?: "primary" | "neutral" | "danger";
  enabled?: boolean;
  checked?: boolean;
  selected?: boolean;
  toggleAction?: string;
  editAction?: string;
  editCategory?: string;
  editArgs?: SunshineJsonObject;
  deleteAction?: string;
  deleteArgs?: SunshineJsonObject;
  expanded?: boolean;
  actions?: SunshineSettingActionItem[];
  details?: SunshineSettingDetailItem[];
  resultText?: string;
  result?: string;
  buttonLabel?: string;
  multiline?: boolean;
  secret?: boolean;
  settings?: SunshineSettingDefinition[];
}

export interface SunshineSettingsSection {
  id?: string;
  title?: string;
  description?: string;
  settings: SunshineSettingDefinition[];
}

export interface SunshineSettingsDefinition {
  id: string;
  title: string;
  subtitle?: string;
  icon?: string;
  order?: number;
  trailingIcon?: string;
  trailingAction?: string;
  trailingCategory?: string;
  trailingArgs?: SunshineJsonObject;
  sections?: SunshineSettingsSection[];
  categories?: SunshineSettingsCategory[];
}

export interface SunshineSettingsCategory {
  id: string;
  title: string;
  subtitle?: string;
  icon?: string;
  order?: number;
  trailingIcon?: string;
  trailingAction?: string;
  trailingCategory?: string;
  trailingArgs?: SunshineJsonObject;
  hidden?: boolean;
  sections: SunshineSettingsSection[];
}

export interface SunshineComposerMenuItemDefinition {
  id: string;
  title: string;
  subtitle?: string;
  icon?: string;
  order?: number;
  action?: string;
  args?: SunshineJsonObject;
  selected?: boolean;
}

export interface SunshineMessageTypeDefinition {
  type: string;
  title?: string;
  icon?: string;
  render:
    | SunshineView
    | ((context: SunshineRenderContext & { message: SunshineJsonObject }) => SunshineView | Promise<SunshineView>);
}

export type SunshineComponentMode =
  | "before"
  | "after"
  | "replace"
  | "wrap"
  | "hide";

export interface SunshineComponentDefinition extends SunshineSurfaceDefinition {
  mode?: SunshineComponentMode;
}

export interface SunshineActionContext extends SunshineRenderContext {
  action: string;
}

export interface SunshineEventContext extends SunshineRenderContext {
  event: string;
}

export interface SunshineUi {
  node(
    type: string,
    properties?: SunshineJsonObject,
    children?: SunshineView[],
  ): SunshineJsonObject;
  text(text: string, properties?: SunshineJsonObject): SunshineJsonObject;
  code(text: string, properties?: SunshineJsonObject): SunshineJsonObject;
  column(
    children: SunshineView[],
    properties?: SunshineJsonObject,
  ): SunshineJsonObject;
  row(
    children: SunshineView[],
    properties?: SunshineJsonObject,
  ): SunshineJsonObject;
  box(
    children: SunshineView[],
    properties?: SunshineJsonObject,
  ): SunshineJsonObject;
  card(
    children: SunshineView[],
    properties?: SunshineJsonObject,
  ): SunshineJsonObject;
  button(
    label: string,
    action: string,
    properties?: SunshineJsonObject,
  ): SunshineJsonObject;
  iconButton(
    icon: string,
    action: string,
    properties?: SunshineJsonObject,
  ): SunshineJsonObject;
  switch(
    label: string,
    checked: boolean,
    action: string,
    properties?: SunshineJsonObject,
  ): SunshineJsonObject;
  input(
    value: string,
    action: string,
    properties?: SunshineJsonObject,
  ): SunshineJsonObject;
  spacer(size?: number, properties?: SunshineJsonObject): SunshineJsonObject;
  progress(value?: number, properties?: SunshineJsonObject): SunshineJsonObject;
  web(properties: SunshineJsonObject): SunshineJsonObject;
  core(properties?: SunshineJsonObject): SunshineJsonObject;
}

export interface SunshineExtensionAPI {
  readonly apiVersion: 2;
  readonly extension: {
    id: string;
    name: string;
    path: string;
  };
  readonly ui: SunshineUi;
  readonly host: {
    invoke(
      method: string,
      args?: SunshineJsonObject,
    ): Promise<SunshineJsonObject>;
  };
  readonly services: {
    list(): Promise<SunshineJsonObject>;
    describe(service: string): Promise<SunshineJsonObject>;
    invoke(
      service: string,
      method: string,
      args?: SunshineJsonObject,
    ): Promise<SunshineJsonObject>;
  };
  readonly state: {
    get(path?: string): Promise<SunshineJsonObject>;
    patch(path: string, value: unknown): Promise<SunshineJsonObject>;
    transaction(
      operations: Array<{
        op?: "set" | "remove";
        path: string;
        value?: unknown;
      }>,
    ): Promise<SunshineJsonObject>;
  };
  readonly storage: {
    get<T = unknown>(key: string, fallback?: T): T;
    set(key: string, value: unknown): void;
    delete(key: string): void;
    clear(): void;
    snapshot(): SunshineJsonObject;
  };
  readonly messages: {
    append(type: string, payload?: SunshineJsonObject, text?: string): Promise<SunshineJsonObject>;
  };
  registerSurface(
    slot: string,
    definition:
      | SunshineSurfaceDefinition
      | SunshineView
      | ((
        context: SunshineRenderContext,
      ) => SunshineView | Promise<SunshineView>),
  ): () => void;
  registerComponent(
    target: string,
    definition:
      | SunshineComponentDefinition
      | SunshineView
      | ((
        context: SunshineRenderContext,
      ) => SunshineView | Promise<SunshineView>),
  ): () => void;
  registerSettings(definition: SunshineSettingsDefinition): () => void;
  registerComposerMenuItem(definition: SunshineComposerMenuItemDefinition): () => void;
  registerComposerMenu(definition: SunshineComposerMenuItemDefinition): () => void;
  registerMessageType(definition: SunshineMessageTypeDefinition): () => void;
  registerCustomMessage(definition: SunshineMessageTypeDefinition): () => void;
  registerToolTitle(
    toolName: string,
    runningTitle: string,
    completedTitle: string,
    priority?: number,
  ): () => void;
  registerAction(
    id: string,
    handler: (
      payload: SunshineJsonObject,
      context: SunshineActionContext,
    ) => unknown | Promise<unknown>,
  ): () => void;
  on(
    event: string,
    handler: (
      payload: SunshineJsonObject,
      context: SunshineEventContext,
    ) => unknown | Promise<unknown>,
  ): () => void;
  intercept(
    operation: string,
    handler: (
      payload: SunshineJsonObject,
      context: SunshineEventContext,
    ) => unknown | Promise<unknown>,
  ): () => void;
  invalidate(): void;
  notify(message: string, level?: "info" | "warning" | "error"): void;
}

export type SunshineExtensionFactory = (
  api: SunshineExtensionAPI,
) =>
  | void
  | (() => void | Promise<void>)
  | Promise<void | (() => void | Promise<void>)>;

export declare const ui: SunshineUi;

export declare function defineSunshineExtension<
  T extends SunshineExtensionFactory,
>(factory: T): T;
