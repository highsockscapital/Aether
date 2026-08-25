# 🍊 Sunshine (`sunshine-app`)

> *"A good housewife of the command line, and your sophisticated, playful terminal agent."*

**Sunshine** is a local-first, on-device Android terminal agent built to keep your developer workspace impeccably organized. Forked from [Zhou-Shilin/Aether](https://github.com/Zhou-Shilin/Aether), Sunshine acts as an autonomous dev companion—filing issues, managing builds, and shipping compiled artifacts straight to your device.

---

## ⚡ Key Features

* **On-Device Agent:** Runs shell commands, manages files, and executes ADB tasks directly on-device.
* **Automated CI/CD Pipeline:** Offloads heavy builds to GitHub Actions via `gh` CLI in Termux to conserve local resources.
* **Build Watcher Subagent:** Spawns a lightweight background agent to poll workflow runs and retrieve `.apk` artifacts without blocking conversation.
* **Self-Improving Iteration:** Files tracking issues, creates pull requests, and updates her own app build directly from user feedback.

---

## 🎨 UI & Aesthetics

* **Primary Canvas:** Warm Cream (`#F6F3E7`)
* **Message Cards:** Crisp White (`#FFFFFF`) with Dark Charcoal (`#161610`) borders & typography
* **Accent Color:** Soft Light Orange (`#FF9E43`)

---

## 🛠 Tech Stack

* **Base:** [Aether](https://github.com/Zhou-Shilin/Aether)
* **Environment:** Android / Termux
* **CLI Tooling:** `gh` CLI, `adb`, `git`
* **CI/CD:** GitHub Actions