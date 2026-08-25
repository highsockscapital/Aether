// Lightweight handoff between the Pi extension (index.ts) and the Sunshine
// Script Mod (sunshine.ts). Keep this module free of pi/MCP SDK imports so the
// two loaders can share it without pulling each other's dependency graphs.

export type McpSunshineServerStatus =
  | "connected"
  | "cached"
  | "failed"
  | "needs-auth"
  | "not-connected"
  | "disabled";

export interface McpSunshineServerSnapshot {
  name: string;
  status: McpSunshineServerStatus;
  toolCount: number;
  resourceCount?: number;
  failedAgoSeconds?: number;
  disabled: boolean;
}

export interface McpSunshineSnapshot {
  ready: boolean;
  configPath: string;
  servers: McpSunshineServerSnapshot[];
  totalTools: number;
  totalResources: number;
  connectedCount: number;
  disabledCount: number;
  /** Public Pi tool names registered as direct MCP tools in the current session. */
  toolNames?: string[];
}

export interface McpSunshineBridge {
  api?: McpSunshineBridgeApi;
  onStatusChanged?: (bridge?: McpSunshineBridge | undefined) => void;
  getSnapshot(): McpSunshineSnapshot;
  reconnect(serverName: string): Promise<{ ok: boolean; message: string }>;
  reconnectAll(): Promise<{ ok: boolean; message: string }>;
  startAuth(serverName: string): Promise<{ ok: boolean; message: string; authorizationUrl?: string }>;
  completeAuth(serverName: string, input: string): Promise<{ ok: boolean; message: string }>;
  inspect(serverName: string, operation: "tools" | "resources" | "prompts"): Promise<{ ok: boolean; message: string; details?: string }>;
  logout(serverName: string): Promise<{ ok: boolean; message: string }>;
  reload(): Promise<{ ok: boolean; message: string }>;
}

interface McpSunshineBridgeApi {
  invalidate?: () => void;
}

interface BridgeState {
  api?: McpSunshineBridgeApi;
  onBridge?: (bridge: McpSunshineBridge) => void;
}

const BRIDGE_KEY = Symbol.for("pi-mcp-adapter.sunshine-bridge");
const API_KEY = Symbol.for("pi-mcp-adapter.sunshine-api");

export function readMcpSunshineBridge(): McpSunshineBridge | undefined {
  return (globalThis as Record<PropertyKey, unknown>)[ BRIDGE_KEY] as McpSunshineBridge | undefined;
}

function readBridgeState(): BridgeState | undefined {
  return (globalThis as Record<PropertyKey, unknown>)[API_KEY] as BridgeState | undefined;
}

/** Installed by the Pi extension. Re-registering replaces any previous activation. */
export function registerMcpSunshineBridge(bridge: McpSunshineBridge): void {
  (globalThis as Record<PropertyKey, unknown>)[BRIDGE_KEY] = bridge;
  const state = readBridgeState();
  if (!state) return;
  if (state.api !== undefined) bridge.api = state.api;
  if (state.onBridge !== undefined) bridge.onStatusChanged = () => state.onBridge?.(bridge);
  state.onBridge?.(bridge);
  state.api?.invalidate?.();
}

/** Remove a bridge installed by this Pi activation. */
export function unregisterMcpSunshineBridge(bridge: McpSunshineBridge): void {
  const current = readMcpSunshineBridge();
  if (current !== bridge) return;
  delete (globalThis as Record<PropertyKey, unknown>)[BRIDGE_KEY];
}

/** Attach the Sunshine API half of the handoff when the Script Mod loads. */
export function attachMcpSunshineApi(
  api: McpSunshineBridgeApi,
  onBridge?: (bridge: McpSunshineBridge) => void,
): void {
  (globalThis as Record<PropertyKey, unknown>)[API_KEY] = { api, onBridge };
  const bridge = readMcpSunshineBridge();
  if (!bridge) return;
  bridge.api = api;
  if (onBridge !== undefined) bridge.onStatusChanged = () => onBridge?.(bridge);
  onBridge?.(bridge);
  api.invalidate?.();
}

