package com.example.tiny

/**
 * Greets people politely. Implementations decide the salutation.
 *
 * This second paragraph is description, not summary.
 */
public interface Greeter {
    /**
     * Builds a greeting line for one person.
     *
     * @param name who to greet
     * @return the finished greeting
     */
    public fun greet(name: String): String
}

/** Default [Greeter] with a fixed salutation. */
public open class DefaultGreeter(private val salutation: String = "Hello") : Greeter {
    override fun greet(name: String): String = "$salutation, $name!"
}

internal class HiddenHelper {
    fun shhh(): Int = 42
}

/** The library version. */
public const val VERSION: String = "1.0"

/** Greets [name] using [greeter]. */
public fun <T : Greeter> greetWith(greeter: T, name: String): String = greeter.greet(name)

/**
 * Greets everyone in [names], joining the lines with [separator].
 *
 * Exists to pin default-value rendering: [greeter] and [separator] are optional, and a signature
 * that hides that is wrong in a way no type check would catch.
 */
public fun greetEveryone(
    names: List<String>,
    greeter: Greeter = DefaultGreeter(),
    separator: String = ", ",
): String = names.joinToString(separator) { greeter.greet(it) }
