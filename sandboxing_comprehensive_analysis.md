# Comprehensive Analysis of Sandboxing in Modern AI CLI Tools

## Executive Summary
As AI agents transition from simple chat interfaces to "agentic" systems capable of executing code and managing files, sandboxing has evolved from a niche security feature to a foundational architectural requirement. This document explores the technical implementations, internal logic, and security philosophies behind the world's leading AI CLI tools: Google's Gemini CLI, Anthropic's Claude Code, and Moonshot AI's Kimi Code CLI.

---

## 1. The Philosophy of Isolation
Sandboxing is not a monolithic technology; it is a strategy to achieve **Process Isolation** and **Resource Restriction**. In the context of CLI tools, it addresses the "Prompt Injection" and "Hallucination" risks where an AI might inadvertently or maliciously execute dangerous commands like `rm -rf /` or exfiltrate private `.env` files.

### Core Objectives:
*   **Filesystem Jailing:** Restricting the AI's "vision" to the project root.
*   **Network Siloing:** Preventing unauthorized data exfiltration or external command-and-control.
*   **Resource Capping:** Limiting CPU and Memory to prevent Denial of Service (DoS) via "Fork Bombs."
*   **Identity Mapping:** Ensuring the AI runs as a low-privilege user even if it thinks it is `root`.

---

## 2. Deep Dive: Tool-Specific Implementations

### A. Google Gemini CLI: The Kernel-Level Guardian
Gemini CLI utilizes a modular approach, offering "drivers" that hook directly into the Operating System's security framework.

#### Internal Logic (macOS Seatbelt):
On macOS, Gemini leverages **Seatbelt** (the engine behind the App Sandbox).
1.  **Policy Compilation:** It takes a Lisp-based profile (e.g., `(deny file-write*)`) and compiles it into a binary blob.
2.  **The Sandbox Hook:** It uses the `sandbox_init` system call. Once this call is made, the process is "caged."
3.  **MACF Enforcement:** Every system call (syscall) is intercepted by the **Mandatory Access Control Framework** in the XNU kernel. If the AI tries to write to `/etc/`, the kernel returns `EPERM` before the disk is even touched.

#### Containerization Driver:
For Linux or robust cross-platform use, it defaults to **Docker/Podman**, providing a virtualized network stack and a completely separate process tree.

---

### B. Anthropic Claude Code: The Permission-Centric Agent
Claude Code prioritizes a "Human-in-the-loop" security model combined with OS-level primitives.

#### Internal Logic:
1.  **Bubblewrap (Linux):** On Linux, Claude uses `bwrap` to create unprivileged namespaces. It uses `pivot_root` to swap the real root filesystem with a read-only view of the OS and a writeable view of the project folder.
2.  **The Approval Loop:** Unlike purely automated tools, Claude's internal logic includes a "Sensitivity Classifier." If a generated command involves network access or system-wide changes, the internal state machine pauses and triggers a terminal UI prompt for user approval.
3.  **Escape Hatches:** It provides a secure mechanism to run commands outside the sandbox *only* when the user provides a one-time cryptographic-like approval through the CLI.

---

### C. Moonshot AI (Kimi Code): The Protocol-Based Abstractor
Kimi Code CLI (and its integration with tools like Zed) moves away from "restricting the process" toward "abstracting the capability" using the **Model Context Protocol (MCP)**.

#### Internal Logic:
1.  **The MCP Boundary:** Kimi does not have a "terminal" in the traditional sense; it has a **JSON-RPC Bridge**.
2.  **Tool Servers:** All system interactions happen on an MCP Server. This server can be hosted on a separate machine, a Docker container, or a restricted local process.
3.  **Functional Whitelisting:** Instead of denying "bad" commands, Kimi only knows "good" functions (e.g., `list_files`, `edit_block`). It is physically impossible for the AI to execute a command that isn't defined in the MCP schema.
4.  **Verification Cycle:** Kimi employs an autonomous `Plan -> Execute -> Observe -> Verify` loop. It will often spin up a "Transient Sandbox" to run tests and verify its own code before ever presenting the result to the user.

---

## 3. Comparative Technical Matrix

| Feature | Gemini CLI | Claude Code | Kimi Code CLI |
| :--- | :--- | :--- | :--- |
| **Primary Driver** | macOS Seatbelt / Docker | Bubblewrap / Seatbelt | MCP (Model Context Protocol) |
| **Isolation Level** | Kernel/Container Level | Namespace Level | Protocol/Interface Level |
| **Network Access** | Restricted by Profile | Restricted by Permission | Restricted by Server Config |
| **Philosophy** | "Secure the syscalls" | "Ask the user" | "Standardize the tools" |
| **Best For** | High-performance local dev | Complex refactoring | Agentic autonomy |

---

## 4. Implementation Guide: Building Your Own Sandbox

To implement these logics in your own scripts or CLI tools, follow these "Best Practice" patterns:

### Method 1: The "Jailer" Pattern (macOS)
Use a minimal Seatbelt profile to restrict a script to the current directory:
```bash
# Define a profile that allows reading the system but writing only to '.'
sandbox-exec -p '
(version 1)
(deny default)
(allow file-read*)
(allow file-write* (subpath "."))
(allow process-fork)
(allow network-outbound)
' python3 agent.py
```

### Method 2: The "Disposable" Pattern (Docker)
Always run untrusted AI code in a container that is destroyed immediately after execution:
```bash
docker run --rm 
  --network none 
  --memory "256m" 
  --cpus "0.5" 
  --user 1000:1000 
  -v $(pwd):/workspace 
  -w /workspace 
  node:alpine node generated_code.js
```

### Method 3: The "Abstraction" Pattern (MCP-style)
Wrap your shell in a Python script that only accepts specific, pre-validated arguments.
*   **Logic:** Instead of `run(cmd)`, use `run_git_commit(message)`.
*   **Result:** The AI can only provide the *message*, never the *command structure*.

---

## 5. Conclusion: The Future of AI Safety
The next frontier of sandboxing involves **Zero-Knowledge Execution** and **Formal Verification**, where the sandbox doesn't just block "bad" actions but mathematically proves that the AI's proposed plan aligns with the user's intent before a single line of code is run. As tools like Kimi, Claude, and Gemini continue to evolve, the boundary between the "AI's imagination" and the "User's hardware" will become increasingly secure and invisible.
