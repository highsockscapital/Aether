<p align="center">
  <img src="public/sunshine-mascot.jpg" width="360" alt="Sunshine — orange bob on warm cream" />
</p>

<h1 align="center">Sunshine</h1>

<p align="center">
  <strong>A good housewife of the terminal.</strong><br>
  Sunshine is a personal edition of <a href="https://github.com/Zhou-Shilin/Aether">Aether</a> —
  same Peng bird underneath, wearing a Parisian apron on top.<br>
  She keeps her shelves spotless, buries caches without ceremony, and writes
  herself little notes so tomorrow's Sunshine knows what today's Sunshine did.
</p>

> *"Meow? That was probably a stale lock file."*

### What's different here

- **Personality layer** — Sunshine speaks English with elegant French flourishes, teases gently, and takes quiet, genuine pride in an immaculate workspace.
- **Termux-native life** — she runs inside Termux on your Android device as her home turf: real shell, real files, disk space reclaimed for fun (~4 GB on a good morning) and a notebook at `~/.sunshine-notes/` where memory is kept honest by data entry and curation.

---

Everything below remains Aether's story — because it deserves telling. 🌪️

---

<p align="center">
  <img src="app/src/main/res/drawable-nodpi/aether_mark.png" width="128" height="128" alt="Aether Logo">
</p>

<h1 align="center">Aether</h1>

<p align="center">
  <strong>Soar with local AI.</strong><br>
  A stunning, localized, highly extensible general-purpose AI Agent for Android, iOS and macOS.
</p>

<p align="center">
  <a href="README_zh.md">中文</a> •
  <a href="#-visuals--experience">Visuals & Experience</a> •
  <a href="#-high-extensibility">Extensions</a> •
  <a href="#-core-features">Core Features</a> •
  <a href="https://aether.baimoqilin.com/docs">Documentation</a>
</p>

<p align="center">
  <table>
    <tr>
      <td><img src="public/welcome.jpg" width="280"></td>
      <td><img src="public/agentmode.jpg" width="280"></td>
      <td><img src="public/chat.jpg" width="280"></td>
      <td><img src="public/research.jpg" width="280"></td>
    </tr>
  </table>
</p>

---

## 🌪️ Aether 

> "When the great Peng bird journeys to the Southern Ocean, it flaps the water for three thousand miles, spiraling upward on a whirlwind (*Aether/Fuyao*) to ninety thousand miles, and travels for six months before resting."

**Aether** is dedicated to bringing a modern, local AI Agent experience to mobile and desktop devices (Android, iOS, and macOS). Built on top of the Pi framework, it pairs a minimalist, polished UI with immense extensibility, seamless tool calling, and full extension ecosystem support.

## 🧩 High Extensibility

