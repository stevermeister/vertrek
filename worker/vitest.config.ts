import { defineWorkersConfig } from "@cloudflare/vitest-pool-workers/config";

export default defineWorkersConfig({
  test: {
    poolOptions: {
      workers: {
        wrangler: { configPath: "./wrangler.jsonc" },
        miniflare: {
          bindings: { NS_API_KEY: "test-key", VERTREK_KEY: "test-vertrek-key" },
        },
      },
    },
  },
});
