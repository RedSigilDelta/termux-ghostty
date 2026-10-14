package com.mrndtvndv.processicons

import androidx.annotation.DrawableRes
import java.util.Locale

/*
 * Brand marks are vendored from Herdr's Apache-2.0 repository:
 * https://github.com/herdrdev/herdr/tree/master/website/assets/agent-icons
 * The Pi mark is sourced from https://pi.dev/logo-on-dark.svg.
 * The PiG (Pi in Go) mark is adapted from the PiG pixel pig logo
 * (https://pi-in-go.dev/pig-pixel-green-display.png, press kit
 * https://pi-in-go.dev/press-kit/) and redrawn as a monochrome silhouette
 * for status tinting.
 * The omp (Oh My Pi) mark is sourced from:
 * https://github.com/can1357/oh-my-pi/blob/main/assets/icon.svg
 * The Prime Agent mark is sourced from:
 * https://github.com/PrimeIntellect-ai/prime-agent/blob/main/assets/brand/prime-butterfly.svg
 * The Cline mark is sourced from https://uxwing.com/cline-ai-icon/.
 * The Neovim mark is sourced from https://neovim.io/logos/neovim-mark.svg.
 * The fish mark is sourced from Simple Icons (CC0 1.0, https://github.com/simple-icons/simple-icons/blob/develop/LICENSE.md):
 * https://github.com/simple-icons/simple-icons/blob/develop/icons/fishshell.svg
 * The Java/OpenJDK mark is sourced from Simple Icons (CC0 1.0):
 * https://github.com/simple-icons/simple-icons/blob/develop/icons/openjdk.svg
 * The Bun mark is sourced from Simple Icons (CC0 1.0):
 * https://github.com/simple-icons/simple-icons/blob/develop/icons/bun.svg
 * The Git mark is sourced from Simple Icons (CC0 1.0):
 * https://github.com/simple-icons/simple-icons/blob/develop/icons/git.svg
 * The fx mark is sourced from https://fx.sh/favicon.png.
 * The Cargo process icon is an original generic crate mark and does not reproduce
 * the Rust/Cargo trademarks.
 */
@Suppress("CyclomaticComplexMethod")
@DrawableRes
fun agentIconResource(agent: String?): Int? = when (agent?.lowercase(Locale.ROOT)) {
    "amp" -> R.drawable.agent_amp
    "agy", "antigravity" -> R.drawable.agent_antigravity
    "claude" -> R.drawable.agent_claude
    "cline" -> R.drawable.agent_cline
    "codex" -> R.drawable.agent_codex
    "copilot", "github-copilot" -> R.drawable.agent_copilot
    "cursor" -> R.drawable.agent_cursor
    "droid" -> R.drawable.agent_droid
    "fx" -> R.drawable.agent_fx
    "git", "lazygit" -> R.drawable.agent_git
    "grok" -> R.drawable.agent_grok
    "hermes" -> R.drawable.agent_hermes
    "kimi" -> R.drawable.agent_kimi
    "kiro" -> R.drawable.agent_kiro
    "mastracode" -> R.drawable.agent_mastracode
    "omp" -> R.drawable.agent_omp
    "opencode" -> R.drawable.agent_opencode
    "pi" -> R.drawable.agent_pi
    "pig" -> R.drawable.agent_pig
    "prime", "prime-agent", "primeintellect" -> R.drawable.agent_prime
    "qoder", "qodercli" -> R.drawable.agent_qoder
    else -> null
}

@DrawableRes
fun processIconResource(processName: String?): Int? = when (
    processName?.lowercase(Locale.ROOT)
) {
    "nvim" -> R.drawable.agent_nvim
    "fish" -> R.drawable.agent_fish
    "fx" -> R.drawable.agent_fx
    "java", "javac", "openjdk" -> R.drawable.agent_java
    "bun", "bunx" -> R.drawable.agent_bun
    "cargo", "rustc" -> R.drawable.agent_cargo
    "git", "lazygit", "gitui", "tig" -> R.drawable.agent_git
    "pig" -> R.drawable.agent_pig
    else -> null
}

/** Icon for a process or agent name, checking process marks first. */
@DrawableRes
fun commandIconResource(name: String?): Int? = processIconResource(name) ?: agentIconResource(name)

/** Icon for an OSC 7501 program `app` name; maps names such as `claude-code` onto the agent marks. */
@DrawableRes
fun programIconResource(app: String?): Int? {
    val name = app?.lowercase(Locale.ROOT) ?: return null
    val key = when {
        name.startsWith("claude") -> "claude"
        name.startsWith("codex") -> "codex"
        else -> name
    }
    return agentIconResource(key) ?: processIconResource(key)
}
