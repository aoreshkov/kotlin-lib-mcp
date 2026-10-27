package com.example.tiny

import com.missing.dep.Widget

/** Renders a widget through a type this library cannot resolve. */
public fun render(widget: Widget): Widget = widget

@Deprecated("Widgets are going away", ReplaceWith("render(widget)"))
public fun renderLegacy(widget: Widget): Widget = render(widget)

/** Degraded by `Widget`, with a return type ending in "by" and a block body. */
public fun enterLobby(widget: Widget): Lobby {
    return Lobby()
}

public class Lobby
