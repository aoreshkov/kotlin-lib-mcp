package com.example.tiny

/** The current way to greet; the undeprecated control. */
public fun newGreet(name: String): String = "Hello, $name!"

@Deprecated(
    "Use newGreet instead",
    ReplaceWith("newGreet(name)", "com.example.tiny.newGreet", "kotlin.text.trim"),
)
public fun oldGreet(name: String): String = newGreet(name)

@Deprecated(
    message = "Greeting by number was a mistake",
    level = DeprecationLevel.ERROR,
    replaceWith = kotlin.ReplaceWith(expression = "newGreet(id.toString())"),
)
public fun greetById(id: Int): String = newGreet(id.toString())

@Deprecated("Kept for binary compatibility", level = kotlin.DeprecationLevel.HIDDEN)
public fun greetLoudly(name: String): String = newGreet(name).uppercase()

@Deprecated("Use DefaultGreeter")
public class LegacyGreeter : Greeter {
    override fun greet(name: String): String = newGreet(name)
}

/**
 * Deprecated with a message that is a constant, not a literal: only resolution can read it, so this
 * is the declaration that tells the resolved path from the PSI fallback.
 */
@Deprecated(LEGACY_REASON)
public val legacyGreeting: String = "Hi"

public const val LEGACY_REASON: String = "Greetings are functions now"
