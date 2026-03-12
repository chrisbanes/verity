package me.chrisbanes.verity.agent

private val CODE_FENCE = Regex("```\\w*\\n?|```")

internal fun String.stripCodeFences(): String = replace(CODE_FENCE, "").trim()
