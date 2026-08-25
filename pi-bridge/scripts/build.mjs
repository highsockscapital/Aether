import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";

const deduplicateDependencies = {
  name: "deduplicate-dependencies",
  setup(context) {
    context.onResolve(
      {
        filter: /^(?:@earendil-works\/pi-agent-core|@earendil-works\/pi-ai|@earendil-works\/pi-tui|jiti)(?:\/.*)?$/,
      },
      ({ path }) => ({ path: fileURLToPath(import.meta.resolve(path)) }),
    );
  },
};

const nodeBundleSourcePatches = {
  name: "node-bundle-source-patches",
  setup(context) {
    context.onLoad(
      { filter: /@earendil-works\/pi-coding-agent\/dist\/core\/extensions\/loader\.js$/ },
      async ({ path }) => {
        const source = await readFile(path, "utf8");
        const original = "...(isBunBinary\n            ? { virtualModules: VIRTUAL_MODULES, tryNative: false }";
        const replacement = "...(true\n            ? { virtualModules: VIRTUAL_MODULES, tryNative: false }";
        if (!source.includes(original)) {
          throw new Error("Pi extension loader structure changed; update the Node bundle patch.");
        }
        return {
          contents: source.replace(original, replacement),
          loader: "js",
        };
      },
    );
    context.onLoad(
      { filter: /@earendil-works\/pi-ai\/dist\/api\/openai-completions\.js$/ },
      async ({ path }) => {
        const source = await readFile(path, "utf8");
        const original = "reasoning: rawUsage.completion_tokens_details?.reasoning_tokens || 0,";
        const replacement =
          "reasoning: rawUsage.completion_tokens_details?.reasoning_tokens ?? rawUsage.reasoning_tokens,";
        if (!source.includes(original)) {
          throw new Error("Pi OpenAI completion usage structure changed; update the reasoning token patch.");
        }
        return {
          contents: source.replace(original, replacement),
          loader: "js",
        };
      },
    );
  },
};

const commonOptions = {
  bundle: true,
  platform: "node",
  format: "esm",
  target: "node22.19",
  minify: true,
  legalComments: "none",
  banner: {
    js: "import { createRequire as __sunshineCreateRequire } from 'node:module';const require = __sunshineCreateRequire(import.meta.url);",
  },
};

await Promise.all([
  build({
    ...commonOptions,
    entryPoints: ["src/bridge.ts"],
    outfile: "dist/bridge.mjs",
    plugins: [deduplicateDependencies, nodeBundleSourcePatches],
  }),
  build({
    ...commonOptions,
    entryPoints: ["src/extension-bridge.ts"],
    outfile: "dist/extension-bridge.mjs",
  }),
]);
