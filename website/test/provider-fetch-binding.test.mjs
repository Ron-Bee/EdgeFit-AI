// Regression test: HttpProvider must call the default global fetch
// with a valid receiver. Browsers throw "Illegal invocation" when
// the detached global fetch runs as an object method; Node's
// undici fetch tolerates it, which hid the bug from host tests.
// Run from the repository root: node --test 'website/test/*.test.mjs'
import { describe, it, afterEach } from "node:test";
import assert from "node:assert/strict";
import { HttpProvider } from "../js/provider.js";

const REAL_FETCH = globalThis.fetch;
afterEach(() => {
  globalThis.fetch = REAL_FETCH;
});

describe("HttpProvider default fetch binding", () => {
  it("invokes the default fetch with the global receiver", async () => {
    // Strict stand-in for browser fetch: rejects any receiver that
    // is not globalThis, exactly like Chromium's Web IDL binding.
    globalThis.fetch = async function strictFetch(url, opts) {
      assert.equal(this, globalThis, "default fetch called detached");
      assert.equal(url, "https://example.invalid/v1/models");
      assert.equal(opts?.method, "GET");
      return {
        status: 200,
        headers: { get: () => null },
        text: async () =>
          '{"api_version":"1","artifacts":[],"truncated":false}',
      };
    };
    const prov = new HttpProvider("https://example.invalid");
    const models = await prov.listModels();
    assert.deepEqual(models, { artifacts: [], truncated: false });
  });
});
