/**
 * Sunshine per-agent credentials.
 *
 * Sunshine (the host app) publishes the subagent OpenRouter API keys it holds
 * in app settings onto globalThis under a well-known symbol when it creates
 * the native session. This module is the read side: at spawn time the runner
 * resolves the key for a specific agent and wraps the inherited ModelRuntime
 * so that every auth lookup performed by that child session returns the
 * agent's own key instead of the main session's credential.
 *
 * The symbol + globalThis handoff mirrors src/sunshine.ts: the bridge and this
 * extension are loaded by separate loaders, so shared process state is the
 * only reliable channel.
 */

export type SunshineSubagentCredentials = {
	/** Key used when an agent has no explicit override of its own. */
	sharedApiKey?: string;
	/** Per-agent keys keyed by agent name/handle (e.g. "build-watcher"). */
	overrides?: Record<string, string>;
};

const SUNSHINE_SUBAGENT_CREDENTIALS = Symbol.for("sunshine.subagent-credentials");

export function publishSunshineSubagentCredentials(
	credentials: SunshineSubagentCredentials,
): void {
	(globalThis as Record<PropertyKey, unknown>)[SUNSHINE_SUBAGENT_CREDENTIALS] = credentials;
}

function readSunshineSubagentCredentials(): SunshineSubagentCredentials | undefined {
	try {
		return (globalThis as Record<PropertyKey, unknown>)[SUNSHINE_SUBAGENT_CREDENTIALS] as
			| SunshineSubagentCredentials
			| undefined;
	} catch {
		return undefined;
	}
}

function normalizeAgentName(name: string): string {
	return name.trim().toLowerCase().replace(/[\s_]+/g, "-");
}

/**
 * Resolve the OpenRouter API key for one spawned agent:
 * explicit per-agent override first, then the shared key. Empty when the
 * host published nothing or the agent has neither override nor shared key.
 */
export function resolveSunshineSubagentApiKey(...names: Array<string | undefined>): string {
	const candidates = names
		.filter((name): name is string => typeof name === "string" && name.trim().length > 0)
		.flatMap((name) => [name.trim(), normalizeAgentName(name)]);
	if (candidates.length === 0) return "";
	const credentials = readSunshineSubagentCredentials();
	if (!credentials) return "";
	const overrides = credentials.overrides ?? {};
	for (const candidate of candidates) {
		const key = overrides[candidate];
		if (typeof key === "string" && key.trim().length > 0) return key.trim();
	}
	const shared = credentials.sharedApiKey;
	return typeof shared === "string" ? shared.trim() : "";
}

/**
 * Wrap a ModelRuntime so every getAuth() answer carries `apiKey`. The child
 * session calls modelRuntime.getAuth(model) before each request; overriding
 * at this boundary scopes the key to this spawn without touching the shared
 * parent runtime or any global env state.
 */
export function wrapModelRuntimeWithApiKey(runtime: unknown, apiKey: string): unknown {
	return new Proxy(runtime as object, {
		get(target, property, receiver) {
			if (property === "getAuth") {
				const original = Reflect.get(target, property, target) as (
					modelOrProvider: unknown,
					overrides?: unknown,
				) => Promise<unknown>;
				return async (modelOrProvider: unknown, overrides?: unknown) => {
					const result = (await original.call(target, modelOrProvider, overrides)) as
						| { auth?: Record<string, unknown>; source?: unknown }
						| undefined;
					if (result && typeof result === "object" && result.auth) {
						return {
							...result,
							auth: { ...result.auth, apiKey },
							source: "Sunshine subagent credentials",
						};
					}
					return { auth: { apiKey }, source: "Sunshine subagent credentials" };
				};
			}
			const value = Reflect.get(target, property, target);
			return typeof value === "function" ? (value as (...args: unknown[]) => unknown).bind(target) : value;
		},
	});
}
