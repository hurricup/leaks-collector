#!/usr/bin/env node
/**
 * Cross-platform NPX wrapper for MCP servers
 *
 * Purpose: Enables MCP servers to run on both Windows and Unix systems with a single configuration.
 *
 * Problem: Windows requires 'cmd /c' to execute npx commands, while Unix systems call npx directly.
 * Solution: This wrapper detects the platform and uses the appropriate command.
 *
 * Usage: Called from mcp.json with node:
 *   "command": "node",
 *   "args": [".ai/mcp/npx-wrapper.js", ...<npx arguments>]
 *
 * The wrapper forwards all arguments to npx transparently and inherits stdio for MCP communication.
 */
const {spawn} = require('child_process')

// Forward all arguments to npx with proper platform handling
const isWindows = process.platform === 'win32'
const command = isWindows ? 'cmd' : 'npx'
const args = isWindows ? ['/c', 'npx', ...process.argv.slice(2)] : process.argv.slice(2)

const child = spawn(command, args, {
  stdio: 'inherit',
  shell: false,
})

child.on('exit', (code) => {
  process.exit(code || 0)
})

child.on('error', (err) => {
  console.error('Failed to start MCP server:', err)
  process.exit(1)
})
