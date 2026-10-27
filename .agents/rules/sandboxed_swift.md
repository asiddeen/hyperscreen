---
description: Ensure Swift compilation works reliably inside the sandbox on macOS
always_on: true
---

# Sandboxed Swift Compilation on macOS

When executing one-off Swift scripts or compiling Swift binaries using `swiftc` or `swift` within sandboxed terminal execution:
- Always supply `-module-cache-path /tmp/swift-cache` to prevent Clang module compilation errors in `/var/folders/` (`Operation not permitted`).
- Example:
  ```bash
  swiftc -module-cache-path /tmp/swift-cache script.swift -o /tmp/script && /tmp/script
  ```