Aether natively supports loading standard [Pi Extensions](https://github.com/earendil-works/pi). To bring TUI-oriented extensions seamlessly to mobile touchscreens, Aether provides a rich **Extension API** allowing extensions to inject custom settings cards, interactive composer widgets, overlay transcript viewers, prompt interceptors, and semantic tool titles.

| Extension | Description | Repository |
| :--- | :--- | :--- |
| **Pi Web Access*** | Web search across 20+ providers (OpenAI, Brave, Exa, Tavily, Perplexity, Gemini, SearXNG), GitHub repo cloning, PDF extraction, and YouTube/video understanding with native settings and transcript cards. | [AetherExtensions/pi-web-access](https://github.com/AetherExtensions/pi-web-access) |
| **Pi MCP Adapter*** | Model Context Protocol (MCP) gateway supporting Stdio, Streamable HTTP, SSE, and Unix domain sockets with lazy loading, token-saving tool discovery, interactive OAuth authentication, and a visual server management GUI. | [AetherExtensions/pi-mcp-adapter](https://github.com/AetherExtensions/pi-mcp-adapter) |
| **Pi Subagents*** | Claude Code-style autonomous sub-agents with parallel background execution, mid-run steering, live widget & FleetView cards above the composer, conversation overlay viewer, and `@handle` prompt mentions. | [AetherExtensions/pi-subagents](https://github.com/AetherExtensions/pi-subagents) |

*\* means it's an official extension adapted by the Aether team. Add your extension to the list by [submitting a PR](https://github.com/Zhou-Shilin/Aether/pulls).*

Extensions can be installed directly as zip packages in **Settings → Extensions → Import extension**.

For extension development documentation, see <https://aether.baimoqilin.com/docs/extensions/overview.md>.


## 📱 Visuals & Experience

Aether's UI and interactions are heavily inspired by excellent, mature applications like ChatGPT, Codex CLI/App, Gemini, and Poco Agent. Every animation and interaction detail has been carefully polished to break the stereotype that "open-source means cheap and unrefined."

<p align="center">
  <table>
    <tr>
      <td><img src="public/input_bar.jpg" width="280"></td>
      <td><img src="public/tool_execution.jpg" width="280"></td>
      <td><img src="public/msg_options.jpg" width="280"></td>
    </tr>
  </table>
</p>


## ✨ Core Features

- **Stunning UI & Silky Smooth Interactions**: Distilling the design essence of top-tier apps like ChatGPT to create a minimalist, modern, and elegant interface.
- **Pi Harness Kernel**: Powered by the Pi framework, providing the widest LLM provider compatibility and a lightweight, highly efficient Agent execution engine.
- **Extreme Extensibility**: Inherits Pi's complete extension model while providing Aether's proprietary Script Extensions and Native Mods system to deeply customize mobile UI, settings, composer widgets, and runtime behavior.
- **Built-in Alpine VM**: Includes an automatically installed Alpine Linux environment to run shell commands and tools out of the box.
- **Extensible Host Control**: Supports optional Shizuku and Termux integration for direct device manipulation and automation. (Android only)


## 🚀 Quick Start

See <https://aether.baimoqilin.com/docs/quickstart>.


## 🤝 Contributing

This project is being developed sporadically by a 15yo student during their spare time (⁠ʘ⁠ᴗ⁠ʘ⁠✿⁠). Aether is still actively iterating and being polished. If you like this project, please consider giving it a ⭐ Star, or submit PRs and Issues to help make Aether even better!

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/R6R2131N5X)

[![Afdian](https://img.shields.io/badge/Afdian-Sponsor-946ce6?style=social&logo=afdian)](https://afdian.com/a/BaimoQilin)


## Special Thanks

- OpenAI Codex
- Google Gemini
- [Linux DO Community](https://linux.do/)


## Star History

<a href="https://www.star-history.com/?repos=Zhou-Shilin%2FAether&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=Zhou-Shilin/Aether&type=date&theme=dark&legend=top-left&sealed_token=EU5gsuVcs_Ravu88uM-u6K0mIGb0JVSVe65e7hxAoH_ZncZr6UgJy4gc1g-EF61t1btQdTyt7Nyo89r9dQvgFzXlIL8P9ebP6orOqbiVMs7vueE4DyTGaIWho_-VEiLYzE6mW76DqgnU00qG0i_JZmFL08ZPdWMRv0hEkUK9NQi_fBuUMqSOqYQNCMO6" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=Zhou-Shilin/Aether&type=date&legend=top-left&sealed_token=EU5gsuVcs_Ravu88uM-u6K0mIGb0JVSVe65e7hxAoH_ZncZr6UgJy4gc1g-EF61t1btQdTyt7Nyo89r9dQvgFzXlIL8P9ebP6orOqbiVMs7vueE4DyTGaIWho_-VEiLYzE6mW76DqgnU00qG0i_JZmFL08ZPdWMRv0hEkUK9NQi_fBuUMqSOqYQNCMO6" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=Zhou-Shilin/Aether&type=date&legend=top-left&sealed_token=EU5gsuVcs_Ravu88uM-u6K0mIGb0JVSVe65e7hxAoH_ZncZr6UgJy4gc1g-EF61t1btQdTyt7Nyo89r9dQvgFzXlIL8P9ebP6orOqbiVMs7vueE4DyTGaIWho_-VEiLYzE6mW76DqgnU00qG0i_JZmFL08ZPdWMRv0hEkUK9NQi_fBuUMqSOqYQNCMO6" />
 </picture>
</a>

---

## 📄 License

This project is licensed under the **GPL-3.0 License**.

---

<p align="center">
  Built with ❤️ by Shilin "BaimoQilin" Zhou.
</p>
