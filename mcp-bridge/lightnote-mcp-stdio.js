#!/usr/bin/env node

/**
 * Standard stdio MCP bridge for LightNote.
 *
 * Codex launches this script as a local MCP server. The script speaks MCP over
 * newline-delimited JSON-RPC on stdin/stdout, then forwards requests to the
 * LightNote HTTP JSON-RPC endpoint.
 */

const httpEndpoint = process.env.LIGHTNOTE_MCP_URL || "http://localhost:8081/mcp";

let buffer = "";
let queue = Promise.resolve();

process.stdin.setEncoding("utf8");
process.stdin.on("data", (chunk) => {
  buffer += chunk;
  let newlineIndex;
  while ((newlineIndex = buffer.indexOf("\n")) >= 0) {
    const line = buffer.slice(0, newlineIndex).trim();
    buffer = buffer.slice(newlineIndex + 1);
    if (line.length === 0) {
      continue;
    }
    enqueue(line);
  }
});

process.stdin.on("end", () => {
  const line = buffer.trim();
  buffer = "";
  if (line.length > 0) {
    enqueue(line);
  }
  queue.finally(() => process.exit(0));
});

function enqueue(line) {
  queue = queue
    .then(() => handleLine(line))
    .catch((error) => {
      log(`Unhandled MCP bridge error: ${error.stack || error.message}`);
    });
}

async function handleLine(line) {
  let message;
  try {
    message = JSON.parse(line);
  } catch (error) {
    write({
      jsonrpc: "2.0",
      id: null,
      error: { code: -32700, message: `Parse error: ${error.message}` },
    });
    return;
  }

  if (!message.method) {
    return;
  }

  if (message.method === "notifications/initialized") {
    return;
  }

  const response = await forward(message);
  if (message.id !== undefined && message.id !== null) {
    write(response);
  }
}

async function forward(message) {
  try {
    const response = await fetch(httpEndpoint, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "accept": "application/json, text/event-stream",
        "mcp-protocol-version": "2025-06-18",
      },
      body: JSON.stringify(message),
    });

    if (!response.ok) {
      return errorResponse(message.id, -32000, `LightNote MCP HTTP ${response.status}: ${await response.text()}`);
    }

    const text = await response.text();
    if (!text.trim()) {
      return { jsonrpc: "2.0", id: message.id, result: {} };
    }
    return JSON.parse(text);
  } catch (error) {
    return errorResponse(
      message.id,
      -32000,
      `Cannot reach LightNote MCP endpoint ${httpEndpoint}: ${error.message}`
    );
  }
}

function errorResponse(id, code, message) {
  return {
    jsonrpc: "2.0",
    id: id ?? null,
    error: { code, message },
  };
}

function write(message) {
  process.stdout.write(`${JSON.stringify(message)}\n`);
}

function log(message) {
  process.stderr.write(`[lightnote-mcp-stdio] ${message}\n`);
}
