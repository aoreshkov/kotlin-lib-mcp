package com.example.tiny

/**
 * Where a line of a greeting card goes.
 *
 * @property label what is printed there. Shown verbatim.
 * @property x horizontal offset in columns
 */
public data class Placement(
    val label: String,
    var x: Int = 0,
    private val secret: String = "",
    width: Int = 1,
)

/** Places [label] at the left margin. */
public fun place(label: String): Placement = Placement(label)

/** Places [label] at column [x]; an overload, so the index keys it `place#2`. */
public fun place(label: String, x: Int): Placement = Placement(label, x)

public abstract class Card {
    protected abstract fun layout(): Placement

    protected open class Section {
        public fun render(): String = ""
    }
}

/** Overrides a protected member without restating `protected`, which only resolution can see. */
public class BirthdayCard : Card() {
    override fun layout(): Placement = Placement("Happy birthday")
}

public class Envelope {
    private class Seal {
        fun press(): Unit = Unit
    }
}

internal open class Draft {
    protected fun scribble(): Unit = Unit
}

public interface Punctuator {
    public fun punctuate(line: String, mark: String = "!"): String
}

/** Inherits `mark`'s default, which has no text of its own here. */
public class QuietPunctuator : Punctuator {
    override fun punctuate(line: String, mark: String): String = line
}

public fun greetFormally(
    name: String,
    salutation: String = "Good morning to you, our most honoured and esteemed guest of this evening",
): String = "$salutation, $name"
