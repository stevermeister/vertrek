import type { Env } from "../src/ns";

declare module "cloudflare:test" {
  interface ProvidedEnv extends Env {}
}
